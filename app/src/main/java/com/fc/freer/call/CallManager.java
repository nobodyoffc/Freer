package com.fc.freer.call;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.call.engine.AudioIo;
import com.fc.freer.im.ImManager;
import com.fc.freer.manager.FidManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * The device's one call at a time (VOICE_SPEC §10, §11.1): places and answers
 * calls, rings for incoming ones, and gives {@link CallActivity} one state to
 * show. The {@link CallSession} itself, with the audio, runs in the
 * {@code :voice} process (§11.3), driven through {@link VoiceClient}: this
 * keeps the signalling and hands it only the call's keys. Everything it tells
 * the UI happens on the main thread.
 */
public final class CallManager implements CallSignaller.Listener {

    private static final String TAG = "CallManager";
    static final String CHANNEL_INCOMING = "calls_incoming";
    static final int NOTIFY_INCOMING = 7101;
    private static final String PREFS = "calls";
    private static final String PREF_RELAY = "relay_override";
    private static final String PREF_ALWAYS_RELAY = "always_relay";
    private static final String PREF_AVAILABLE = "available_for_calls";
    private static final String PREF_OWN_AEC = "own_echo_canceller";

    public enum Phase { IDLE, CALLING, RINGING_IN, CONNECTING, CONNECTED, ENDED }

    public interface Listener {
        void onCallChanged();
    }

    private static CallManager instance;

