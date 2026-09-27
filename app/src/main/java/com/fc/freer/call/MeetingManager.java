package com.fc.freer.call;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.fc.fc_ajdk.call.CallKeys;
import com.fc.fc_ajdk.call.CallSignal;
import com.fc.fc_ajdk.call.MeetingSignal;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.call.engine.AudioIo;

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
 * cards come from, runs the {@link MeetingSession}, the foreground
 * {@link CallService} and the audio routing, and gives
 * {@link MeetingActivity} one state to show. A meeting and a 1:1 call never
 * run at once. Everything it tells the UI happens on the main thread.
 */
public final class MeetingManager {

    private static final String TAG = "MeetingManager";
    /** A member's MEETING_END is checked with the relay after the relay would have closed an empty meeting (§7.6). */
    private static final long CONFIRM_END_AFTER_MS = 70_000;

    public enum Phase { IDLE, CONNECTING, IN_MEETING, ENDED }

    public interface Listener {
        void onMeetingChanged();
    }

    /** What the manager needs from the identity's IM layer. */
    public interface Hooks {
        /** Post a CALL message into the Room or Team chat (§3.3). */
        void post(String entityType, String entityId, MeetingSignal signal);

        /** The relay a new meeting runs on: the entity's home.CALL, else my own (§8). Blocking. */
        String relayFor(String entityType, String entityId);

        String entityName(String entityId);

        MeetingSession.Keys keys();
    }

    private static MeetingManager instance;

    public static synchronized MeetingManager getInstance(Context context) {
        if (instance == null) instance = new MeetingManager(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final CallAudio audio;
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
    private MeetingSession session;
    private boolean endForAllRequested;
    private String endReason;
    private volatile boolean speaker = true; // a meeting is usually on the speaker

    private MeetingManager(Context context) {
        this.context = context;
        this.audio = new CallAudio(context);
    }

    /** Called by ImManager when an identity loads. */
    public void attach(String myFid, byte[] fidPriv, Hooks hooks) {
        main.post(() -> {
            if (session != null && !myFid.equals(this.myFid)) leave();
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

    public MeetingSession session() {
        return session;
    }

    public String myFid() {
        return myFid;
    }

    public String endReason() {
        return endReason;
    }

    public boolean isSpeaker() {
        return speaker;
    }

    /** The meeting's title, or its Room or Team's name. */
    public String title() {
        MeetingBoard.Meeting m = meeting;
        if (m == null) return null;
        if (m.title != null && !m.title.isEmpty()) return m.title;
        String name = hooks == null ? null : hooks.entityName(m.entityId);
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
            MeetingSession.Keys keys = h.keys();
            long version = keys.currentVersion(entityId);
            List<byte[]> held = version < 0 ? null : keys.symkeys(entityId, version);
            main.post(() -> {
                if (phase != Phase.CONNECTING || session != null) return;
                if (relayUrl == null || relayUrl.isEmpty()) {
                    phase = Phase.IDLE;
                    notifyUi();
                    done.accept(context.getString(R.string.meeting_no_relay));
                    return;
                }
                if (held == null || held.isEmpty()) {
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
                byte[] secret = CallKeys.meetingSecret(held.get(0), nonce, entityId, version, m.meetingId);
                m.keys.add(new MeetingBoard.Keys(Hex.toHex(nonce), version,
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
        if (session != null) {
            session.leave(false);
        } else if (phase == Phase.CONNECTING) {
            // Still looking for the relay: nothing to leave yet.
            phase = Phase.IDLE;
            notifyUi();
        }
    }

    /** Host only: close the meeting for everyone, and say so in the chat (§8). */
    public void endForAll() {
        if (session == null || !session.isHost()) return;
        endForAllRequested = true;
        session.leave(true);
    }

    public void setMuted(boolean muted) {
        if (session != null) session.setMuted(muted);
        notifyUi();
    }

    public void setHand(boolean raised) {
        if (session != null) session.setHand(raised);
        notifyUi();
    }

    public void setSpeaker(boolean on) {
        speaker = on;
        audio.setSpeaker(on);
        notifyUi();
    }

    /** A host control; {@code done} gets null or the relay's refusal, on the main thread. */
    public void control(String action, Object target, java.util.function.Consumer<String> done) {
        MeetingSession s = session;
        if (s == null) return;
        s.control(action, target, error -> main.post(() -> done.accept(error)));
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
        if (r != MeetingBoard.Result.UNCHANGED) main.post(this::notifyUi);
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
                main.post(this::notifyUi);
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
        phase = Phase.CONNECTING;
        endReason = null;
        endForAllRequested = false;
        Hooks h = hooks;
        MeetingSession s = new MeetingSession(context, m, myFid, fidPriv, AudioIo.Backend.AAUDIO, creating, h.keys(),
                new MeetingSession.Listener() {
                    @Override
                    public void onJoined() {
                        main.post(() -> {
                            if (session == null || !session.meetingId().equals(m.meetingId)) return;
                            phase = Phase.IN_MEETING;
                            if (creating) announce(m);
                            notifyUi();
                        });
                    }

                    @Override
                    public void onChanged() {
                        main.post(MeetingManager.this::notifyUi);
                    }

                    @Override
                    public void beforeAudio() {
                        audio.enter(speaker);
                    }

                    @Override
                    public void onRekeyed(MeetingSignal update) {
                        // Late joiners need the new keys (§3.3); the board takes them as any member's post.
                        MeetingBoard b = board;
                        if (b != null) b.onStart(m.entityId, m.entityType, myFid, update);
                        h.post(m.entityType, m.entityId, update);
                    }

                    @Override
                    public void onEnded(MeetingSession.End why, String detail) {
                        main.post(() -> ended(m, why, detail));
                    }
                });
        session = s;
        s.start();
        CallService.start(context);
        notifyUi();
    }

    /** The card, once the meeting is open on the relay: with its key and service id, to spare joiners discovery. */
    private void announce(MeetingBoard.Meeting m) {
        MeetingBoard.Keys k = m.newestKeys();
        MeetingSession s = session;
        CallSignal.Relay relay = s != null && s.relayPubkey() != null
                ? new CallSignal.Relay(m.relay.url(), s.relayPubkey(), s.relaySid()) : m.relay;
        m.relay = relay;
        MeetingSignal start = MeetingSignal.start(m.meetingId, relay, Hex.fromHex(k.nonce()), k.symkeyVersion(),
                Hex.fromHex(k.authPub()), k.keyEpoch(), m.title, m.started);
        MeetingBoard b = board;
        if (b != null) b.onStart(m.entityId, m.entityType, myFid, start);
        hooks.post(m.entityType, m.entityId, start);
    }

    private void ended(MeetingBoard.Meeting m, MeetingSession.End why, String detail) {
        if (meeting != m) return;
        TimberLogger.i(TAG, "meeting %s over: %s %s", m.meetingId, why, detail == null ? "" : detail);
        long duration = session != null && session.joinedAtMs() > 0
                ? System.currentTimeMillis() - session.joinedAtMs() : 0;
        MeetingBoard b = board;
        if (why == MeetingSession.End.ENDED_BY_HOST || why == MeetingSession.End.GONE) {
            if (b != null) b.markEnded(m.meetingId, duration);
            // The host who closed it says so in the chat; members learn it from there (§8).
            if (endForAllRequested && hooks != null) hooks.post(m.entityType, m.entityId, MeetingSignal.end(m.meetingId, duration));
        }
        session = null;
        audio.leave();
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

    private void notifyUi() {
        for (Listener l : listeners) l.onMeetingChanged();
    }
}
