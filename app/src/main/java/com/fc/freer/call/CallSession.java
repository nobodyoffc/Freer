package com.fc.freer.call;

import android.content.Context;

import com.fc.fc_ajdk.call.CallKeys;
import com.fc.fc_ajdk.call.Delegation;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.call.engine.AudioIo;
import com.fc.freer.call.engine.CaptureEngine;
import com.fc.freer.call.engine.PlayoutEngine;

import java.io.File;
import java.util.Arrays;
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
    public static final int FRAME_MS = 20;
    public static final int BITRATE = 24_000;
    public static final int EXPECTED_LOSS = 10;

    public enum State { CONNECTING, RINGING, CONNECTED, ENDED, FAILED }

    public interface Listener {
        void onState(State state, String detail);

        /** Audio claimed to be from {@code fid} could not be verified (§5.1). */
        void onUnverified(String fid);
    }

    private final Context context;
    private final CallSignaller signaller;
    private final CallSignaller.Call call;
    private final String myFid;
    private final AudioIo.Backend backend;
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
    private final long startedMs = System.currentTimeMillis();

    public CallSession(Context context, CallSignaller signaller, CallSignaller.Call call, String myFid,
                       AudioIo.Backend backend, Listener listener) {
        this.context = context.getApplicationContext();
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
                    if (m != null && l != null) l.sendFrame(m.seal(f, System.currentTimeMillis()));
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
                Map<String, Object> joined = link.join(capture.ssrc(), null);
                routeId = (int) ((Number) joined.get("routeId")).longValue();
                step("created and joined the call on the relay; ringing");
                signaller.ring(call.callId, new com.fc.fc_ajdk.call.CallSignal.Relay(call.relayUrl,
                        link.relayPubkey(), link.relaySid()), null);
                setState(State.RINGING, null);
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
                link.register(CallKeys.authPub(CallKeys.authPriv(secret)));
                step("registered");
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
                Map<String, Object> joined = link.join(capture.ssrc(), CallKeys.authPriv(secret));
                routeId = (int) ((Number) joined.get("routeId")).longValue();
                step("joined the call on the relay");
                lastRoster = joined;
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
    public long rttMs() {
        CallRelayLink l = link;
        return l == null ? -1 : l.rttMs();
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
            public void onFrame(byte[] datagram) {
                CallMedia m = media;
                PlayoutEngine p = playout;
                if (m == null || p == null) return;
                var f = m.open(datagram, System.currentTimeMillis());
                if (f == null) return;
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
                        addPeers(notice);
                    }
                    case "kicked" -> {
                        if (!worker.isShutdown()) worker.execute(() -> fail("removed by the relay: " + notice.get("reason")));
                    }
                    case "balance" -> TimberLogger.w(TAG, "relay says the balance is low");
                    default -> { }
                }
            }
        });
        step("connecting to " + call.relayUrl + (call.relayPubkey != null ? " with its key" : " by discovery"));
        link.connect(call.relayUrl, call.relayPubkey, call.relaySid);
        step("connected to the relay");
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
            p.silence(ssrc);
            listener.onUnverified(fid);
        });
        playout = p;
        media = m;
        if (lastRoster != null) addPeers(lastRoster);
        p.start();
        capture.start();
        timer.scheduleWithFixedDelay(() -> {
            long now = System.currentTimeMillis();
            CallRelayLink l = link;
            if (l != null) for (byte[] a : m.takeAttestations(now)) l.sendAttestation(a);
            m.tick(now);
        }, 1, 1, TimeUnit.SECONDS);
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
        }
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
            for (byte[] a : m.finish(System.currentTimeMillis())) l.sendAttestation(a);
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