    public static synchronized CallManager getInstance(Context context) {
        if (instance == null) instance = new CallManager(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final VoiceClient voice;
    private CallSignaller signaller;
    private String myFid;

    // State for the UI, touched on the main thread only.
    private Phase phase = Phase.IDLE;
    private CallSignaller.Call call;
    /** The call running in the voice process, or null. */
    private String sessionCallId;
    private boolean muted, direct, paymentRequired, networkBlocked;
    private long rttMs = -1;
    private long connectedAtMs = -1;
    private String endReason;
    private boolean failed;
    private String unverifiedFid;
    /** Well into the call and not a frame from the peer: likely its network cannot reach the relay. */
    private boolean peerSilent;
    /** The callee's CALL service for the call I placed, for its price and a top-up. */
    private com.fc.freer.im.handler.P2pHandler.CallRelay placedVia;
    /** Set when my call failed for my balance at the callee's relay: the relay to top up. */
    private String topUpRelay;
    private volatile boolean speaker; // read on the session thread when call audio starts
    private Ringtone ringtone;
    private CallRingWatch ringWatch;

    private CallManager(Context context) {
        this.context = context;
        this.voice = VoiceClient.get(context);
        voice.setCallEvents(new VoiceClient.Events() {
            @Override
            public void onEvent(int what, android.os.Bundle data) {
                onVoiceEvent(what, data);
            }

            @Override
            public void onVoiceDied() {
                if (sessionCallId == null) return;
                // A crash in the audio code ends the call, as a failed relay would; the app goes on.
                android.os.Bundle e = new android.os.Bundle();
                e.putString(VoiceProtocol.CALL_ID, sessionCallId);
                e.putString(VoiceProtocol.STATE, CallSession.State.FAILED.name());
                e.putString(VoiceProtocol.DETAIL, context.getString(R.string.call_voice_died));
                onVoiceEvent(VoiceProtocol.EVT_CALL_STATE, e);
            }
        });
    }

    /** Called by ImManager when an identity's signaller starts. */
    public void attach(String myFid, CallSignaller signaller) {
        main.post(() -> {
            this.myFid = myFid;
            this.signaller = signaller;
            signaller.setListener(this);
            voice.bind(); // the voice process is up before a call needs it
            // An identity has just loaded, so the app is in the foreground: the only time Android allows this.
            if (availableForCalls()) CallAvailabilityService.start(context);
        });
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ===== State for the UI =====

    public Phase phase() {
        return phase;
    }

    public String peerFid() {
        return call == null ? null : call.peerFid;
    }

    public String relayHost() {
        if (call == null || call.relayUrl == null) return null;
        String u = call.relayUrl.replaceFirst("^[a-z]+://", "");
        int slash = u.indexOf('/');
        return slash > 0 ? u.substring(0, slash) : u;
    }

    /**
     * What a relayed minute of this call costs me, in FCH, from the CALL
     * service's price: the caller pays both sides' traffic (§7.5), about
     * 0.4 MB in and out per side. Null if I am not paying or the price is unknown.
     */
    public Double callerCostPerMinute() {
        com.fc.fc_ajdk.data.feipData.Service s = placedVia == null ? null : placedVia.service();
        if (s == null || call == null || !call.outgoing) return null;
        double in = price(s.getPricePerKBIn(), s.getPricePerKB()), out = price(s.getPricePerKBOut(), s.getPricePerKB());
        return in < 0 || out < 0 ? null : 2 * 400 * (in + out);
    }

    private static double price(String specific, String general) {
        String v = specific != null && !specific.isEmpty() ? specific : general;
        try {
            return v == null || v.isEmpty() ? -1 : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The relay to top up after a call failed for my balance there, or null. */
    public String topUpRelay() {
        return topUpRelay;
    }

    /**
     * Pay the callee's CALL service from my FID, as FAPI's auto-recharge does
     * for any service, so the next call can open (§7.5). The user confirms
     * first. Runs off the main thread; {@code done} gets null on success, or
     * why not.
     */
    public void topUp(String relayUrl, java.util.function.Consumer<String> done) {
        Executors.newSingleThreadExecutor().execute(() -> {
            String error;
            try {
                var r = com.fc.freer.utils.ApiCenter.getInstance().topUpService(context, relayUrl);
                error = r == null ? "the relay could not be reached" : r.isSuccess() ? null : r.getMessage();
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
            String result = error;
            main.post(() -> done.accept(result));
        });
    }

    public long connectedAtMs() {
        return connectedAtMs;
    }

    public String endReason() {
        return endReason;
    }

    /** The call failed rather than ended: the screen stays up so the reason can be read. */
    public boolean endedWithFailure() {
        return failed;
    }

    public boolean peerSilent() {
        return peerSilent;
    }

    public String unverifiedFid() {
        return unverifiedFid;
    }

    public boolean isMuted() {
        return sessionCallId != null && muted;
    }

    public boolean isSpeaker() {
        return speaker;
    }

    public long rttMs() {
        return sessionCallId == null ? -1 : rttMs;
    }

    /**
     * Debug builds only: a relay to call through instead of the callee's
     * home.CALL, and to answer on besides my own, for testing before a CALL
     * service is on chain. A release build always uses the callee's.
     */
    public String relayOverride() {
        return com.fc.freer.BuildConfig.DEBUG ? prefs().getString(PREF_RELAY, "") : "";
    }

    public void setRelayOverride(String url) {
        prefs().edit().putString(PREF_RELAY, url == null ? "" : url.trim()).apply();
    }

    /** Never try a direct path, which would show each side the other's IP (Decision 8). Off by default. */
    /**
     * Cancel echo with the app's own canceller (AEC3) instead of the phone's
     * voice processing (§11.1): for phones that cut one side off when both
     * talk on the loudspeaker. Off by default. Read when a call or meeting starts.
     */
    public boolean ownEchoCanceller() {
        return prefs().getBoolean(PREF_OWN_AEC, false);
    }

    public void setOwnEchoCanceller(boolean on) {
        prefs().edit().putBoolean(PREF_OWN_AEC, on).apply();
    }

    public boolean alwaysRelay() {
        return prefs().getBoolean(PREF_ALWAYS_RELAY, false);
    }

    public void setAlwaysRelay(boolean on) {
        prefs().edit().putBoolean(PREF_ALWAYS_RELAY, on).apply();
    }

    /** Ring in the background, through {@link CallAvailabilityService} (§6.3). Off by default. */
    public boolean availableForCalls() {
        return prefs().getBoolean(PREF_AVAILABLE, false);
    }

    public void setAvailableForCalls(boolean on) {
        prefs().edit().putBoolean(PREF_AVAILABLE, on).apply();
        if (on) CallAvailabilityService.start(context);
        else CallAvailabilityService.stop(context);
        // Behind, the DOCKs are checked only as often as the process is kept alive to.
        com.fc.freer.im.ImManager im = com.fc.freer.manager.FidManager.getInstance().getImManager();
        if (im != null) im.applyDockIntervals();
    }

    /** Audio is on a direct path rather than the relay. */
    public boolean isDirect() {
        return sessionCallId != null && direct;
    }

    // ===== Actions =====

    /** Ring {@code peerFid}. Resolving the relay touches the network, so it runs off the main thread. */
    public void placeCall(String peerFid) {
        if (signaller == null || phase != Phase.IDLE && phase != Phase.ENDED) return;
        reset();
        phase = Phase.CALLING;
        notifyUi();
        Executors.newSingleThreadExecutor().execute(() -> {
            // The callee's CALL service, and no other (§6.2): no home.CALL means not callable.
            com.fc.freer.im.handler.P2pHandler.CallRelay via = null;
            String override = relayOverride();
            if (override.isEmpty()) {
                ImManager im = FidManager.getInstance().getImManager();
                via = im == null ? null : im.resolveCallRelay(peerFid);
            }
            String relayUrl = !override.isEmpty() ? override : via == null ? null : via.url();
            com.fc.freer.im.handler.P2pHandler.CallRelay resolved = via;
            main.post(() -> {
                if (phase != Phase.CALLING || call != null) return; // hung up while we looked
                if (relayUrl == null || relayUrl.isEmpty()) {
                    finish(context.getString(R.string.call_end_no_relay));
                    return;
                }
                placedVia = resolved;
                try {
                    // Nothing is sent yet: the session reaches the relay, then rings.
                    call = signaller.prepare(peerFid, relayUrl);
                } catch (IllegalStateException e) {
                    finish(context.getString(R.string.call_end_busy_here));
                    return;
                }
                startSession(call, true, null);
                CallService.start(context, false);
            });
        });
    }

    public void answer() {
        if (phase != Phase.RINGING_IN || call == null) return;
        stopRinging();
        if (signaller.accept(call.callId, null) == null) return;
        phase = Phase.CONNECTING;
        startSession(call, false, secretOf(call));
        CallService.start(context, false);
        notifyUi();
    }

    public void decline() {
        if (phase == Phase.RINGING_IN && call != null) signaller.reject(call.callId);
    }

    /** Cancel an unanswered call, or hang up an answered one. */
    public void hangup() {
        if (phase == Phase.CALLING && call == null) { // still looking for a relay
            finish(context.getString(R.string.call_end_ended));
            return;
        }
        if (call == null || signaller == null) return;
        switch (phase) {
            case CALLING -> signaller.cancel(call.callId);
            case RINGING_IN -> signaller.reject(call.callId);
            case CONNECTING, CONNECTED -> signaller.hangup(call.callId);
            default -> { }
        }
    }

    public void setMuted(boolean muted) {
        if (sessionCallId != null) {
            this.muted = muted;
            android.os.Bundle b = new android.os.Bundle();
            b.putBoolean(VoiceProtocol.ON, muted);
            voice.send(VoiceProtocol.SET_MUTED, b);
        }
        notifyUi();
    }

    public void setSpeaker(boolean on) {
        speaker = on;
        if (sessionCallId != null) {
            android.os.Bundle b = new android.os.Bundle();
            b.putBoolean(VoiceProtocol.ON, on);
            voice.send(VoiceProtocol.SET_SPEAKER, b);
        }
        notifyUi();
    }

    // ===== Signalling events (from the signaller's thread) =====

    @Override
    public void onIncoming(CallSignaller.Call incoming) {
        main.post(() -> {
            if (phase != Phase.IDLE && phase != Phase.ENDED) return; // the signaller answers busy
            reset();
            call = incoming;
            phase = Phase.RINGING_IN;
            MeetingManager.getInstance(context).stopRinging(); // one ring at a time: the call's
            ring();
            watchRing(incoming);
            notifyUi();
        });
    }

    @Override
    public void onAnswered(CallSignaller.Call answered) {
        main.post(() -> {
            if (sessionCallId != null && call == answered) {
                phase = Phase.CONNECTING;
                android.os.Bundle b = new android.os.Bundle();
                b.putString(VoiceProtocol.CALL_ID, answered.callId);
                b.putByteArray(VoiceProtocol.SECRET, secretOf(answered));
                if (answered.peerDelegation() != null) {
                    b.putString(VoiceProtocol.PEER_DELEGATION, answered.peerDelegation().toJson());
                }
                voice.send(VoiceProtocol.CALL_ANSWERED, b);
                notifyUi();
            }
        });
    }

    @Override
    public void onEnded(CallSignaller.Call ended, CallSignaller.End reason) {
        TimberLogger.i(TAG, "call %s ended by signalling: %s", ended.callId, reason);
        main.post(() -> {
            if (call != ended || phase == Phase.ENDED) return; // already ended, and said why
            finish(describe(reason));
        });
    }

    // ===== Internals =====

    /** Direct paths only with contacts, and never with Always relay on (Decision 8). */
    private boolean allowDirect(String peerFid) {
        if (alwaysRelay()) return false;
        try {
            com.fc.freer.manager.ContactManager cm = com.fc.freer.manager.ContactManager.getInstance();
            return cm != null && cm.checkIfFidExisted(peerFid);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A copy of the call secret for the session, which erases it; null if the signaller has none. */
    private byte[] secretOf(CallSignaller.Call c) {
        byte[] s = signaller == null ? null : signaller.callSecret(c.callId);
        return s == null ? null : s.clone();
    }

    /**
     * Start the call's session in the voice process with what it needs and no
     * more (§11.3): the call's ids and relay, its transport key, the
     * delegations, and for the callee the call secret.
     */
    private void startSession(CallSignaller.Call c, boolean outgoing, byte[] secret) {
        android.os.Bundle b = new android.os.Bundle();
        b.putString(VoiceProtocol.CALL_ID, c.callId);
        b.putString(VoiceProtocol.PEER_FID, c.peerFid);
        b.putString(VoiceProtocol.MY_FID, myFid);
        b.putString(VoiceProtocol.RELAY_URL, c.relayUrl);
        b.putString(VoiceProtocol.RELAY_PUBKEY, c.relayPubkey);
        b.putString(VoiceProtocol.RELAY_SID, c.relaySid);
        byte[] tPriv = c.transportPriv();
        b.putByteArray(VoiceProtocol.T_PRIV, tPriv);
        b.putString(VoiceProtocol.MY_DELEGATION, c.myDelegation.toJson());
        if (c.peerDelegation() != null) b.putString(VoiceProtocol.PEER_DELEGATION, c.peerDelegation().toJson());
        if (secret != null) b.putByteArray(VoiceProtocol.SECRET, secret);
        b.putBoolean(VoiceProtocol.OUTGOING, outgoing);
        b.putBoolean(VoiceProtocol.ALLOW_DIRECT, allowDirect(c.peerFid));
        b.putBoolean(VoiceProtocol.OWN_AEC, ownEchoCanceller());
        b.putBoolean(VoiceProtocol.ON, speaker);
        sessionCallId = c.callId;
        voice.send(VoiceProtocol.CALL_START, b);
        // The Bundle carries copies across; ours go now.
        java.util.Arrays.fill(tPriv, (byte) 0);
        if (secret != null) java.util.Arrays.fill(secret, (byte) 0);
    }

    /** An event of the call running in the voice process, on the main thread. */
    private void onVoiceEvent(int what, android.os.Bundle e) {
        CallSignaller.Call c = call;
        String callId = e.getString(VoiceProtocol.CALL_ID);
        if (c == null || sessionCallId == null || !c.callId.equals(callId)) return;
        CallSignaller s = signaller;
        switch (what) {
            case VoiceProtocol.EVT_CALL_RING -> {
                if (s != null) s.ring(c.callId, new com.fc.fc_ajdk.call.CallSignal.Relay(
                        e.getString(VoiceProtocol.RELAY_URL), e.getString(VoiceProtocol.RELAY_PUBKEY),
                        e.getString(VoiceProtocol.RELAY_SID)), null);
            }
            case VoiceProtocol.EVT_CALL_KNOCK -> {
                String json = e.getString(VoiceProtocol.PEER_DELEGATION);
                if (s != null && json != null) {
                    // A knock is an answer (§6.2 step 3); checking it takes a signature verification.
                    try {
                        s.onKnock(c.callId, com.fc.fc_ajdk.call.Delegation.fromJson(json));
                    } catch (RuntimeException ignored) {
                        // not a delegation it can read: the ACCEPT may still come
                    }
                }
            }
            case VoiceProtocol.EVT_CALL_PEER_LEFT -> {
                if (s != null) s.peerLeft(c.callId);
            }
            case VoiceProtocol.EVT_CALL_UNVERIFIED -> {
                unverifiedFid = e.getString(VoiceProtocol.FID);
                notifyUi();
            }
            case VoiceProtocol.EVT_CALL_STATUS -> {
                boolean changed = direct != e.getBoolean(VoiceProtocol.DIRECT);
                direct = e.getBoolean(VoiceProtocol.DIRECT);
                rttMs = e.getLong(VoiceProtocol.RTT_MS, -1);
                muted = e.getBoolean(VoiceProtocol.MUTED);
                if (changed) notifyUi();
            }
            case VoiceProtocol.EVT_CALL_PEER_SILENT -> {
                peerSilent = e.getBoolean(VoiceProtocol.ON);
                notifyUi();
            }
            case VoiceProtocol.EVT_CALL_STATE -> {
                CallSession.State state = CallSession.State.valueOf(e.getString(VoiceProtocol.STATE));
                switch (state) {
                    case CONNECTED -> {
                        phase = Phase.CONNECTED;
                        connectedAtMs = System.currentTimeMillis();
                    }
                    case FAILED -> {
                        paymentRequired = e.getBoolean(VoiceProtocol.PAYMENT_REQUIRED);
                        networkBlocked = e.getBoolean(VoiceProtocol.NETWORK_BLOCKED);
                        if (paymentRequired) topUpRelay = c.relayUrl;
                        String detail = e.getString(VoiceProtocol.DETAIL);
                        // The media path failed: end the call for the peer too.
                        hangup();
                        failed = true; // before finish(), whose redraw decides whether the screen stays
                        finish(networkBlocked ? context.getString(R.string.call_end_network_blocked, relayHost())
                                : context.getString(R.string.call_end_failed, detail));
                    }
                    default -> { }
                }
                notifyUi();
            }
            default -> { }
        }
    }

    private void finish(String reason) {
        stopRinging();
        if (sessionCallId != null) {
            android.os.Bundle b = new android.os.Bundle();
            b.putString(VoiceProtocol.CALL_ID, sessionCallId);
            voice.send(VoiceProtocol.CALL_END, b);
        }
        sessionCallId = null;
        CallService.stop(context);
        phase = Phase.ENDED;
        endReason = reason;
        notifyUi();
    }

    private void reset() {
        call = null;
        sessionCallId = null;
        muted = false;
        direct = false;
        rttMs = -1;
        paymentRequired = false;
        networkBlocked = false;
        connectedAtMs = -1;
        endReason = null;
        failed = false;
        unverifiedFid = null;
        peerSilent = false;
        placedVia = null;
        topUpRelay = null;
    }

    private String describe(CallSignaller.End reason) {
        return switch (reason) {
            case DECLINED -> context.getString(R.string.call_record_declined);
            case BUSY -> context.getString(R.string.call_record_busy);
            case UNSUPPORTED -> context.getString(R.string.call_end_unsupported);
            case WRONG_RELAY -> context.getString(R.string.call_end_wrong_relay);
            case NO_ANSWER -> context.getString(R.string.call_record_no_answer);
            case MISSED -> context.getString(R.string.call_record_missed);
            case ANSWERED_ELSEWHERE -> context.getString(R.string.call_record_answered_elsewhere);
            case CANCELLED, HUNG_UP, LOCAL_HANGUP -> context.getString(R.string.call_end_ended);
        };
    }

    /** The incoming-call notification opens the call screen, over the lock screen if need be (§11.1). */
    private void ring() {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_INCOMING,
                context.getString(R.string.call_channel_incoming), NotificationManager.IMPORTANCE_HIGH));
        Intent open = new Intent(context, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        nm.notify(NOTIFY_INCOMING, new NotificationCompat.Builder(context, CHANNEL_INCOMING)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(context.getString(R.string.call_incoming_title))
                .setContentText(call.peerFid)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build());
        try {
            ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
            if (ringtone != null) ringtone.play();
        } catch (RuntimeException e) {
            TimberLogger.w(TAG, "no ringtone: %s", e.getMessage());
        }
        // If the app is in front, go straight to the call screen.
        try {
            context.startActivity(open);
        } catch (RuntimeException ignored) {
            // from the background the notification does it
        }
    }

    /** While ringing, watch the relay: another device may answer, or the caller leave (§6.3). */
    private void watchRing(CallSignaller.Call c) {
        if (ringWatch != null) ringWatch.stop();
        ringWatch = null;
        if (c.relayUrl == null || c.relayPubkey == null || c.relaySid == null) return;
        CallSignaller s = signaller;
        ringWatch = new CallRingWatch(new java.io.File(context.getCacheDir(), "call-ring/" + c.callId), c.callId,
                c.relayUrl, c.relayPubkey, c.relaySid, c.expiresMs, new CallRingWatch.Listener() {
                    @Override
                    public void answeredElsewhere() {
                        TimberLogger.i(TAG, "call %s answered on another device", c.callId);
                        s.settledElsewhere(c.callId, true);
                    }

                    @Override
                    public void callerGone() {
                        TimberLogger.i(TAG, "call %s: the caller left the relay", c.callId);
                        s.settledElsewhere(c.callId, false);
                    }
                });
        ringWatch.start();
    }

    private void stopRinging() {
        if (ringWatch != null) ringWatch.stop(); // before this device joins: its join is not another's
        ringWatch = null;
        if (ringtone != null) ringtone.stop();
        ringtone = null;
        context.getSystemService(NotificationManager.class).cancel(NOTIFY_INCOMING);
    }

    private void notifyUi() {
        for (Listener l : listeners) l.onCallChanged();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
