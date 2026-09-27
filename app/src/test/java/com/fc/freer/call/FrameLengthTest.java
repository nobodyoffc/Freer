package com.fc.freer.call;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FrameLengthTest {

    /** Feeds the same path every 200 ms from {@code from} to {@code to}, like the session timer. */
    private static int run(FrameLength f, long from, long to, boolean direct, long rtt) {
        int ms = f.current();
        for (long t = from; t <= to; t += 200) ms = f.update(t, direct, rtt);
        return ms;
    }

    @Test
    public void relayedAudioStaysAt40Ms() {
        FrameLength f = new FrameLength();
        assertEquals(40, f.current());
        assertEquals("however short the relay's RTT", 40, run(f, 0, 60_000, false, 10));
    }

    @Test
    public void aFastDirectPathGets20MsAfterItHolds() {
        FrameLength f = new FrameLength();
        assertEquals("not at once", 40, run(f, 0, FrameLength.HOLD_MS - 200, true, 20));
        assertEquals(20, run(f, FrameLength.HOLD_MS, FrameLength.HOLD_MS, true, 20));
    }

    @Test
    public void aSlowDirectPathStaysAt40Ms() {
        assertEquals(40, run(new FrameLength(), 0, 60_000, true, 80));
        assertEquals("unknown RTT", 40, run(new FrameLength(), 0, 60_000, true, -1));
    }

    @Test
    public void backTo40MsOnlyWhenClearlySlowerOrRelayed() {
        FrameLength f = new FrameLength();
        run(f, 0, 5_000, true, 20);
        assertEquals(20, f.current());
        assertEquals("between the thresholds: no flapping", 20, run(f, 5_200, 30_000, true, 60));
        assertEquals("a blip shorter than the hold changes nothing", 20, run(f, 30_200, 31_000, true, 90));
        assertEquals(20, run(f, 31_200, 32_000, true, 30));
        assertEquals("the direct path went down", 40, run(f, 32_200, 36_000, false, 25));
    }

    @Test
    public void slowForLongEnoughGoesBackTo40Ms() {
        FrameLength f = new FrameLength();
        run(f, 0, 5_000, true, 20);
        assertEquals(40, run(f, 5_200, 9_000, true, 75));
    }
}
