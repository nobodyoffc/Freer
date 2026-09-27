package com.fc.freer.call.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OpusTocTest {

    private static byte[] packet(int config, int code, int... more) {
        byte[] p = new byte[2 + more.length];
        p[0] = (byte) (config << 3 | code);
        for (int i = 0; i < more.length; i++) p[1 + i] = (byte) more[i];
        return p;
    }

    @Test
    public void oneFramePackets() {
        assertEquals("SILK 20 ms", 960, OpusToc.samples(packet(1, 0)));
        assertEquals("SILK 40 ms", 1920, OpusToc.samples(packet(2, 0)));
        assertEquals("SILK 60 ms", 2880, OpusToc.samples(packet(11, 0)));
        assertEquals("hybrid 20 ms", 960, OpusToc.samples(packet(13, 0)));
        assertEquals("CELT 20 ms", 960, OpusToc.samples(packet(31, 0)));
        assertEquals("CELT 10 ms", 480, OpusToc.samples(packet(18, 0)));
    }

    @Test
    public void severalFramesInOnePacket() {
        assertEquals("two CELT 20 ms frames: what a 40 ms frame at higher rates looks like", 1920,
                OpusToc.samples(packet(31, 1)));
        assertEquals("two hybrid 20 ms frames of different sizes", 1920, OpusToc.samples(packet(15, 2)));
        assertEquals("code 3, three 20 ms frames", 2880, OpusToc.samples(packet(31, 3, 3)));
    }

    @Test
    public void malformedOrTooLong() {
        assertEquals(-1, OpusToc.samples(new byte[0]));
        assertEquals(-1, OpusToc.samples(null));
        assertEquals("code 3 without its count byte", -1, OpusToc.samples(new byte[]{(byte) (31 << 3 | 3)}));
        assertEquals("code 3 with zero frames", -1, OpusToc.samples(packet(31, 3, 0)));
        assertEquals("120 ms is more than we play", -1, OpusToc.samples(packet(11, 1)));
    }
}
