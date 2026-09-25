package com.fc.freer.call;

import android.content.Context;

import com.fc.fc_ajdk.call.CallKeys;
import com.fc.fc_ajdk.call.Delegation;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.call.engine.AudioIo;
import com.fc.freer.call.engine.CaptureEngine;
import com.fc.freer.call.engine.PlayoutEngine;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One 1:1 call through the relay (VOICE_SPEC §6.2): the signaller's call,
 * its {@link CallRelayLink}, {@link CallMedia}, and the capture and playout
 * engines. Network steps run on the session's own thread.
 * <p>
 * Caller: connect, {@code call.create}, join, and wait; once the callee's
 * ACCEPT verifies, register {@code authPub} and start the media. Callee,
 * after accepting: connect, join with {@code admitSig} (retrying while the
 * caller has not registered), start the media.
 */
public final class CallSession {

    private static final String TAG = "CallSession";
    private static final long ATTEST_CHECK_MS = 200;
    private static final long STATS_EVERY_MS = 5_000;
    private static final long PEER_GONE_MS = 3_000;
    private static final int REGISTER_ATTEMPTS = 3;
    public static final int FRAME_MS = 20;
    public static final int BITRATE = 24_000;
    public static final int EXPECTED_LOSS = 10;

    public enum State { CONNECTING, RINGING, CONNECTED, ENDED, FAILED }

    public interface Listener {
        void onState(State state, String detail);

        /** Audio claimed to be from {@code fid} could not be verified (§5.1). */
        void onUnverified(String fid);

        /** On the session's thread, just before the audio streams open: enter call audio mode now. */
        void beforeAudio();

        /** Audio moved to the direct path, or back to the relay. */
        void onPathChanged();
    }

