package com.fc.freer.call;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.fc.fc_ajdk.call.CallKeys;
import com.fc.fc_ajdk.call.CallSignal;
import com.fc.fc_ajdk.call.MeetingSignal;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;

import java.io.File;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The device's one meeting at a time (VOICE_SPEC §8, §10): starts and joins
 * meetings in Rooms and Teams, keeps the {@link MeetingBoard} the chat's
 * cards come from, and gives {@link MeetingActivity} one state to show. The
 * {@link MeetingSession}, with the audio, runs in the {@code :voice} process
 * (§11.3), driven through {@link VoiceClient}: this keeps the symkeys and the
 * identity's key, and hands it a join's transport key, its delegation and
 * call secrets. A meeting and a 1:1 call never
 * run at once. Everything it tells the UI happens on the main thread.
 */
public final class MeetingManager {

    private static final String TAG = "MeetingManager";
    /** A member's MEETING_END is checked with the relay after the relay would have closed an empty meeting (§7.6). */
    private static final long CONFIRM_END_AFTER_MS = 70_000;
    /** A new meeting rings only if it started this recently: a card that arrives late is not a call. */
    static final long RING_FRESH_MS = 120_000;
    /** A meeting rings this long, as a call does (§6.3). */
    static final long RING_FOR_MS = 45_000;
    static final int NOTIFY_MEETING = 7104;

    public enum Phase { IDLE, CONNECTING, IN_MEETING, ENDED }

    /** Delegations outlast any meeting, well inside the 24 h cap (§4.1). */
    static final long DELEGATION_SEC = 12 * 3600;

    /** Where the entity's symkeys come from: the identity's SymkeyStore, and the FIMP key request. */
    public interface Keys {
        List<byte[]> symkeys(String entityId, long version);

        long currentVersion(String entityId);

        /** Ask the members for a version this device lacks (§8, SYMKEY_IDENTITY_SPEC §3). */
        void request(String entityId, long version);
    }

    public interface Listener {
        void onMeetingChanged();
    }

    /** For the chat: a meeting card opened, changed or ended. Not called for what goes on inside a meeting. */
    public interface CardListener {
        void onCardsChanged();
    }

    /** What the manager needs from the identity's IM layer. */
    public interface Hooks {
        /** Post a CALL message into the Room or Team chat (§3.3). */
        void post(String entityType, String entityId, MeetingSignal signal);

        /** The relay a new meeting runs on: the entity's home.CALL, else my own (§8). Blocking. */
        String relayFor(String entityType, String entityId);

        /** The Room's or Team's name. May touch the network: never on the main thread. */
        String entityName(String entityType, String entityId);

        Keys keys();

        /** Keep this meeting's DOCKs fetched every few seconds while it runs; null, null when it ends. */
        void meetingDocks(String entityType, String entityId);

        /** Keep a chosen-people meeting's key as the symkey of {@code keyEntity}, encrypted like any (Decision 20). */
        void storeKey(String keyEntity, long version, byte[] key);

        void forgetKey(String keyEntity);

        /** A 1:1 CALL message to {@code fid} on every channel: an invitation or its MEETING_END. */
        void sendDirect(String fid, MeetingSignal signal);

        /** A chosen-people meeting's card, in this device's copy of the chat only. */
        void storeCard(String entityType, String entityId, String hostFid, MeetingSignal card);
    }

    private static MeetingManager instance;

