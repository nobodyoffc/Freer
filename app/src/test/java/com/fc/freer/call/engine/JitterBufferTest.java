package com.fc.freer.call.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.LongUnaryOperator;

/**
 * JitterBuffer (VOICE_SPEC §9.3) against simulated arrivals: a sender making
 * one frame every 20 ms, a network that delays or drops them, and a playout
 * clock pulling once per frame period.
 */
public class JitterBufferTest {

    private static final int FRAME_MS = 20;
    private static final int SAMPLES = 48 * FRAME_MS;

    /** One simulated run. */
    private static final class Sim {
        final JitterBuffer jb = new JitterBuffer(FRAME_MS);
        final List<JitterBuffer.Kind> pulls = new ArrayList<>();
        final List<long[]> arrivals = new ArrayList<>(); // {arrivalMs, seq, flags}

        /** Frame {@code seq} leaves at seq * 20 ms and arrives {@code delay} later. flags: 1 silent, 2 afterDtx. */
        void send(long seq, long delayMs, int flags) {
            arrivals.add(new long[]{seq * FRAME_MS + delayMs, seq, flags});
        }

        void run(long untilMs) {
            arrivals.sort((a, b) -> Long.compare(a[0], b[0]));
            int next = 0;
            for (long t = 0; t <= untilMs; t++) {
                while (next < arrivals.size() && arrivals.get(next)[0] <= t) {
                    long[] a = arrivals.get(next++);
                    jb.put(a[1], a[1] * SAMPLES, new byte[]{(byte) a[1]}, (a[2] & 1) != 0, (a[2] & 2) != 0, a[0]);
                }
                if (t % FRAME_MS == 5) pulls.add(jb.pull(t).kind()); // playout phase offset from sending
            }
        }

        JitterBuffer.Stats stats() {
            return jb.stats();
        }
    }

    private static Sim steady(int frames, LongUnaryOperator delay) {
        Sim s = new Sim();
        for (long i = 0; i < frames; i++) {
            long d = delay.applyAsLong(i);
            if (d >= 0) s.send(i, d, 0);
        }
        return s;
    }

    @Test
    public void steadyStreamPlaysEverythingAtMinimumDelay() {
        Sim s = steady(250, i -> 30);
        s.run(250 * FRAME_MS + 500);
        JitterBuffer.Stats st = s.stats();
        assertEquals(250, st.played());
        assertEquals(0, st.fecRecovered() + st.concealed() + st.late());
        assertEquals(JitterBuffer.MIN_TARGET_MS, st.targetMs());
    }

    @Test
    public void lostFrameIsRecoveredFromTheNextFramesFec() {
        Sim s = steady(100, i -> i == 50 ? -1 : 30);
        s.run(100 * FRAME_MS + 500);
        JitterBuffer.Stats st = s.stats();
        assertEquals(99, st.played());
        assertEquals(1, st.fecRecovered());
        assertEquals(0, st.concealed());
        assertTrue(s.pulls.contains(JitterBuffer.Kind.FEC));
    }

    @Test
    public void twoLostInARowConcealsTheFirstAndRecoversTheSecond() {
        Sim s = steady(100, i -> i == 50 || i == 51 ? -1 : 30);
        s.run(100 * FRAME_MS + 500);
        JitterBuffer.Stats st = s.stats();
        assertEquals(1, st.concealed());
        assertEquals(1, st.fecRecovered());
        assertEquals(2.0, st.lossPercent(), 0.01);
    }

    @Test
    public void targetFollowsJitterAndKeepsLateFramesRare() {
        Random r = new Random(7);
        Sim s = steady(500, i -> 30 + r.nextInt(100)); // 0-100 ms of jitter
        s.run(500 * FRAME_MS + 1000);
        JitterBuffer.Stats st = s.stats();
        assertTrue("target " + st.targetMs() + " should track ~95 ms of jitter",
                st.targetMs() >= 80 && st.targetMs() <= 110);
        long lost = st.fecRecovered() + st.concealed();
        assertTrue("lost to lateness " + lost + " of 500", lost <= 25);
        assertTrue("it grew by waiting for late frames", st.stretched() > 0);
    }

    @Test
    public void targetStaysWithinBounds() {
        Sim s = steady(300, i -> 30 + (i % 2 == 0 ? 0 : 900)); // absurd jitter
        s.run(300 * FRAME_MS + 2000);
        assertTrue(s.stats().targetMs() <= JitterBuffer.MAX_TARGET_MS);
    }

    @Test
    public void shortDtxGapIsSilenceNotLoss() {
        Sim s = new Sim();
        for (long i = 0; i < 100; i++) {
            if (i >= 40 && i < 45) continue; // DTX: not sent
            s.send(i, 30, i == 45 ? 2 : 0);
        }
        s.run(100 * FRAME_MS + 500);
        JitterBuffer.Stats st = s.stats();
        assertEquals(5, st.dtxGap());
        assertEquals(0, st.fecRecovered() + st.concealed());
        assertEquals(0.0, st.lossPercent(), 0.001);
    }

    @Test
    public void longPauseRestartsPlayoutAtTheNextSpurt() {
        Sim s = new Sim();
        for (long i = 0; i < 50; i++) s.send(i, 30, 0);
        for (long i = 100; i < 150; i++) s.send(i, 30, i == 100 ? 2 : 0); // 1 s of silence between
        s.run(150 * FRAME_MS + 500);
        JitterBuffer.Stats st = s.stats();
        assertEquals(100, st.played());
        assertEquals(0, st.fecRecovered() + st.concealed() + st.late());
    }

    @Test
    public void aBurstAfterAStallIsCaughtUpNotPlayedLate() {
        Sim s = new Sim();
        for (long i = 0; i < 300; i++) {
            // 1 s stall: frames 100-149 all arrive together at 3.0 s
            long d = i >= 100 && i < 150 ? 3000 - i * FRAME_MS : 30;
            s.send(i, d, 1); // all silent, so the buffer may skip any of them
        }
        s.run(300 * FRAME_MS + 1000);
        JitterBuffer.Stats st = s.stats();
        assertTrue("some frames were skipped to catch up", st.skipped() > 0);
        assertTrue("delay came back down: depth " + st.depthMs(), st.depthMs() <= JitterBuffer.MAX_TARGET_MS);
    }

    @Test
    public void fivePercentRandomLossIsReportedAsFivePercent() {
        Random r = new Random(11);
        Sim s = steady(2000, i -> r.nextDouble() < 0.05 ? -1 : 30);
        s.run(2000 * FRAME_MS + 500);
        JitterBuffer.Stats st = s.stats();
        assertEquals(5.0, st.lossPercent(), 1.0);
        assertTrue("most single losses come back through FEC", st.fecRecovered() > st.concealed());
    }

    @Test
    public void duplicatesAndLateArrivalsAreCountedNotPlayed() {
        Sim s = steady(100, i -> 30);
        s.send(20, 30, 0);          // duplicate, same time
        s.send(60, 1500, 0);        // frame 60 again, long after its turn (and after the stream paused)
        s.run(100 * FRAME_MS + 2000);
        JitterBuffer.Stats st = s.stats();
        assertEquals(100, st.played());
        assertEquals(1, st.duplicates());
        assertEquals(1, st.late());
    }
}
