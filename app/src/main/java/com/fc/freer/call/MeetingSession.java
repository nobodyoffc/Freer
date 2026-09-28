package com.fc.freer.call;

import android.content.Context;
import android.os.SystemClock;

import com.fc.fc_ajdk.call.CallKeys;
import com.fc.fc_ajdk.call.CallSignal;
import com.fc.fc_ajdk.call.Delegation;
import com.fc.fc_ajdk.call.MeetingSignal;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.call.engine.AudioIo;
import com.fc.freer.call.engine.CaptureEngine;
import com.fc.freer.call.engine.PlayoutEngine;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One join of a meeting in a Room or Team (VOICE_SPEC §8): the relay link
 * under a throwaway transport key, the call secret from the entity's symkey,
 * {@link CallMedia} for everyone in the roster whose delegation verifies, and
 * the capture and playout engines. Network steps run on the session's own
 * thread; the listener hears from it and must not block.
 * <p>
 * A rekey (§4.5) comes as a relay notice: the session derives the new secret
 * from the named symkey version, asking the members for it if it lacks it,
 * and proves it within the relay's deadline. As host it also follows the
 * owner's rotations, rekeying when a newer symkey version arrives.
 */
public final class MeetingSession {

    private static final String TAG = "MeetingSession";
    private static final long TICK_MS = 200;
    private static final long STATS_EVERY_MS = 10_000;
    private static final long HOST_CHECK_MS = 5_000;
    private static final long REKEY_RETRY_MS = 2_000;
    /** A host retries a rekey the relay refused only after this long. */
    private static final long REKEY_BACKOFF_MS = 30_000;
    /** Delegations outlast any meeting, well inside the 24 h cap (§4.1). */
    private static final long DELEGATION_SEC = 12 * 3600;
    /** A stream counts as speaking while frames this loud arrived this recently. */
    private static final long SPEAKING_WITHIN_MS = 400;
    private static final int SPEAKING_LEVEL = 55;

    /** Why a session is over. */
    public enum End { LEFT, ENDED_BY_HOST, KICKED, NO_KEY, BALANCE, GONE, FAILED }

    /** Where the entity's symkeys come from: the identity's SymkeyStore, and the FIMP key request. */
    public interface Keys {
        List<byte[]> symkeys(String entityId, long version);

        long currentVersion(String entityId);

        /** Ask the members for a version this device lacks (§8, SYMKEY_IDENTITY_SPEC §3). */
        void request(String entityId, long version);
    }

    public interface Listener {
        /** Joined, audio flowing. */
        void onJoined();

        /** The roster, a mute, a hand or who is speaking changed. */
        void onChanged();

        /** On the session's thread, just before the audio streams open. */
        void beforeAudio();

        /** As host, a rekey took: post the new keys for late joiners (§3.3). */
        void onRekeyed(MeetingSignal update);

        void onEnded(End why, String detail);
    }

    /** One roster entry, as the UI shows it. */
    public record Participant(String fid, int ssrc, boolean me, boolean host, String mutedByHost, boolean hand,
                              boolean verified) {}