    private final Context context;
    private final CallSignaller signaller;
    private final CallSignaller.Call call;
    private final String myFid;
    private final AudioIo.Backend backend;
    private final boolean allowDirect;
    private final Listener listener;
    private final byte[] tPriv;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "call-session");
        // A bug in a call must end the call, not the app (and with it the unlocked wallet).
        t.setUncaughtExceptionHandler((th, e) -> TimberLogger.e(TAG, "call-session: %s", e));
        return t;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "call-session-timer");
        t.setDaemon(true);
        return t;
    });
    private final CaptureEngine capture;

    private volatile CallRelayLink link;
    private volatile CallMedia media;
    private volatile PlayoutEngine playout;
    private volatile Map<String, Object> lastRoster;
    private volatile State state = State.CONNECTING;
    private volatile int routeId;
    private volatile boolean muted;
    private volatile boolean heardFirst;
    private volatile boolean paymentRequired;
    private volatile boolean peerSeen;
    private volatile long framesOpened;
    private int statTicks;
    private final long startedMs = System.currentTimeMillis();

    /**
     * @param allowDirect try a direct path (§6.2 step 6): only with a contact,
     *                    and never with Always relay on (Decision 8)
     */
    public CallSession(Context context, CallSignaller signaller, CallSignaller.Call call, String myFid,
                       AudioIo.Backend backend, boolean allowDirect, Listener listener) {
        this.context = context.getApplicationContext();
        this.allowDirect = allowDirect;
        this.signaller = signaller;
        this.call = call;
        this.myFid = myFid;
        this.backend = backend;
        this.listener = listener;
        this.tPriv = call.transportPriv();
        this.capture = new CaptureEngine(new CaptureEngine.Settings(FRAME_MS, BITRATE, true, EXPECTED_LOSS, backend),
                f -> {
                    CallMedia m = media;
                    CallRelayLink l = link;
                    if (m == null || l == null) return;
                    byte[] frame = m.seal(f, System.currentTimeMillis());
                    CallDirectPath d = l.direct();
                    if (d != null && d.isUp()) d.send(frame);
                    else l.sendFrame(frame);
                });
    }

    public CallSignaller.Call call() {
        return call;
    }

    public State state() {
        return state;
    }

    /**
     * Caller: reach the relay, open the call there, and only then ring, with
     * the relay's key and service id in the INVITE so the callee can connect
     * without discovery. A callee never rings for a call that cannot connect.
     */
    public void startOutgoing() {
        worker.execute(() -> {
            try {
                openLink();
                link.create();
                Map<String, Object> joined = link.join(capture.ssrc(), null, allowDirect);
                routeId = (int) ((Number) joined.get("routeId")).longValue();
                step("created and joined the call on the relay; ringing");
                signaller.ring(call.callId, new com.fc.fc_ajdk.call.CallSignal.Relay(call.relayUrl,
                        link.relayPubkey(), link.relaySid()), null);
                setState(State.RINGING, null);
            } catch (CallRelayLink.Refused e) {
                // 402: the caller pays for the call (§7.5) and cannot afford a minute of it.
                paymentRequired = e.code == 402;
                fail("could not open the call on the relay: " + e.getMessage());
            } catch (Exception e) {
                fail("could not reach the relay: " + e.getMessage());
            }
        });
    }

    /** Caller: the callee's ACCEPT verified. */
    public void onAnswered() {
        worker.execute(() -> {
            try {
                byte[] secret = signaller.callSecret(call.callId);
                if (secret == null) throw new IllegalStateException("no call key");
                step("answered; registering authPub");
                register(CallKeys.authPub(CallKeys.authPriv(secret)));
                startMedia(secret);
            } catch (Exception e) {
                fail("could not open the call on the relay: " + e.getMessage());
            }
        });
    }

    /** Callee: after the signaller sent ACCEPT. */
    public void startIncoming() {
        worker.execute(() -> {
            try {
                byte[] secret = signaller.callSecret(call.callId);
                if (secret == null) throw new IllegalStateException("no call key");
                openLink();
                Map<String, Object> joined = link.join(capture.ssrc(), CallKeys.authPriv(secret), allowDirect);
                routeId = (int) ((Number) joined.get("routeId")).longValue();
                step("joined the call on the relay");
                lastRoster = joined;
                watchPeer(joined);
                startMedia(secret);
            } catch (Exception e) {
                fail("could not join the call on the relay: " + e.getMessage());
            }
        });
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        capture.setMuted(muted);
    }

    public boolean isMuted() {
        return muted;
    }

    /** Relay RTT, ms, or -1. */
    /** RTT on the path audio takes, ms, or -1. */
    public long rttMs() {
        CallRelayLink l = link;
        if (l == null) return -1;
        CallDirectPath d = l.direct();
        return d != null && d.isUp() ? d.rttMs() : l.rttMs();
    }

    /** Stop everything; the signalling side (HANGUP, CANCEL) is the caller's to send. */
    public void end() {
        worker.execute(() -> {
            teardown();
            if (state != State.FAILED) setState(State.ENDED, null);
        });
        worker.shutdown();
    }

    // ===== Internals =====

    private void openLink() throws Exception {
        if (call.relayUrl == null) throw new IllegalStateException("no relay for this call");
        File dir = new File(context.getCacheDir(), "call/" + call.callId);
        link = new CallRelayLink(dir, tPriv, call.callId, call.myDelegation, new CallRelayLink.Events() {
            @Override
            public void onFrame(byte[] datagram, boolean direct) {
                CallMedia m = media;
                PlayoutEngine p = playout;
                if (m == null || p == null) return;
                var f = m.open(datagram, System.currentTimeMillis(), direct);
                if (f == null) return;
                framesOpened++;
                if (!heardFirst) {
                    heardFirst = true;
                    step("first audio frame from the peer");
                }
                p.onFrame(f, android.os.SystemClock.elapsedRealtime());
            }

            @Override
            public void onAttestation(byte[] attestation) {
                CallMedia m = media;
                if (m != null) m.onAttestation(attestation);
            }

            @Override
            public void onNotice(Map<String, Object> notice) {
                String type = String.valueOf(notice.get("type"));
                switch (type) {
                    case "roster" -> {
                        step("roster: " + CallRelayLink.roster(notice).size() + " in the call");
                        lastRoster = notice;
                        watchPeer(notice);
                        addPeers(notice);
                    }
                    case "knock" -> {
                        if (!call.callId.equals(notice.get("meetingId"))
                                || !(notice.get("delegation") instanceof String json)) {
                            return;
                        }
                        step("the relay says " + notice.get("fid") + " is waiting to join");
                        try {
                            signaller.onKnock(call.callId, Delegation.fromJson(json));
                        } catch (RuntimeException ignored) {
                            // not a delegation we can read: the ACCEPT may still come
                        }
                    }
                    case "kicked" -> {
                        if (!worker.isShutdown()) worker.execute(() -> fail("removed by the relay: " + notice.get("reason")));
                    }
                    case "balance" -> TimberLogger.w(TAG, "relay says the balance is low");
                    default -> { }
                }
            }
        });
        step("connecting to " + call.relayUrl);
        String how = link.connect(call.relayUrl, call.relayPubkey, call.relaySid, KnownRelays.of(context));
        step("connected to the relay " + how);
    }

    /**
     * A peer that was on the relay and left has hung up, even if its HANGUP
     * never reaches us over IM. It gets a few seconds to come back, as on a
     * reconnect, before the call ends. (With direct paths, §6.2 step 8, a
     * peer leaves the relay on purpose: this must then watch the direct path.)
     */
    private void watchPeer(Map<String, Object> roster) {
        boolean present = CallRelayLink.roster(roster).stream().anyMatch(e -> call.peerFid.equals(e.get("fid")));
        if (present) {
            peerSeen = true;
            return;
        }
        if (!peerSeen || timer.isShutdown()) return;
        try {
            timer.schedule(() -> {
                Map<String, Object> r = lastRoster;
                boolean back = r != null
                        && CallRelayLink.roster(r).stream().anyMatch(e -> call.peerFid.equals(e.get("fid")));
                if (!back && state == State.CONNECTED) {
                    step("the peer left the relay: the call is over");
                    signaller.peerLeft(call.callId);
                }
            }, PEER_GONE_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // ending anyway
        }
    }

    /** Levels are -dBov: 127 is silence, speech is roughly 20-50. */
    private void logStats(PlayoutEngine p) {
        StringBuilder in = new StringBuilder();
        for (PlayoutEngine.Stream s : p.streams().values()) {
            in.append(" ssrc ").append(Integer.toUnsignedString(s.ssrc)).append(" level ").append(s.level())
                    .append(" buffered ").append(s.buffer.depthMs()).append("ms");
        }
        step(String.format(java.util.Locale.US,
                "stats: %s | mic level %d%s, sent %d, dtx %d, via %s [%s] | received %d, playing%s, underruns %d, via %s",
                isDirect() ? "direct" : "relay",
                capture.level(), muted ? " (muted)" : "", capture.framesSent(), capture.dtxSkipped(),
                capture.describe(), capture.effects(), framesOpened,
                in.length() == 0 ? " nothing" : in, p.underruns(), p.describe()));
    }

    /** One line per step, timed from the session's start, so a failed call shows where it stopped. */
    private void step(String what) {
        TimberLogger.i(TAG, "call %s +%dms: %s", call.callId, System.currentTimeMillis() - startedMs, what);
    }

    private void startMedia(byte[] secret) {
        CallMedia m = new CallMedia(call.callId, secret, myFid, capture.ssrc(), tPriv);
        Arrays.fill(secret, (byte) 0);
        m.setRouteId(routeId);
        PlayoutEngine p = new PlayoutEngine(FRAME_MS, backend);
        m.setListener((fid, ssrc) -> {
            step("peer's audio unverified and silenced");
            p.silence(ssrc);
            listener.onUnverified(fid);
        });
        playout = p;
        media = m;
        if (lastRoster != null) addPeers(lastRoster);
        listener.beforeAudio();
        p.start();
        capture.start();
        timer.scheduleWithFixedDelay(() -> {
            long now = System.currentTimeMillis();
            CallRelayLink l = link;
            if (l != null) for (byte[] a : m.takeAttestations(now)) sendAttestation(l, a);
            m.tick(now);
            if (++statTicks % (STATS_EVERY_MS / ATTEST_CHECK_MS) == 0) logStats(p);
            // Checked 5 times a second so each attestation leaves ~1 s after its
            // first frame, not up to 2 s: the far end mutes us at 3 s (§5.1).
        }, ATTEST_CHECK_MS, ATTEST_CHECK_MS, TimeUnit.MILLISECONDS);
        step("media started");
        setState(State.CONNECTED, null);
    }

    /**
     * Hear a roster entry only if it is the peer the signalling named, under a
     * delegation that verifies for this call and names the same transport key
     * the peer's own INVITE or ACCEPT did. A relay lying in the roster gets
     * no stream played (§5.1, §12 item 4).
     */
    private void addPeers(Map<String, Object> roster) {
        CallMedia m = media;
        Delegation signalled = call.peerDelegation();
        if (m == null || signalled == null) return;
        for (Map<String, Object> e : CallRelayLink.roster(roster)) {
            if (!call.peerFid.equals(e.get("fid")) || !(e.get("delegation") instanceof String json)) continue;
            Delegation d;
            try {
                d = Delegation.fromJson(json);
            } catch (RuntimeException ex) {
                continue;
            }
            if (d == null || d.verify(call.callId, System.currentTimeMillis() / 1000) != Delegation.Check.OK
                    || !call.peerFid.equals(d.fid) || !Arrays.equals(d.tPubBytes(), signalled.tPubBytes())) {
                step("roster entry for the peer rejected: its delegation does not match the signalled one");
                continue;
            }
            m.addPeer(call.peerFid, (int) ((Number) e.get("ssrc")).longValue(), d.tPubBytes());
            step("hearing the peer");
            tryDirect(e, d);
        }
    }

    /**
     * {@code call.register}, retried when its reply is lost: it is idempotent
     * for the same authPub. The relay admits the peer only after it, so a
     * peer already in the roster means it took, whatever became of the reply.
     */
    private void register(byte[] authPub) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                link.register(authPub);
                step("registered");
                return;
            } catch (CallRelayLink.Refused e) {
                throw e; // a real answer, not a lost one
            } catch (IOException e) {
                if (peerSeen) {
                    step("register's reply was lost, but the peer is in the call: it took");
                    return;
                }
                if (attempt >= REGISTER_ATTEMPTS) throw e;
                step("register attempt " + attempt + " got no reply (" + e.getMessage() + "); retrying");
            }
        }
    }

    /** Attestations go the way the frames they cover went. */
    private static void sendAttestation(CallRelayLink l, byte[] a) {
        CallDirectPath d = l.direct();
        if (d != null && d.isUp()) d.sendAttestation(a);
        else l.sendAttestation(a);
    }

    /** The relay refused to open the call because my balance there is too low. */
    public boolean paymentRequired() {
        return paymentRequired;
    }

    public boolean isDirect() {
        CallRelayLink l = link;
        CallDirectPath d = l == null ? null : l.direct();
        return d != null && d.isUp();
    }

    /**
     * The peer's roster entry, its delegation verified, carries the candidates
     * it chose to share: punch to them, keeping the relay (§6.2 steps 6-9).
     * The lower FID opens the connection.
     */
    private void tryDirect(Map<String, Object> entry, Delegation peer) {
        CallRelayLink l = link;
        if (!allowDirect || l == null || l.direct() != null || !(entry.get("candidates") instanceof List<?> list)) return;
        List<CallDirectPath.Candidate> candidates = new java.util.ArrayList<>();
        for (Object o : list) {
            CallDirectPath.Candidate c = o instanceof Map<?, ?> m ? CallDirectPath.Candidate.parse(m) : null;
            if (c != null) candidates.add(c);
        }
        if (candidates.isEmpty()) return;
        l.startDirect(peer.tPubBytes(), myFid.compareTo(call.peerFid) < 0, candidates, new CallDirectPath.Listener() {
            @Override
            public void onUp() {
                CallMedia m = media;
                if (m != null) m.setRouteId(0); // §5: routeId 0 on a direct path
                step("audio now goes direct");
                listener.onPathChanged();
            }

            @Override
            public void onDown() {
                CallMedia m = media;
                if (m != null) m.setRouteId(routeId);
                step("back on the relay");
                listener.onPathChanged();
            }

            @Override
            public void log(String what) {
                step("direct: " + what);
            }
        });
    }

    /** Nothing in here may throw: it runs on the failure path too. */
    private void teardown() {
        try {
            doTeardown();
        } catch (RuntimeException e) {
            TimberLogger.e(TAG, "teardown of %s: %s", call.callId, e);
        } finally {
            Arrays.fill(tPriv, (byte) 0); // §4.1: the transport key does not outlive the call
        }
    }

    private void doTeardown() {
        timer.shutdownNow();
        capture.stop();
        PlayoutEngine p = playout;
        if (p != null) p.stop();
        CallMedia m = media;
        CallRelayLink l = link;
        if (m != null && l != null) {
            for (byte[] a : m.finish(System.currentTimeMillis())) sendAttestation(l, a);
        }
        if (l != null) {
            l.leave();
            l.close();
        }
        media = null;
        playout = null;
        link = null;
    }

    private void fail(String why) {
        TimberLogger.e(TAG, "call %s failed: %s", call.callId, why);
        teardown();
        setState(State.FAILED, why);
    }

    private void setState(State s, String detail) {
        state = s;
        listener.onState(s, detail);
    }
}