    public static synchronized MeetingManager getInstance(Context context) {
        if (instance == null) instance = new MeetingManager(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<CardListener> cardListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "meetings");
        t.setDaemon(true);
        return t;
    });
    private final java.util.concurrent.ScheduledExecutorService later =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "meetings-later");
                t.setDaemon(true);
                return t;
            });

    private String myFid;
    private byte[] fidPriv;
    private Hooks hooks;
    private MeetingBoard board;

    // State for the UI, touched on the main thread only.
    private volatile Phase phase = Phase.IDLE; // read by the signaller's thread for busy
    private MeetingBoard.Meeting meeting;
    /** The meeting running in the voice process, or null. */
    private String sessionMeetingId;
    /** What it last said of itself. */
    private MeetingView view;
    /** Its call secrets, from this identity's symkeys: what the voice process asks for. */
    private MeetingSession.Secrets sessionSecrets;
    private final java.util.Map<Long, java.util.function.Consumer<String>> controls = new java.util.HashMap<>();
    private long nextControl = 1;
    private final VoiceClient voice;
    private boolean endForAllRequested;
    private String endReason;
    private volatile boolean speaker = true; // a meeting is usually on the speaker
    /** The meeting's Room or Team name, looked up once off the main thread. */
    private volatile String entityName;
    /** A new meeting ringing this device, not yet joined or declined; main thread only. */
    private MeetingBoard.Meeting ringing;
    private android.media.Ringtone ringtone;
    private final Runnable ringTimeout = this::stopRinging;

    private MeetingManager(Context context) {
        this.context = context;
        this.voice = VoiceClient.get(context);
        voice.setMeetingEvents(new VoiceClient.Events() {
            @Override
            public void onEvent(int what, android.os.Bundle data) {
                onVoiceEvent(what, data);
            }

            @Override
            public void onVoiceDied() {
                MeetingBoard.Meeting m = meeting;
                // A crash in the audio code ends the meeting here; the app goes on.
                if (sessionMeetingId != null && m != null) {
                    ended(m, MeetingSession.End.FAILED, context.getString(R.string.call_voice_died));
                }
            }
        });
    }

    /** Called by ImManager when an identity loads. */
    public void attach(String myFid, byte[] fidPriv, Hooks hooks) {
        main.post(() -> {
            if (sessionMeetingId != null && !myFid.equals(this.myFid)) leave();
            this.myFid = myFid;
            this.fidPriv = fidPriv.clone();
            this.hooks = hooks;
            SharedPreferences prefs = context.getSharedPreferences("meetings_" + myFid, Context.MODE_PRIVATE);
            this.board = new MeetingBoard(new MeetingBoard.Store() {
                @Override
                public String load() {
                    return prefs.getString("board", null);
                }

                @Override
                public void save(String json) {
                    prefs.edit().putString("board", json).apply();
                }
            });
            notifyUi();
        });
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public void addCardListener(CardListener l) {
        cardListeners.add(l);
    }

    public void removeCardListener(CardListener l) {
        cardListeners.remove(l);
    }

    // ===== State for the UI =====

    /** Null before an identity has loaded. */
    public MeetingBoard board() {
        return board;
    }

    public Phase phase() {
        return phase;
    }

    /** In a meeting, or joining one. */
    public boolean isActive() {
        return phase == Phase.CONNECTING || phase == Phase.IN_MEETING;
    }

    public MeetingBoard.Meeting meeting() {
        return meeting;
    }

    /** What the running meeting last said of itself; null before it has. */
    public MeetingView session() {
        return sessionMeetingId == null ? null : view;
    }

    public String myFid() {
        return myFid;
    }

    public String endReason() {
        return endReason;
    }

    /** The meeting ringing this device, or null. */
    public MeetingBoard.Meeting ringing() {
        return ringing;
    }

    public boolean isSpeaker() {
        return speaker;
    }

    /** The meeting's title, or its Room or Team's name. */
    public String title() {
        MeetingBoard.Meeting m = meeting;
        if (m == null) return null;
        if (m.title != null && !m.title.isEmpty()) return m.title;
        String name = entityName;
        return name != null ? name : m.entityId;
    }

    public String relayHost() {
        MeetingBoard.Meeting m = meeting;
        if (m == null || m.relay == null || m.relay.url() == null) return null;
        String u = m.relay.url().replaceFirst("^[a-z]+://", "");
        int slash = u.indexOf('/');
        return slash > 0 ? u.substring(0, slash) : u;
    }

    // ===== Actions =====

    /**
     * Start a meeting in a Room or Team (§8): the entity's newest symkey, a new
     * meeting id and nonce, {@code call.create} and join on the relay, then the
     * card in the chat.
     *
     * @param done gets null once connecting, or why it cannot start; on the main thread
     */
    public void start(String entityType, String entityId, String title, java.util.function.Consumer<String> done) {
        start(entityType, entityId, title, null, done);
    }

    /**
     * @param invitees null for everyone in the chat; otherwise only these
     *                 members, under a key of the meeting's own (Decision 20)
     */
    public void start(String entityType, String entityId, String title, List<String> invitees,
                      java.util.function.Consumer<String> done) {
        String busy = busyReason();
        if (busy != null || hooks == null) {
            done.accept(busy != null ? busy : context.getString(R.string.meeting_not_ready));
            return;
        }
        phase = Phase.CONNECTING;
        endReason = null;
        notifyUi();
        Hooks h = hooks;
        background.execute(() -> {
            String relayUrl = h.relayFor(entityType, entityId);
            Keys keys = h.keys();
            long version = keys.currentVersion(entityId);
            List<byte[]> held = version < 0 ? null : keys.symkeys(entityId, version);
            main.post(() -> {
                if (phase != Phase.CONNECTING || sessionMeetingId != null) return;
                if (relayUrl == null || relayUrl.isEmpty()) {
                    phase = Phase.IDLE;
                    notifyUi();
                    done.accept(context.getString(R.string.meeting_no_relay));
                    return;
                }
                if (invitees == null && (held == null || held.isEmpty())) {
                    phase = Phase.IDLE;
                    notifyUi();
                    done.accept(context.getString(R.string.meeting_no_key));
                    return;
                }
                SecureRandom rng = new SecureRandom();
                byte[] id = new byte[12], nonce = new byte[32];
                rng.nextBytes(id);
                rng.nextBytes(nonce);
                MeetingBoard.Meeting m = new MeetingBoard.Meeting();
                m.meetingId = MeetingSignal.ID_PREFIX + Hex.toHex(id);
                m.entityId = entityId;
                m.entityType = entityType;
                m.hostFid = myFid;
                m.relay = new CallSignal.Relay(relayUrl);
                m.title = title;
                m.started = System.currentTimeMillis();
                byte[] secret;
                long keyVersion;
                if (invitees == null) {
                    keyVersion = version;
                    secret = CallKeys.meetingSecret(held.get(0), nonce, entityId, version, m.meetingId);
                } else {
                    // Its own key, stored as the symkey of an entity named by the meeting (§4.2).
                    m.invited = true;
                    m.invitees = new java.util.ArrayList<>(invitees);
                    keyVersion = MeetingSignal.INVITED_VERSION;
                    byte[] key = new byte[32];
                    rng.nextBytes(key);
                    h.storeKey(m.meetingId, keyVersion, key);
                    secret = CallKeys.meetingSecret(key, nonce, m.meetingId, keyVersion, m.meetingId);
                    Arrays.fill(key, (byte) 0);
                }
                m.keys.add(new MeetingBoard.Keys(Hex.toHex(nonce), keyVersion,
                        Hex.toHex(CallKeys.authPub(CallKeys.authPriv(secret))), 0));
                Arrays.fill(secret, (byte) 0);
                run(m, true);
                done.accept(null);
            });
        });
    }

    /**
     * Join a meeting from its card.
     *
     * @param done gets null once connecting, or why not; on the main thread
     */
    public void join(String meetingId, java.util.function.Consumer<String> done) {
        MeetingBoard.Meeting m = board == null ? null : board.get(meetingId);
        if (m == null) {
            done.accept(context.getString(R.string.meeting_unknown));
            return;
        }
        if (isActive() && meeting != null && meeting.meetingId.equals(meetingId)) {
            done.accept(null); // already in it: just show it
            return;
        }
        if (m.ended) {
            done.accept(context.getString(R.string.meeting_is_over));
            return;
        }
        String busy = busyReason();
        if (busy != null) {
            done.accept(busy);
            return;
        }
        run(m, false);
        done.accept(null);
    }

    public void leave() {
        if (sessionMeetingId != null) {
            sendLeave(false);
        } else if (phase == Phase.CONNECTING) {
            // Still looking for the relay: nothing to leave yet.
            phase = Phase.IDLE;
            notifyUi();
        }
    }

    /** Host only: close the meeting for everyone, and say so in the chat (§8). */
    public void endForAll() {
        MeetingView v = session();
        if (v == null || !v.isHost()) return;
        endForAllRequested = true;
        sendLeave(true);
    }

    private void sendLeave(boolean endForAll) {
        android.os.Bundle b = new android.os.Bundle();
        b.putBoolean(VoiceProtocol.END_FOR_ALL, endForAll);
        voice.send(VoiceProtocol.MEETING_LEAVE, b);
    }

    public void setMuted(boolean muted) {
        if (sessionMeetingId != null) {
            android.os.Bundle b = new android.os.Bundle();
            b.putBoolean(VoiceProtocol.ON, muted);
            voice.send(VoiceProtocol.SET_MUTED, b);
            if (view != null) view.selfMuted = muted; // until the next view says so
        }
        notifyUi();
    }

    public void setHand(boolean raised) {
        if (sessionMeetingId != null) {
            android.os.Bundle b = new android.os.Bundle();
            b.putBoolean(VoiceProtocol.ON, raised);
            voice.send(VoiceProtocol.MEETING_HAND, b);
            if (view != null) view.handRaised = raised;
        }
        notifyUi();
    }

    public void setSpeaker(boolean on) {
        speaker = on;
        if (sessionMeetingId != null) {
            android.os.Bundle b = new android.os.Bundle();
            b.putBoolean(VoiceProtocol.ON, on);
            voice.send(VoiceProtocol.SET_SPEAKER, b);
        }
        notifyUi();
    }

    /**
     * A host control; {@code done} gets null or the relay's refusal, on the main thread.
     *
     * @param target a FID or an ssrc (Integer)
     */
    public void control(String action, Object target, java.util.function.Consumer<String> done) {
        if (sessionMeetingId == null) return;
        long id = nextControl++;
        controls.put(id, done);
        android.os.Bundle b = new android.os.Bundle();
        b.putLong(VoiceProtocol.REQUEST_ID, id);
        b.putString(VoiceProtocol.ACTION, action);
        if (target instanceof Integer ssrc) b.putLong(VoiceProtocol.TARGET_SSRC, Integer.toUnsignedLong(ssrc));
        else if (target != null) b.putString(VoiceProtocol.TARGET_FID, String.valueOf(target));
        voice.send(VoiceProtocol.MEETING_CONTROL, b);
    }

    // ===== From the chat (ImManager) =====

    /**
     * A meeting signal from a verified member of {@code entityId} (§3.3).
     *
     * @return what the board made of it: NEW means the message is a new card
     */
    public MeetingBoard.Result onChatSignal(String entityType, String entityId, String senderFid, MeetingSignal s) {
        MeetingBoard b = board;
        if (b == null || s == null) return MeetingBoard.Result.UNCHANGED;
        MeetingBoard.Result r = s.op == MeetingSignal.Op.MEETING_START
                ? b.onStart(entityId, entityType, senderFid, s)
                : b.onEnd(entityId, senderFid, s);
        if (r == MeetingBoard.Result.CONFIRM) confirmEndLater(s.meetingId);
        if (r != MeetingBoard.Result.UNCHANGED && r != MeetingBoard.Result.CONFIRM) main.post(this::notifyCards);
        if (r == MeetingBoard.Result.NEW && s.op == MeetingSignal.Op.MEETING_START && s.keyEpochOrZero() == 0) {
            main.post(() -> maybeRing(s.meetingId, senderFid));
        } else if (r == MeetingBoard.Result.UPDATED && s.op == MeetingSignal.Op.MEETING_END) {
            main.post(() -> stopRingingFor(s.meetingId));
        }
        return r;
    }

    /**
     * Another member says a meeting ended: believe it only once its relay
     * no longer knows it, after it would have closed an empty meeting.
     */
    private void confirmEndLater(String meetingId) {
        later.schedule(() -> {
            MeetingBoard b = board;
            MeetingBoard.Meeting m = b == null ? null : b.get(meetingId);
            if (m == null || m.ended || m.relay == null) return;
            Boolean open = probeOpen(m);
            if (Boolean.FALSE.equals(open)) {
                b.markEnded(meetingId, 0);
                main.post(() -> {
                    stopRingingFor(meetingId);
                    notifyCards();
                });
            }
        }, CONFIRM_END_AFTER_MS, TimeUnit.MILLISECONDS);
    }

    /** {@code call.info} on a throwaway key (§7.2). @return open, or null if the relay could not be asked */
    private Boolean probeOpen(MeetingBoard.Meeting m) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        File dir = new File(context.getCacheDir(), "meeting-probe/" + m.meetingId);
        try (CallRelayLink l = new CallRelayLink(dir, key, m.meetingId, null, new CallRelayLink.Events() {
            @Override
            public void onFrame(byte[] datagram, boolean direct) {}

            @Override
            public void onAttestation(byte[] attestation) {}

            @Override
            public void onNotice(Map<String, Object> notice) {}
        })) {
            l.connect(m.relay.url(), m.relay.pubkey(), m.relay.sid(), KnownRelays.of(context));
            Map<String, Object> info = l.info();
            return Boolean.TRUE.equals(info.get("open"));
        } catch (Exception e) {
            TimberLogger.w(TAG, "call.info for %s: %s", m.meetingId, e.getMessage());
            return null;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    // ===== Internals =====

    /** Why a meeting cannot start or be joined now, or null. */
    private String busyReason() {
        if (isActive()) return context.getString(R.string.meeting_already_in_one);
        CallManager.Phase call = CallManager.getInstance(context).phase();
        if (call != CallManager.Phase.IDLE && call != CallManager.Phase.ENDED) {
            return context.getString(R.string.meeting_in_a_call);
        }
        return null;
    }

    private void run(MeetingBoard.Meeting m, boolean creating) {
        meeting = m;
        entityName = null;
        Hooks names = hooks;
        if (names != null) {
            background.execute(() -> {
                String n = names.entityName(m.entityType, m.entityId);
                main.post(() -> {
                    if (meeting == m) {
                        entityName = n;
                        notifyUi();
                    }
                });
            });
        }
        phase = Phase.CONNECTING;
        stopRinging(); // after the phase: a ring answered turns into the meeting, not into nothing
        endReason = null;
        endForAllRequested = false;
        Hooks h = hooks;
        // The join's transport key and its delegation are made here, where the identity's key is (§11.3).
        byte[] tPriv = new byte[32];
        new SecureRandom().nextBytes(tPriv);
        com.fc.fc_ajdk.call.Delegation delegation = com.fc.fc_ajdk.call.Delegation.sign(fidPriv, m.meetingId,
                com.fc.fc_ajdk.core.crypto.KeyTools.prikeyToPubkey(tPriv),
                System.currentTimeMillis() / 1000 + DELEGATION_SEC);
        // The secrets at hand now, so the join needs no round trip; the rest on request.
        MeetingSession.Secrets secrets = secretsFor(m, h.keys());
        java.util.ArrayList<android.os.Bundle> given = new java.util.ArrayList<>();
        for (MeetingBoard.Keys k : m.keys) {
            byte[] secret = secrets.secret(k.symkeyVersion(), k.nonce(), k.authPub());
            if (secret != null) given.add(secretBundle(m.meetingId, k.symkeyVersion(), k.nonce(), k.authPub(), secret));
        }
        android.os.Bundle b = new android.os.Bundle();
        b.putString(VoiceProtocol.MEETING, new com.google.gson.Gson().toJson(m));
        b.putString(VoiceProtocol.MY_FID, myFid);
        b.putByteArray(VoiceProtocol.T_PRIV, tPriv);
        b.putString(VoiceProtocol.MY_DELEGATION, delegation.toJson());
        b.putBoolean(VoiceProtocol.CREATING, creating);
        b.putBoolean(VoiceProtocol.OWN_AEC, CallManager.getInstance(context).ownEchoCanceller());
        b.putBoolean(VoiceProtocol.ON, speaker);
        b.putParcelableArrayList(VoiceProtocol.SECRETS, given);
        sessionMeetingId = m.meetingId;
        sessionSecrets = secrets;
        joinCreating = creating;
        view = null;
        voice.send(VoiceProtocol.MEETING_JOIN, b);
        // The Bundle carries copies across; ours go now.
        Arrays.fill(tPriv, (byte) 0);
        for (android.os.Bundle g : given) {
            byte[] secret = g.getByteArray(VoiceProtocol.SECRET);
            if (secret != null) Arrays.fill(secret, (byte) 0);
        }
        if (h != null) h.meetingDocks(m.entityType, m.entityId);
        CallService.start(context, true);
        notifyUi();
    }

    /** Whether the running join is the one that created its meeting. */
    private boolean joinCreating;

    private static android.os.Bundle secretBundle(String meetingId, long version, String nonce, String authPub,
                                                  byte[] secret) {
        android.os.Bundle g = new android.os.Bundle();
        g.putString(VoiceProtocol.MEETING_ID, meetingId);
        g.putLong(VoiceProtocol.SYMKEY_VERSION, version);
        g.putString(VoiceProtocol.NONCE, nonce);
        g.putString(VoiceProtocol.AUTH_PUB, authPub);
        if (secret != null) g.putByteArray(VoiceProtocol.SECRET, secret);
        return g;
    }

    /** An event of the meeting running in the voice process, on the main thread. */
    private void onVoiceEvent(int what, android.os.Bundle e) {
        MeetingBoard.Meeting m = meeting;
        String id = e.getString(VoiceProtocol.MEETING_ID);
        if (m == null || sessionMeetingId == null || !sessionMeetingId.equals(id)) return;
        switch (what) {
            case VoiceProtocol.EVT_MEETING_JOINED -> {
                MeetingView v = MeetingView.fromJson(e.getString(VoiceProtocol.VIEW));
                if (v != null) view = v;
                phase = Phase.IN_MEETING;
                if (joinCreating) announce(m);
                notifyUi();
            }
            case VoiceProtocol.EVT_MEETING_VIEW -> {
                MeetingView v = MeetingView.fromJson(e.getString(VoiceProtocol.VIEW));
                if (v != null) {
                    view = v;
                    notifyUi();
                }
            }
            case VoiceProtocol.EVT_MEETING_REKEYED -> {
                MeetingSignal update = MeetingSignal.fromJson(e.getString(VoiceProtocol.SIGNAL));
                Hooks h = hooks;
                if (update == null || h == null) return;
                // Late joiners need the new keys (§3.3); the board takes them as any member's post.
                MeetingBoard b = board;
                if (b != null) b.onStart(m.entityId, m.entityType, myFid, update);
                background.execute(() -> h.post(m.entityType, m.entityId, update));
            }
            case VoiceProtocol.EVT_MEETING_ENDED -> {
                MeetingSession.End why;
                try {
                    why = MeetingSession.End.valueOf(e.getString(VoiceProtocol.WHY));
                } catch (RuntimeException ex) {
                    why = MeetingSession.End.FAILED;
                }
                ended(m, why, e.getString(VoiceProtocol.DETAIL));
            }
            case VoiceProtocol.EVT_SECRET_REQUEST -> {
                MeetingSession.Secrets secrets = sessionSecrets;
                if (secrets == null) return;
                long version = e.getLong(VoiceProtocol.SYMKEY_VERSION);
                String nonce = e.getString(VoiceProtocol.NONCE), authPub = e.getString(VoiceProtocol.AUTH_PUB);
                // The symkey store may touch disk: off the main thread.
                background.execute(() -> {
                    byte[] secret = secrets.secret(version, nonce, authPub);
                    main.post(() -> {
                        if (!m.meetingId.equals(sessionMeetingId)) return;
                        voice.send(VoiceProtocol.MEETING_SECRET, secretBundle(m.meetingId, version, nonce, authPub, secret));
                        if (secret != null) Arrays.fill(secret, (byte) 0);
                    });
                });
            }
            case VoiceProtocol.EVT_REKEY_CHECK -> {
                MeetingSession.Secrets secrets = sessionSecrets;
                if (secrets == null) return;
                long version = e.getLong(VoiceProtocol.SYMKEY_VERSION);
                background.execute(() -> {
                    MeetingSession.Rekey r = secrets.nextRekey(version);
                    if (r == null) return;
                    main.post(() -> {
                        if (!m.meetingId.equals(sessionMeetingId)) return;
                        android.os.Bundle b = new android.os.Bundle();
                        b.putString(VoiceProtocol.MEETING_ID, m.meetingId);
                        b.putLong(VoiceProtocol.SYMKEY_VERSION, r.symkeyVersion());
                        b.putByteArray(VoiceProtocol.NONCE, r.nonce());
                        b.putByteArray(VoiceProtocol.AUTH_PUB, r.authPub());
                        voice.send(VoiceProtocol.MEETING_REKEY, b);
                    });
                });
            }
            case VoiceProtocol.EVT_CONTROL_RESULT -> {
                java.util.function.Consumer<String> done = controls.remove(e.getLong(VoiceProtocol.REQUEST_ID));
                if (done != null) done.accept(e.getString(VoiceProtocol.ERROR));
            }
            default -> { }
        }
    }

    /** The card, once the meeting is open on the relay: with its key and service id, to spare joiners discovery. */
    private void announce(MeetingBoard.Meeting m) {
        MeetingBoard.Keys k = m.newestKeys();
        MeetingView s = view;
        CallSignal.Relay relay = s != null && s.relayPubkey() != null
                ? new CallSignal.Relay(m.relay.url(), s.relayPubkey(), s.relaySid()) : m.relay;
        m.relay = relay;
        if (m.invited) {
            // Only the chosen: each gets the key in an invitation sealed to them alone (Decision 20).
            MeetingBoard b = board;
            if (b != null) b.putHosted(m);
            sendInvites(m, m.invitees);
            MeetingSignal inv = invitation(m);
            if (inv != null) hooks.storeCard(m.entityType, m.entityId, myFid, inv.withoutKey());
            notifyCards();
            return;
        }
        MeetingSignal start = MeetingSignal.start(m.meetingId, relay, Hex.fromHex(k.nonce()), k.symkeyVersion(),
                Hex.fromHex(k.authPub()), k.keyEpoch(), m.title, m.started);
        MeetingBoard b = board;
        if (b != null) b.onStart(m.entityId, m.entityType, myFid, start);
        hooks.post(m.entityType, m.entityId, start);
        notifyCards();
    }

    private void ended(MeetingBoard.Meeting m, MeetingSession.End why, String detail) {
        if (meeting != m) return;
        TimberLogger.i(TAG, "meeting %s over: %s %s", m.meetingId, why, detail == null ? "" : detail);
        MeetingView v = view;
        long duration = v != null && v.joinedAtMs() > 0 ? System.currentTimeMillis() - v.joinedAtMs() : 0;
        MeetingBoard b = board;
        if (why == MeetingSession.End.ENDED_BY_HOST || why == MeetingSession.End.GONE) {
            if (b != null) b.markEnded(m.meetingId, duration);
            // The host who closed it says so in the chat; members learn it from there (§8).
            if (endForAllRequested && hooks != null) {
                if (m.invited) {
                    MeetingSignal end = MeetingSignal.end(m.meetingId, duration);
                    end.entityId = m.entityId;
                    end.entityType = m.entityType;
                    for (String fid : m.invitees) hooks.sendDirect(fid, end);
                } else {
                    hooks.post(m.entityType, m.entityId, MeetingSignal.end(m.meetingId, duration));
                }
            }
            if (m.invited && hooks != null) hooks.forgetKey(m.meetingId); // over: its key has no further use
            notifyCards();
        }
        sessionMeetingId = null;
        sessionSecrets = null;
        view = null;
        controls.clear();
        if (hooks != null) hooks.meetingDocks(null, null);
        CallService.stop(context);
        phase = Phase.ENDED;
        endReason = switch (why) {
            case LEFT -> context.getString(R.string.meeting_left);
            case ENDED_BY_HOST -> context.getString(R.string.meeting_is_over);
            case GONE -> context.getString(R.string.meeting_is_over);
            case KICKED -> context.getString(R.string.meeting_removed);
            case NO_KEY -> context.getString(R.string.meeting_no_key_asked);
            case BALANCE -> context.getString(R.string.meeting_balance);
            case FAILED -> context.getString(R.string.meeting_failed, detail == null ? "" : detail);
        };
        notifyUi();
    }

    /**
     * A meeting's call secrets from the identity's symkeys (§4.2), and, as
     * host, the key set of an owner's rotation (§4.5): what the session gets
     * instead of the symkeys themselves.
     */
    static MeetingSession.Secrets secretsFor(MeetingBoard.Meeting m, Keys keys) {
        java.util.Set<Long> asked = java.util.concurrent.ConcurrentHashMap.newKeySet();
        return new MeetingSession.Secrets() {
            @Override
            public byte[] secret(long version, String nonceHex, String authPubHex) {
                byte[] s = MeetingKeys.secret(keys.symkeys(m.keyEntity(), version), Hex.fromHex(nonceHex),
                        m.keyEntity(), version, m.meetingId, authPubHex);
                // A chat's key can be asked for; a chosen-people meeting's comes only with its invitation.
                if (s == null && !m.invited && asked.add(version)) keys.request(m.entityId, version);
                return s;
            }

            @Override
            public MeetingSession.Rekey nextRekey(long symkeyVersion) {
                if (m.invited) return null;
                long current = keys.currentVersion(m.entityId);
                if (current <= symkeyVersion) return null;
                List<byte[]> held = keys.symkeys(m.entityId, current);
                if (held == null || held.isEmpty()) return null;
                byte[] nonce = new byte[32];
                new SecureRandom().nextBytes(nonce);
                byte[] secret = CallKeys.meetingSecret(held.get(0), nonce, m.entityId, current, m.meetingId);
                byte[] authPub = CallKeys.authPub(CallKeys.authPriv(secret));
                Arrays.fill(secret, (byte) 0);
                return new MeetingSession.Rekey(current, nonce, authPub);
            }
        };
    }

    /** Host of a chosen-people meeting: invite more members to it (Decision 20). */
    public void invite(List<String> fids) {
        MeetingBoard.Meeting m = meeting;
        MeetingView s = session();
        if (m == null || !m.invited || s == null || !s.isHost() || fids == null || fids.isEmpty()) return;
        List<String> fresh = new java.util.ArrayList<>();
        for (String f : fids) if (!m.invitees.contains(f)) fresh.add(f);
        m.invitees.addAll(fresh);
        MeetingBoard b = board;
        if (b != null) b.putHosted(m);
        sendInvites(m, fresh);
    }

    /** The invitation, with the key read back from where it is kept; null if this device lacks it. */
    private MeetingSignal invitation(MeetingBoard.Meeting m) {
        MeetingBoard.Keys k = m.newestKeys();
        List<byte[]> key = hooks.keys().symkeys(m.meetingId, MeetingSignal.INVITED_VERSION);
        if (k == null || key == null || key.isEmpty()) return null;
        return MeetingSignal.invite(m.meetingId, m.entityId, m.entityType, m.relay, Hex.fromHex(k.nonce()),
                Hex.fromHex(k.authPub()), key.get(0), m.title, m.started);
    }

    private void sendInvites(MeetingBoard.Meeting m, List<String> fids) {
        Hooks h = hooks;
        if (h == null || fids.isEmpty()) return;
        background.execute(() -> {
            MeetingSignal inv = invitation(m);
            if (inv == null) return;
            for (String fid : fids) {
                if (!fid.equals(myFid)) h.sendDirect(fid, inv);
            }
        });
    }

    /**
     * A 1:1 meeting signal from a member of its entity (the caller checked):
     * an invitation, whose key the caller has stored, or its end.
     */
    public MeetingBoard.Result onDirectSignal(String senderFid, MeetingSignal s) {
        MeetingBoard b = board;
        if (b == null || s == null) return MeetingBoard.Result.UNCHANGED;
        MeetingBoard.Result r = s.op == MeetingSignal.Op.MEETING_INVITE ? b.onInvite(senderFid, s)
                : b.onEnd(s.entityId, senderFid, s);
        if (s.op == MeetingSignal.Op.MEETING_END && r == MeetingBoard.Result.UPDATED && hooks != null) {
            hooks.forgetKey(s.meetingId);
        }
        if (r == MeetingBoard.Result.NEW || r == MeetingBoard.Result.UPDATED) main.post(this::notifyCards);
        if (r == MeetingBoard.Result.NEW && s.op == MeetingSignal.Op.MEETING_INVITE) {
            main.post(() -> maybeRing(s.meetingId, senderFid));
        } else if (r == MeetingBoard.Result.UPDATED && s.op == MeetingSignal.Op.MEETING_END) {
            main.post(() -> stopRingingFor(s.meetingId));
        }
        return r;
    }

    // ===== Ringing =====

    /**
     * A new meeting in one of my chats rings like a call: someone else's, just
     * started, while this device is in no call or meeting. One at a time.
     */
    private void maybeRing(String meetingId, String senderFid) {
        MeetingBoard b = board;
        MeetingBoard.Meeting m = b == null ? null : b.get(meetingId);
        if (m == null || m.ended || senderFid == null || senderFid.equals(myFid)) return;
        if (ringing != null || busyReason() != null) return;
        // The host's clock, not ours: a little skew either way still counts as just started.
        if (Math.abs(System.currentTimeMillis() - m.started) > RING_FRESH_MS) return;
        ringing = m;
        TimberLogger.i(TAG, "meeting %s rings", meetingId);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CallManager.CHANNEL_INCOMING,
                context.getString(R.string.call_channel_incoming), NotificationManager.IMPORTANCE_HIGH));
        Intent open = new Intent(context, MeetingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(context, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        nm.notify(NOTIFY_MEETING, new NotificationCompat.Builder(context, CallManager.CHANNEL_INCOMING)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(context.getString(R.string.meeting_ringing_title))
                .setContentText(ringText(m))
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
        main.postDelayed(ringTimeout, RING_FOR_MS);
        // If the app is in front, go straight to the meeting screen.
        try {
            context.startActivity(open);
        } catch (RuntimeException ignored) {
            // from the background the notification does it
        }
        notifyUi();
    }

    /** "Alice started a meeting", for the ring. */
    public String ringText(MeetingBoard.Meeting m) {
        String who = hostName(m.hostFid);
        return m.title == null || m.title.isEmpty() ? context.getString(R.string.meeting_ringing_text, who)
                : context.getString(R.string.meeting_ringing_text_titled, who, m.title);
    }

    private static String hostName(String fid) {
        com.fc.freer.im.ImManager im = com.fc.freer.manager.FidManager.getInstance().getImManager();
        var p = im == null ? null : im.getTalkPartner(fid);
        String cid = p == null ? null : p.getCid();
        return cid != null && !cid.isEmpty() ? cid : com.fc.fc_ajdk.utils.StringUtils.omitMiddle(fid, 20);
    }

    /** Join the ringing meeting; {@code done} as for {@link #join}. */
    public void answerRing(java.util.function.Consumer<String> done) {
        MeetingBoard.Meeting m = ringing;
        if (m == null) {
            done.accept(context.getString(R.string.meeting_unknown));
            return;
        }
        // Joining stops the ring once it is connecting, so the screen never sees neither.
        join(m.meetingId, error -> {
            if (error != null) stopRinging();
            done.accept(error);
        });
    }

    public void declineRing() {
        stopRinging();
    }

    /** Stop ringing, whatever rang: a call rang, the meeting was joined, declined or ended, or 45 s passed. */
    public void stopRinging() {
        main.removeCallbacks(ringTimeout);
        if (ringtone != null) ringtone.stop();
        ringtone = null;
        context.getSystemService(NotificationManager.class).cancel(NOTIFY_MEETING);
        if (ringing != null) {
            ringing = null;
            notifyUi();
        }
    }

    private void stopRingingFor(String meetingId) {
        if (ringing != null && ringing.meetingId.equals(meetingId)) stopRinging();
    }

    private void notifyUi() {
        for (Listener l : listeners) l.onMeetingChanged();
    }

    private void notifyCards() {
        for (CardListener l : cardListeners) l.onCardsChanged();
    }
}
