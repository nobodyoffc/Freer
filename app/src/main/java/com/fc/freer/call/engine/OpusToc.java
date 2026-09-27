package com.fc.freer.call.engine;

/**
 * How much audio an Opus packet holds, read from its TOC byte (RFC 6716
 * §3.1), without decoding it. A receiver learns each sender's frame length
 * this way (VOICE_SPEC §9.1): senders choose it, and may change it mid-call.
 * Kept apart from {@link Opus}, which loads the native library.
 */
public final class OpusToc {

    private OpusToc() {}

    /** Samples per frame at 48 kHz by configuration: SILK 0-11, hybrid 12-15, CELT 16-31. */
    private static int frameSamples(int config) {
        if (config < 12) return new int[]{480, 960, 1920, 2880}[config & 3];
        if (config < 16) return (config & 1) == 0 ? 480 : 960;
        return new int[]{120, 240, 480, 960}[config & 3];
    }

    /** @return the packet's audio in 48 kHz samples, or -1 if it is malformed */
    public static int samples(byte[] packet) {
        if (packet == null || packet.length < 1) return -1;
        int toc = packet[0] & 0xFF;
        int frames = switch (toc & 3) {
            case 0 -> 1;
            case 1, 2 -> 2;
            default -> packet.length < 2 ? 0 : packet[1] & 0x3F;
        };
        int total = frames * frameSamples(toc >> 3);
        return frames == 0 || total > 2880 ? -1 : total; // a packet holds at most 120 ms; we send at most 60
    }
}