    private final Context context;
    private final MeetingBoard.Meeting meeting;
    private final String myFid;
    private final Keys keys;
    private final Listener listener;
    private final boolean creating;
    private final byte[] tPriv = new byte[32];
    private final Delegation myDelegation;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "meeting-session");
        t.setUncaughtExceptionHandler((th, e) -> TimberLogger.e(TAG, "meeting-session: %s", e));
        return t;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "meeting-session-timer");
        t.setDaemon(true);
        return t;
    });
    private final CaptureEngine capture;
    private final long startedMs = System.currentTimeMillis();

    private volatile CallRelayLink link;
    private volatile CallMedia media;
    private volatile PlayoutEngine playout;
    private volatile List<Participant> participants = List.of();
    private volatile String hostFid;
    private volatile int routeId;
    private volatile boolean selfMuted, handRaised;
    /** "host" or "locked" while the host has muted this device (§7.2). */
    private volatile String mutedByHost;
    private volatile long symkeyVersion;
    private volatile long rekeyTriedVersion = -1, rekeyTriedAtMs;
    private volatile boolean over;
    private volatile long joinedAtMs = -1;
    private final Set<Integer> unverified = new HashSet<>();
    private final Set<Integer> paused = new HashSet<>();
    private final Map<Integer, String> fidOfSsrc = new HashMap<>();
    /** Per sender ssrc: frames that arrived from the relay, and those that opened. For the log. */
    private final Map<Integer, long[]> arrivals = new java.util.concurrent.ConcurrentHashMap<>();
    private int ticks;

    /**
     * @param creating this device starts the meeting: {@code call.create} before
     *                 joining, with the newest key set of {@code meeting}
     */
    public MeetingSession(Context context, MeetingBoard.Meeting meeting, String myFid, byte[] fidPriv,
                          AudioIo.Backend backend, boolean creating, Keys keys, Listener listener) {
        this.context = context.getApplicationContext();
        this.meeting = meeting;
        this.myFid = myFid;
        this.keys = keys;
        this.listener = listener;
        this.creating = creating;
        // Until a roster says otherwise: the relay names the host in each (§7.2).
        this.hostFid = creating ? myFid : meeting.hostFid;
        new SecureRandom().nextBytes(tPriv);
        this.myDelegation = Delegation.sign(fidPriv, meeting.meetingId, KeyTools.prikeyToPubkey(tPriv),
                System.currentTimeMillis() / 1000 + DELEGATION_SEC);
        // Relayed audio, and so every meeting, uses 40 ms frames (§9.1).
        this.capture = new CaptureEngine(new CaptureEngine.Settings(FrameLength.LONG_MS, CallSession.BITRATE, true,
                CallSession.EXPECTED_LOSS, backend), f -> {
            CallMedia m = media;
            CallRelayLink l = link;
            if (m != null && l != null) l.sendFrame(m.seal(f, System.currentTimeMillis()));
        });
    }

    public String meetingId() {
        return meeting.meetingId;
    }

    public void start() {
        worker.execute(() -> {
            try {
                openLink();
                join();
            } catch (Ended e) {
                finish(e.why, e.getMessage());
            } catch (Exception e) {
                finish(End.FAILED, e.getMessage());
            }
        });
    }

    /** Leave; as host, {@code endForAll} closes the meeting for everyone first. */
    public void leave(boolean endForAll) {
        submit(() -> {
            if (endForAll) {
                try {
                    link.control("end", null);
                } catch (IOException | RuntimeException e) {
                    TimberLogger.w(TAG, "end: %s", e.getMessage());
                }
            }
            finish(endForAll ? End.ENDED_BY_HOST : End.LEFT, null);
        });
    }

    public void setMuted(boolean muted) {
        selfMuted = muted;
        applyMute();
        // Unmuting after the host's plain mute asks the relay to let our frames through (§7.2).
        if (!muted && "host".equals(mutedByHost)) {
            submit(() -> {
                try {
                    link.control("unmute", capture.ssrc());
                } catch (IOException e) {
                    TimberLogger.w(TAG, "unmute: %s", e.getMessage());
                }
            });
        }
    }

    public void setHand(boolean raised) {
        handRaised = raised;
        submit(() -> {
            try {
                link.hand(raised);
            } catch (IOException e) {
                TimberLogger.w(TAG, "hand: %s", e.getMessage());
            }
        });
    }

    /**
     * A host control (§7.2).
     *
     * @param target a FID or an ssrc (Integer)
     * @param done   gets null, or why the relay refused; on the session's thread
     */
    public void control(String action, Object target, java.util.function.Consumer<String> done) {
        submit(() -> {
            String error = null;
            try {
                link.control(action, target);
            } catch (IOException e) {
                error = e.getMessage();
            }
            done.accept(error);
        });
    }

    // ===== State for the UI =====

    public List<Participant> participants() {
        return participants;
    }

    public boolean isHost() {
        return myFid.equals(hostFid);
    }

    public String hostFid() {
        return hostFid;
    }

    public boolean isMuted() {
        return selfMuted || mutedByHost != null;
    }

    public boolean isSelfMuted() {
        return selfMuted;
    }

    /** "host", "locked" or null. */
    public String mutedByHost() {
        return mutedByHost;
    }

    public boolean handRaised() {
        return handRaised;
    }

    public long joinedAtMs() {
        return joinedAtMs;
    }

    public int mySsrc() {
        return capture.ssrc();
    }

    /** ssrcs heard just now, this device's own included. */
    public Set<Integer> speaking() {
        Set<Integer> out = new HashSet<>();
        PlayoutEngine p = playout;
        long now = SystemClock.elapsedRealtime();
        if (p != null) {
            for (PlayoutEngine.Stream s : p.streams().values()) {
                if (now - s.buffer.lastArrivalMs() < SPEAKING_WITHIN_MS && s.level() < SPEAKING_LEVEL) out.add(s.ssrc);
            }
        }
        if (!isMuted() && capture.level() < SPEAKING_LEVEL) out.add(capture.ssrc());
        return out;
    }

    /** Streams whose audio is on hold for late attestations (Decision 15). */
    public synchronized Set<Integer> pausedSsrcs() {
        return new HashSet<>(paused);
    }

    /** FIDs whose audio could not be verified and is silenced for this join (§5.1). */
    public synchronized List<String> unverifiedFids() {
        List<String> out = new ArrayList<>();
        for (int ssrc : unverified) {
            String fid = fidOfSsrc.get(ssrc);
            if (fid != null) out.add(fid);
        }
        return out;
    }

    /** The relay's key, hex, once connected: for the card (§3.3), so joiners skip discovery. */
    public String relayPubkey() {
        CallRelayLink l = link;
        return l == null ? null : l.relayPubkey();
    }

    public String relaySid() {
        CallRelayLink l = link;
        return l == null ? null : l.relaySid();
    }

    public long rttMs() {
        CallRelayLink l = link;
        return l == null ? -1 : l.rttMs();
    }

    // ===== Internals =====

    /** Refusals that end the session with a reason the UI can say. */
    private static final class Ended extends Exception {
        final End why;

        Ended(End why, String detail) {
            super(detail);
            this.why = why;
        }
    }

    private void openLink() throws Exception {
        CallSignal.Relay relay = meeting.relay;
        File dir = new File(context.getCacheDir(), "meeting/" + meeting.meetingId);
        link = new CallRelayLink(dir, tPriv, meeting.meetingId, myDelegation, new CallRelayLink.Events() {
            @Override
            public void onFrame(byte[] datagram, boolean direct) {
                CallMedia m = media;
                PlayoutEngine p = playout;
                if (m == null || p == null) return;
                var h = com.fc.fc_ajdk.call.MediaFrame.Header.parse(datagram);
                long[] count = h == null ? null : arrivals.computeIfAbsent(h.ssrc(), k -> new long[2]);
                if (count != null) count[0]++;
                var f = m.open(datagram, System.currentTimeMillis());
                if (f == null) return;
                if (count != null) count[1]++;
                p.onFrame(f, SystemClock.elapsedRealtime());
            }

            @Override
            public void onAttestation(byte[] attestation) {
                CallMedia m = media;
                if (m != null) m.onAttestation(attestation);
            }

            @Override
            public void onNotice(Map<String, Object> notice) {
                if (!meeting.meetingId.equals(notice.get("meetingId"))) return;
                onRelayNotice(notice);
            }
        });
        step("connecting to " + relay.url());
        String how = link.connect(relay.url(), relay.pubkey(), relay.sid(), KnownRelays.of(context));
        step("connected to the relay " + how);
    }

    /**
     * Create (as its starter) and join, with the newest key set this device
     * can derive; an older one if the relay refuses it, as when a rekey's
     * update has not reached this device yet (§3.3).
     */
    private void join() throws Exception {
        List<MeetingBoard.Keys> sets = new ArrayList<>(meeting.keys);
        Exception last = null;
        boolean anyKey = false;
        for (MeetingBoard.Keys k : sets) {
            byte[] secret = MeetingKeys.secret(keys.symkeys(meeting.entityId, k.symkeyVersion()),
                    Hex.fromHex(k.nonce()), meeting.entityId, k.symkeyVersion(), meeting.meetingId, k.authPub());
            if (secret == null) continue;
            anyKey = true;
            byte[] authPriv = CallKeys.authPriv(secret);
            try {
                if (creating) link.createMeeting(CallKeys.authPub(authPriv));
                Map<String, Object> joined = link.join(capture.ssrc(), authPriv, false);
                routeId = (int) ((Number) joined.get("routeId")).longValue();
                symkeyVersion = k.symkeyVersion();
                int epoch = joined.get("keyEpoch") instanceof Number n ? n.intValue() : 0;
                step("joined at key epoch " + epoch);
                startMedia(secret, epoch);
                onRoster(joined);
                return;
            } catch (CallRelayLink.Refused e) {
                last = e;
                if (e.code == 404) throw new Ended(End.GONE, "the meeting is over");
                if (e.code == 403) throw new Ended(End.KICKED, e.getMessage());
                if (e.code == 402) throw new Ended(End.BALANCE, e.getMessage());
                if (e.code != 401) throw e; // 401: that key is no longer the meeting's; try an older one
            } finally {
                Arrays.fill(secret, (byte) 0);
            }
        }
        if (!anyKey) {
            MeetingBoard.Keys newest = meeting.newestKeys();
            if (newest != null) keys.request(meeting.entityId, newest.symkeyVersion());
            throw new Ended(End.NO_KEY, "no key for symkey version " + (newest == null ? "?" : newest.symkeyVersion()));
        }
        throw last != null ? last : new IOException("could not join");
    }

    private void startMedia(byte[] secret, int epoch) {
        CallMedia m = new CallMedia(meeting.meetingId, secret, myFid, capture.ssrc(), tPriv);
        if (epoch != CallMedia.KEY_EPOCH) m.rekey(secret, epoch, System.currentTimeMillis());
        m.setRouteId(routeId);
        PlayoutEngine p = new PlayoutEngine(AudioIo.Backend.AAUDIO);
        m.setListener(new CallMedia.Listener() {
            @Override
            public void onUnverified(String fid, int ssrc) {
                step("audio claimed to be " + fid + "'s could not be verified; silenced");
                p.silence(ssrc);
                synchronized (MeetingSession.this) {
                    unverified.add(ssrc);
                    paused.remove(ssrc);
                }
                listener.onChanged();
            }

            @Override
            public void onPaused(String fid, int ssrc, boolean on) {
                step((on ? "paused " : "resumed ") + fid + "'s audio");
                synchronized (MeetingSession.this) {
                    if (on) paused.add(ssrc);
                    else paused.remove(ssrc);
                }
                listener.onChanged();
            }
        });
        playout = p;
        media = m;
        listener.beforeAudio();
        p.start();
        capture.start();
        applyMute();
        joinedAtMs = System.currentTimeMillis();
        timer.scheduleWithFixedDelay(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        step("media started");
        listener.onJoined();
    }

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            CallMedia m = media;
            CallRelayLink l = link;
            if (m == null || l == null) return;
            for (byte[] a : m.takeAttestations(now)) l.sendAttestation(a);
            m.tick(now);
            if (++ticks % (HOST_CHECK_MS / TICK_MS) == 0 && isHost()) followSymkey(now);
            if (ticks % (STATS_EVERY_MS / TICK_MS) == 0) logStats();
            if (ticks % 5 == 0) listener.onChanged(); // who is speaking
        } catch (RuntimeException e) {
            TimberLogger.w(TAG, "tick: %s", e.getMessage());
        }
    }

    private void onRelayNotice(Map<String, Object> notice) {
        String type = String.valueOf(notice.get("type"));
        switch (type) {
            case "roster" -> onRoster(notice);
            case "muted" -> {
                mutedByHost = Boolean.TRUE.equals(notice.get("locked")) ? "locked" : "host";
                step("muted by the host (" + mutedByHost + ")");
                applyMute();
                listener.onChanged();
            }
            case "rekey" -> timer.execute(() -> rekeyAttempt(notice, System.currentTimeMillis(), false));
            case "kicked" -> {
                // "rekey": this device could not prove the new key in time (§4.5); "balance": the host's ran out.
                String reason = String.valueOf(notice.get("reason"));
                End why = "rekey".equals(reason) ? End.NO_KEY : "balance".equals(reason) ? End.BALANCE : End.KICKED;
                submit(() -> finish(why, reason));
            }
            case "ended" -> submit(() -> finish(End.ENDED_BY_HOST, null));
            case "balance" -> step("the relay says the host's balance is low");
            default -> { }
        }
    }

    /**
     * Hear every roster entry whose delegation verifies for this meeting and
     * names its FID (§5.1 step 1); a relay that lies in the roster gets no
     * stream played.
     */
    @SuppressWarnings("unchecked")
    private void onRoster(Map<String, Object> roster) {
        CallMedia m = media;
        if (roster.get("host") instanceof String h) hostFid = h;
        List<Participant> list = new ArrayList<>();
        Set<Integer> present = new HashSet<>();
        long nowSec = System.currentTimeMillis() / 1000;
        String myMute = null;
        for (Map<String, Object> e : CallRelayLink.roster(roster)) {
            if (!(e.get("fid") instanceof String fid) || !(e.get("ssrc") instanceof Number n)) continue;
            int ssrc = (int) n.longValue();
            boolean me = ssrc == capture.ssrc();
            String muted = e.get("muted") instanceof String s ? s : null;
            if (me) {
                myMute = muted;
                list.add(new Participant(fid, ssrc, true, fid.equals(hostFid), muted,
                        Boolean.TRUE.equals(e.get("hand")), true));
                continue;
            }
            boolean verified = false;
            if (e.get("delegation") instanceof String json) {
                try {
                    Delegation d = Delegation.fromJson(json);
                    verified = d != null && fid.equals(d.fid)
                            && d.verify(meeting.meetingId, nowSec) == Delegation.Check.OK;
                    if (verified && m != null) m.addPeer(fid, ssrc, d.tPubBytes());
                } catch (RuntimeException ignored) {
                    // unreadable: not heard
                }
            }
            present.add(ssrc);
            synchronized (this) {
                fidOfSsrc.put(ssrc, fid);
            }
            list.add(new Participant(fid, ssrc, false, fid.equals(hostFid), muted, Boolean.TRUE.equals(e.get("hand")),
                    verified));
        }
        synchronized (this) {
            for (Integer gone : new HashSet<>(fidOfSsrc.keySet())) {
                if (!present.contains(gone)) {
                    fidOfSsrc.remove(gone);
                    arrivals.remove(gone);
                    if (m != null) m.removePeer(gone);
                }
            }
        }
        // The host lifted its mute, or never set one.
        if (myMute == null && mutedByHost != null) {
            mutedByHost = null;
            applyMute();
        } else if (myMute != null) {
            mutedByHost = myMute;
            applyMute();
        }
        participants = List.copyOf(list);
        listener.onChanged();
    }

    /**
     * A rekey notice (§4.5): derive the new secret, asking the members once for
     * a version this device lacks, and prove it before the relay's deadline.
     * Retried every 2 s on the timer; the relay drops us if it runs out.
     */
    private void rekeyAttempt(Map<String, Object> notice, long since, boolean asked) {
        if (over) return;
        long version = notice.get("symkeyVersion") instanceof Number v ? v.longValue() : -1;
        int epoch = notice.get("keyEpoch") instanceof Number e ? e.intValue() : -1;
        String nonce = notice.get("nonce") instanceof String s ? s : null;
        String authPub = notice.get("authPub") instanceof String s ? s : null;
        long within = notice.get("proveWithinSeconds") instanceof Number w ? w.longValue() * 1000 : 30_000;
        if (version < 0 || epoch < 0 || nonce == null || authPub == null) return;
        CallMedia m = media;
        if (m != null && m.keyEpoch() == (epoch & 0xFF)) return; // already there
        byte[] secret = MeetingKeys.secret(keys.symkeys(meeting.entityId, version), Hex.fromHex(nonce),
                meeting.entityId, version, meeting.meetingId, authPub);
        if (secret == null) {
            if (!asked) {
                step("rekeyed to symkey version " + version + ", which this device lacks: asking for it");
                keys.request(meeting.entityId, version);
            }
            if (System.currentTimeMillis() - since < within) {
                timer.schedule(() -> rekeyAttempt(notice, since, true), REKEY_RETRY_MS, TimeUnit.MILLISECONDS);
            }
            return;
        }
        byte[] authPriv = CallKeys.authPriv(secret);
        if (m != null) m.rekey(secret, epoch, System.currentTimeMillis());
        Arrays.fill(secret, (byte) 0);
        symkeyVersion = version;
        submit(() -> {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    link.prove(epoch, authPriv, capture.ssrc());
                    step("proved key epoch " + epoch);
                    return;
                } catch (IOException e) {
                    if (!(e instanceof CallRelayLink.Refused r) || !r.lostReply()) {
                        step("prove refused: " + e.getMessage());
                        return;
                    }
                }
            }
        });
    }

    /**
     * As host: the owner rotated the symkey (§4.5), usually to shut out a
     * member it removed. Rekey the meeting onto the new version; the relay's
     * rekey notice then moves this device too.
     */
    private void followSymkey(long now) {
        long current = keys.currentVersion(meeting.entityId);
        if (current <= symkeyVersion) return;
        if (current == rekeyTriedVersion && now - rekeyTriedAtMs < REKEY_BACKOFF_MS) return;
        List<byte[]> held = keys.symkeys(meeting.entityId, current);
        if (held == null || held.isEmpty()) return;
        rekeyTriedVersion = current;
        rekeyTriedAtMs = now;
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        byte[] secret = CallKeys.meetingSecret(held.get(0), nonce, meeting.entityId, current, meeting.meetingId);
        byte[] authPub = CallKeys.authPub(CallKeys.authPriv(secret));
        Arrays.fill(secret, (byte) 0);
        submit(() -> {
            try {
                int epoch = link.rekey(current, nonce, authPub);
                step("rekeyed onto symkey version " + current + ", key epoch " + epoch);
                listener.onRekeyed(MeetingSignal.start(meeting.meetingId, meeting.relay, nonce, current, authPub, epoch,
                        meeting.title, meeting.started));
            } catch (IOException e) {
                step("rekey refused: " + e.getMessage());
            }
        });
    }

    private void applyMute() {
        capture.setMuted(selfMuted || mutedByHost != null);
    }

    /**
     * One line for this device, then one per sender: frames that arrived and
     * opened, and what the jitter buffer made of them. Where a sender's audio
     * goes missing shows as the first number that falls short.
     */
    private void logStats() {
        PlayoutEngine p = playout;
        CallMedia m = media;
        step(String.format(java.util.Locale.US, "stats: %d in the roster, %d playing, mic level %d%s, sent %d, dtx %d, rtt %d ms",
                participants.size(), p == null ? 0 : p.streams().size(), capture.level(), isMuted() ? " (muted)" : "",
                capture.framesSent(), capture.dtxSkipped(), rttMs()));
        for (Map.Entry<Integer, long[]> e : arrivals.entrySet()) {
            int ssrc = e.getKey();
            String fid;
            synchronized (this) {
                fid = fidOfSsrc.get(ssrc);
            }
            PlayoutEngine.Stream st = p == null ? null : p.streams().get(ssrc);
            var j = st == null ? null : st.buffer.stats();
            step(String.format(java.util.Locale.US,
                    "  from %s ssrc %s: arrived %d, opened %d%s%s | played %s, lost %s, late %s, skipped %s, dtx %s, target %s ms, level %s",
                    fid, Integer.toUnsignedString(ssrc), e.getValue()[0], e.getValue()[1],
                    m != null && m.isPaused(ssrc) ? ", PAUSED" : "", m != null && m.isUnverified(ssrc) ? ", UNVERIFIED" : "",
                    j == null ? "-" : j.played(), j == null ? "-" : j.fecRecovered() + j.concealed(),
                    j == null ? "-" : j.late(), j == null ? "-" : j.skipped(), j == null ? "-" : j.dtxGap(),
                    j == null ? "-" : j.targetMs(), st == null ? "-" : st.level()));
        }
    }

    private void step(String what) {
        TimberLogger.i(TAG, "meeting %s +%dms: %s", meeting.meetingId, System.currentTimeMillis() - startedMs, what);
    }

    private void submit(Runnable r) {
        try {
            worker.execute(r);
        } catch (RejectedExecutionException ignored) {
            // over
        }
    }

    /** Once: stop everything, erase the transport key, and tell the listener why. */
    private void finish(End why, String detail) {
        if (over) return;
        over = true;
        step("over: " + why + (detail == null ? "" : " (" + detail + ")"));
        try {
            timer.shutdownNow();
            capture.stop();
            PlayoutEngine p = playout;
            if (p != null) p.stop();
            CallMedia m = media;
            CallRelayLink l = link;
            if (m != null && l != null) for (byte[] a : m.finish(System.currentTimeMillis())) l.sendAttestation(a);
            if (l != null) {
                if (why == End.LEFT || why == End.FAILED) l.leave();
                l.close();
            }
        } catch (RuntimeException e) {
            TimberLogger.e(TAG, "teardown: %s", e);
        } finally {
            Arrays.fill(tPriv, (byte) 0); // §4.1: the transport key does not outlive the join
            media = null;
            playout = null;
            link = null;
            worker.shutdown();
        }
        listener.onEnded(why, detail);
    }
}
