package com.fc.freer.call;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import com.fc.fc_ajdk.call.CallSignal;
import com.fc.fc_ajdk.call.Delegation;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.call.engine.AudioIo;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The {@code :voice} process's end of a call or meeting (VOICE_SPEC §11.3):
 * it runs the {@link CallSession} or {@link MeetingSession}, with libopus,
 * the audio devices, the call's FUDP nodes and the audio routing, and talks
 * to the main process only through {@link VoiceProtocol} messages. A memory
 * bug in any of it can reach the current call, never the identity's key or
 * the wallet, which this process never holds.
 * <p>
 * One call or meeting at a time, as in the main process. If the main process
 * goes away, so does the call: its signalling lived there.
 */
public final class VoiceService extends Service {

    private static final String TAG = "VoiceService";
    private static final long STATUS_EVERY_MS = 1_000;
    private static final Gson GSON = new Gson();

    /** For {@link CallService}'s hang-up button, which runs in this process too. */
    private static volatile VoiceService running;

    private final Handler handler = new Handler(Looper.getMainLooper(), this::handle);
    private final Messenger messenger = new Messenger(handler);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "voice-status");
        t.setDaemon(true);
        return t;
    });
    private CallAudio audio;
    private volatile Messenger client;
    private volatile boolean speaker;

    // Touched on the main looper only.
    private CallSession call;
    private MeetingSession meeting;
    private RemoteSecrets secrets;
    private ScheduledFuture<?> status;

    @Override
    public void onCreate() {
        super.onCreate();
        audio = new CallAudio(this);
        running = this;
        TimberLogger.i(TAG, "voice process up");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        endAll("the voice service stopped");
        running = null;
        timer.shutdownNow();
        super.onDestroy();
    }

    /** The ongoing-call notification's hang-up button: signalling is the main process's. */
    static void requestHangup() {
        VoiceService s = running;
        if (s != null) s.send(VoiceProtocol.EVT_HANGUP_REQUEST, new Bundle());
    }

    // ===== Commands =====

    private boolean handle(Message msg) {
        Bundle b = msg.getData();
        try {
            switch (msg.what) {
                case VoiceProtocol.REGISTER -> register(msg.replyTo);
                case VoiceProtocol.CALL_START -> startCall(b);
                case VoiceProtocol.CALL_ANSWERED -> {
                    CallSession c = call;
                    if (c != null && c.callId().equals(b.getString(VoiceProtocol.CALL_ID))) {
                        c.onAnswered(b.getByteArray(VoiceProtocol.SECRET),
                                Delegation.fromJson(b.getString(VoiceProtocol.PEER_DELEGATION)));
                    }
                }
                case VoiceProtocol.CALL_END -> endCall(b.getString(VoiceProtocol.CALL_ID));
                case VoiceProtocol.SET_MUTED -> {
                    boolean on = b.getBoolean(VoiceProtocol.ON);
                    if (call != null) call.setMuted(on);
                    if (meeting != null) meeting.setMuted(on);
                    pushStatus();
                }
                case VoiceProtocol.SET_SPEAKER -> {
                    speaker = b.getBoolean(VoiceProtocol.ON);
                    audio.setSpeaker(speaker);
                    TimberLogger.i(TAG, "audio: %s", audio.describe());
                }
                case VoiceProtocol.MEETING_JOIN -> joinMeeting(b);
                case VoiceProtocol.MEETING_LEAVE -> {
                    if (meeting != null) meeting.leave(b.getBoolean(VoiceProtocol.END_FOR_ALL));
                }
                case VoiceProtocol.MEETING_HAND -> {
                    if (meeting != null) meeting.setHand(b.getBoolean(VoiceProtocol.ON));
                }
                case VoiceProtocol.MEETING_CONTROL -> control(b);
                case VoiceProtocol.MEETING_SECRET -> {
                    RemoteSecrets s = secrets;
                    if (s != null) s.answer(b);
                }
                case VoiceProtocol.MEETING_REKEY -> {
                    RemoteSecrets s = secrets;
                    if (s != null) s.offer(b);
                }
                default -> {
                    return false;
                }
            }
        } catch (RuntimeException e) {
            TimberLogger.e(TAG, "command %d: %s", msg.what, e);
        }
        return true;
    }

    private void register(Messenger replyTo) {
        client = replyTo;
        if (replyTo == null) return;
        try {
            // The main process holds the signalling: without it a call cannot go on.
            replyTo.getBinder().linkToDeath(() -> handler.post(() -> {
                if (client == replyTo) client = null;
                endAll("the app's main process went away");
            }), 0);
        } catch (RemoteException e) {
            client = null;
        }
    }

    private void startCall(Bundle b) {
        if (call != null || meeting != null) {
            TimberLogger.w(TAG, "a call or meeting is already running: refusing another");
            return;
        }
        String callId = b.getString(VoiceProtocol.CALL_ID);
        String peerJson = b.getString(VoiceProtocol.PEER_DELEGATION);
        CallSession.Params params = new CallSession.Params(callId, b.getString(VoiceProtocol.PEER_FID),
                b.getString(VoiceProtocol.RELAY_URL), b.getString(VoiceProtocol.RELAY_PUBKEY),
                b.getString(VoiceProtocol.RELAY_SID), b.getByteArray(VoiceProtocol.T_PRIV),
                Delegation.fromJson(b.getString(VoiceProtocol.MY_DELEGATION)),
                peerJson == null ? null : Delegation.fromJson(peerJson), b.getBoolean(VoiceProtocol.OWN_AEC));
        speaker = b.getBoolean(VoiceProtocol.ON);
        CallSession.Host host = new CallSession.Host() {
            @Override
            public void ring(CallSignal.Relay relay) {
                Bundle e = forCall(callId);
                e.putString(VoiceProtocol.RELAY_URL, relay.url());
                e.putString(VoiceProtocol.RELAY_PUBKEY, relay.pubkey());
                e.putString(VoiceProtocol.RELAY_SID, relay.sid());
                send(VoiceProtocol.EVT_CALL_RING, e);
            }

            @Override
            public void knock(String delegationJson) {
                Bundle e = forCall(callId);
                e.putString(VoiceProtocol.PEER_DELEGATION, delegationJson);
                send(VoiceProtocol.EVT_CALL_KNOCK, e);
            }

            @Override
            public void peerLeft() {
                send(VoiceProtocol.EVT_CALL_PEER_LEFT, forCall(callId));
            }
        };
        CallSession s = new CallSession(this, params, b.getString(VoiceProtocol.MY_FID), AudioIo.Backend.AAUDIO,
                b.getBoolean(VoiceProtocol.ALLOW_DIRECT), host, new CallSession.Listener() {
            @Override
            public void onState(CallSession.State state, String detail) {
                Bundle e = forCall(callId);
                e.putString(VoiceProtocol.STATE, state.name());
                e.putString(VoiceProtocol.DETAIL, detail);
                CallSession me = call;
                if (me != null) {
                    e.putBoolean(VoiceProtocol.PAYMENT_REQUIRED, me.paymentRequired());
                    e.putBoolean(VoiceProtocol.NETWORK_BLOCKED, me.networkBlocked());
                }
                send(VoiceProtocol.EVT_CALL_STATE, e);
                if (state == CallSession.State.ENDED || state == CallSession.State.FAILED) {
                    handler.post(() -> finishCall(callId));
                }
            }

            @Override
            public void onUnverified(String fid) {
                Bundle e = forCall(callId);
                e.putString(VoiceProtocol.FID, fid);
                send(VoiceProtocol.EVT_CALL_UNVERIFIED, e);
            }

            @Override
            public void beforeAudio() {
                audio.enter(speaker);
                TimberLogger.i(TAG, "audio: %s", audio.describe());
            }

            @Override
            public void onPathChanged() {
                handler.post(VoiceService.this::pushStatus);
            }

            @Override
            public void onPeerSilent(boolean silent) {
                Bundle e = forCall(callId);
                e.putBoolean(VoiceProtocol.ON, silent);
                send(VoiceProtocol.EVT_CALL_PEER_SILENT, e);
            }
        });
        call = s;
        status = timer.scheduleWithFixedDelay(() -> handler.post(this::pushStatus), STATUS_EVERY_MS, STATUS_EVERY_MS,
                TimeUnit.MILLISECONDS);
        if (b.getBoolean(VoiceProtocol.OUTGOING)) s.startOutgoing();
        else s.startIncoming(b.getByteArray(VoiceProtocol.SECRET));
    }

    private void endCall(String callId) {
        CallSession c = call;
        if (c == null || !c.callId().equals(callId)) return;
        c.end(); // reports ENDED, which finishes it here
    }

    /** The session is over: let the audio go. */
    private void finishCall(String callId) {
        CallSession c = call;
        if (c == null || !c.callId().equals(callId)) return;
        call = null;
        if (status != null) status.cancel(false);
        status = null;
        try {
            c.end(); // a failed call has not ended its worker yet; an ended one has, and refuses this
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // already ended
        }
        audio.leave();
    }

    private void pushStatus() {
        CallSession c = call;
        if (c == null) return;
        Bundle e = forCall(c.callId());
        e.putBoolean(VoiceProtocol.DIRECT, c.isDirect());
        e.putLong(VoiceProtocol.RTT_MS, c.rttMs());
        e.putBoolean(VoiceProtocol.MUTED, c.isMuted());
        send(VoiceProtocol.EVT_CALL_STATUS, e);
    }

    private void joinMeeting(Bundle b) {
        if (call != null || meeting != null) {
            TimberLogger.w(TAG, "a call or meeting is already running: refusing another");
            return;
        }
        MeetingBoard.Meeting m = GSON.fromJson(b.getString(VoiceProtocol.MEETING), MeetingBoard.Meeting.class);
        RemoteSecrets s = new RemoteSecrets(m.meetingId);
        ArrayList<Bundle> given = b.getParcelableArrayList(VoiceProtocol.SECRETS);
        if (given != null) for (Bundle g : given) s.answer(g);
        secrets = s;
        speaker = b.getBoolean(VoiceProtocol.ON);
        String id = m.meetingId;
        MeetingSession session = new MeetingSession(this, m, b.getString(VoiceProtocol.MY_FID),
                b.getByteArray(VoiceProtocol.T_PRIV), Delegation.fromJson(b.getString(VoiceProtocol.MY_DELEGATION)),
                AudioIo.Backend.AAUDIO, b.getBoolean(VoiceProtocol.CREATING), b.getBoolean(VoiceProtocol.OWN_AEC), s,
                new MeetingSession.Listener() {
            @Override
            public void onJoined() {
                // With the view: the host announces the meeting with the relay's key from it.
                handler.post(() -> {
                    MeetingSession me = meeting;
                    if (me == null || !me.meetingId().equals(id)) return;
                    Bundle e = forMeeting(id);
                    e.putString(VoiceProtocol.VIEW, MeetingView.of(me).toJson());
                    send(VoiceProtocol.EVT_MEETING_JOINED, e);
                });
            }

            @Override
            public void onChanged() {
                handler.post(VoiceService.this::pushView);
            }

            @Override
            public void beforeAudio() {
                audio.enter(speaker);
            }

            @Override
            public void onRekeyed(com.fc.fc_ajdk.call.MeetingSignal update) {
                Bundle e = forMeeting(id);
                e.putString(VoiceProtocol.SIGNAL, update.toJson());
                send(VoiceProtocol.EVT_MEETING_REKEYED, e);
            }

            @Override
            public void onEnded(MeetingSession.End why, String detail) {
                Bundle e = forMeeting(id);
                e.putString(VoiceProtocol.WHY, why.name());
                e.putString(VoiceProtocol.DETAIL, detail);
                send(VoiceProtocol.EVT_MEETING_ENDED, e);
                handler.post(() -> finishMeeting(id));
            }
        });
        meeting = session;
        session.start();
    }

    private void finishMeeting(String meetingId) {
        MeetingSession m = meeting;
        if (m == null || !m.meetingId().equals(meetingId)) return;
        meeting = null;
        RemoteSecrets s = secrets;
        secrets = null;
        if (s != null) s.erase();
        audio.leave();
    }

    private void pushView() {
        MeetingSession m = meeting;
        if (m == null) return;
        Bundle e = forMeeting(m.meetingId());
        e.putString(VoiceProtocol.VIEW, MeetingView.of(m).toJson());
        send(VoiceProtocol.EVT_MEETING_VIEW, e);
    }

    private void control(Bundle b) {
        MeetingSession m = meeting;
        long requestId = b.getLong(VoiceProtocol.REQUEST_ID);
        if (m == null) return;
        Object target = b.containsKey(VoiceProtocol.TARGET_SSRC) ? (Object) (int) b.getLong(VoiceProtocol.TARGET_SSRC)
                : b.getString(VoiceProtocol.TARGET_FID);
        m.control(b.getString(VoiceProtocol.ACTION), target, error -> {
            Bundle e = forMeeting(m.meetingId());
            e.putLong(VoiceProtocol.REQUEST_ID, requestId);
            e.putString(VoiceProtocol.ERROR, error);
            send(VoiceProtocol.EVT_CONTROL_RESULT, e);
        });
    }

    /** End whatever runs, when the main process that signals it is gone. */
    private void endAll(String why) {
        CallSession c = call;
        if (c != null) {
            TimberLogger.w(TAG, "ending the call: %s", why);
            c.end();
        }
        MeetingSession m = meeting;
        if (m != null) {
            TimberLogger.w(TAG, "leaving the meeting: %s", why);
            m.leave(false);
        }
    }

    // ===== Events =====

    private static Bundle forCall(String callId) {
        Bundle e = new Bundle();
        e.putString(VoiceProtocol.CALL_ID, callId);
        return e;
    }

    private static Bundle forMeeting(String meetingId) {
        Bundle e = new Bundle();
        e.putString(VoiceProtocol.MEETING_ID, meetingId);
        return e;
    }

    /** From any thread. Dropped if the main process is not listening. */
    private void send(int what, Bundle data) {
        Messenger c = client;
        if (c == null) return;
        Message m = Message.obtain(null, what);
        m.setData(data);
        try {
            c.send(m);
        } catch (RemoteException e) {
            TimberLogger.w(TAG, "event %d not delivered: %s", what, e.getMessage());
        }
    }

    /**
     * A meeting's call secrets as the main process gives them (§11.3): those
     * at hand when the join starts, and the rest on request. A miss asks, once
     * until answered, and returns null; the session asks again on its retries.
     */
    private final class RemoteSecrets implements MeetingSession.Secrets {
        private final String meetingId;
        private final Map<String, byte[]> known = new ConcurrentHashMap<>();
        private final Set<String> asked = ConcurrentHashMap.newKeySet();
        private volatile MeetingSession.Rekey offered;

        RemoteSecrets(String meetingId) {
            this.meetingId = meetingId;
        }

        @Override
        public byte[] secret(long symkeyVersion, String nonceHex, String authPubHex) {
            String key = VoiceProtocol.keySet(symkeyVersion, nonceHex, authPubHex);
            byte[] s = known.get(key);
            if (s != null) return s.clone();
            if (asked.add(key)) {
                Bundle e = forMeeting(meetingId);
                e.putLong(VoiceProtocol.SYMKEY_VERSION, symkeyVersion);
                e.putString(VoiceProtocol.NONCE, nonceHex);
                e.putString(VoiceProtocol.AUTH_PUB, authPubHex);
                send(VoiceProtocol.EVT_SECRET_REQUEST, e);
            }
            return null;
        }

        @Override
        public MeetingSession.Rekey nextRekey(long symkeyVersion) {
            Bundle e = forMeeting(meetingId);
            e.putLong(VoiceProtocol.SYMKEY_VERSION, symkeyVersion);
            send(VoiceProtocol.EVT_REKEY_CHECK, e);
            MeetingSession.Rekey r = offered;
            if (r == null || r.symkeyVersion() <= symkeyVersion) return null;
            offered = null;
            return r;
        }

        /** A secret from the main process, or its answer that there is none yet. */
        void answer(Bundle b) {
            String key = VoiceProtocol.keySet(b.getLong(VoiceProtocol.SYMKEY_VERSION), b.getString(VoiceProtocol.NONCE),
                    b.getString(VoiceProtocol.AUTH_PUB));
            byte[] s = b.getByteArray(VoiceProtocol.SECRET);
            if (s != null) known.put(key, s);
            asked.remove(key); // ask again on the next retry if there was none
        }

        void offer(Bundle b) {
            byte[] nonce = b.getByteArray(VoiceProtocol.NONCE), authPub = b.getByteArray(VoiceProtocol.AUTH_PUB);
            if (nonce == null || authPub == null) return;
            offered = new MeetingSession.Rekey(b.getLong(VoiceProtocol.SYMKEY_VERSION), nonce, authPub);
        }

        void erase() {
            for (byte[] s : known.values()) Arrays.fill(s, (byte) 0);
            known.clear();
        }
    }
}
