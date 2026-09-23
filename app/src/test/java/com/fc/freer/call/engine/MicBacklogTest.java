package com.fc.freer.call.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MicBacklogTest {

    private static final int FRAME = 960; // 20 ms at 48 kHz

    @Test
    public void aStandingBacklogIsDroppedToOneFrame() {
        MicBacklog b = new MicBacklog(FRAME);
        int dropped = 0;
        for (long t = 0; t <= MicBacklog.WINDOW_MS; t += 20) dropped += b.observe(4 * FRAME, t);
        assertEquals(3 * FRAME, dropped);
        assertEquals(80, b.queueMs());
        assertEquals(60, b.droppedMs());
    }

    @Test
    public void burstyDeliveryIsNotABacklog() {
        // The device hands over 80 ms at a time: the queue swings 0..3 frames.
        MicBacklog b = new MicBacklog(FRAME);
        int dropped = 0;
        for (long t = 0; t <= 3 * MicBacklog.WINDOW_MS; t += 20) dropped += b.observe((int) (t / 20 % 4) * FRAME, t);
        assertEquals(0, dropped);
        assertEquals(0, b.queueMs());
    }

    @Test
    public void oneFrameOfSlackIsKept() {
        MicBacklog b = new MicBacklog(FRAME);
        int dropped = 0;
        for (long t = 0; t <= MicBacklog.WINDOW_MS; t += 20) dropped += b.observe(FRAME + FRAME / 2, t);
        assertEquals(0, dropped);
    }
}
