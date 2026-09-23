package com.fc.freer.call.engine;

import java.nio.ByteBuffer;

/**
 * The Phase 2 audio frame: the 24-byte MediaFrame header of VOICE_SPEC §5
 * with {@code kind = 0x7E} and the Opus packet in the clear, since the
 * spike rides FUDP's hop encryption only. Phase 3 replaces it with the
 * sealed {@code kind = 0x01} MediaFrame; receivers that know only that
 * ignore this kind (§5).
 *
 * <pre>
 * kind(1)=0x7E flags(1) routeId(4)=0 ssrc(4) seq(8) timestamp(4) level(1) keyEpoch(1)=0 opus(...)
 * </pre>
 */
public record SpikeFrame(int flags, int ssrc, long seq, long timestamp, int level, byte[] opus) {

    public static final int KIND = 0x7E;
    public static final int HEADER = 24;

    public static final int FLAG_VAD = 0x01;
    /**
     * Spike meaning: the frames just before this one were DTX and not sent,
     * so the gap in {@code seq} is silence, not loss.
     */
    public static final int FLAG_DTX = 0x02;

    public boolean voiceActive() {
        return (flags & FLAG_VAD) != 0;
    }

    public boolean afterDtx() {
        return (flags & FLAG_DTX) != 0;
    }

    public static SpikeFrame of(EncodedFrame f) {
        int flags = (f.voiceActive() ? FLAG_VAD : 0) | (f.afterDtx() ? FLAG_DTX : 0);
        return new SpikeFrame(flags, f.ssrc(), f.seq(), f.timestamp(), f.level(), f.opus());
    }

    public EncodedFrame toEncoded() {
        return new EncodedFrame(ssrc, seq, timestamp, level, voiceActive(), afterDtx(), opus);
    }

    public byte[] encode() {
        ByteBuffer b = ByteBuffer.allocate(HEADER + opus.length);
        b.put((byte) KIND).put((byte) flags).putInt(0).putInt(ssrc).putLong(seq)
                .putInt((int) timestamp).put((byte) level).put((byte) 0).put(opus);
        return b.array();
    }

    /** @return the frame, or null if {@code data} is not a spike frame */
    public static SpikeFrame decode(byte[] data) {
        if (data.length < HEADER || (data[0] & 0xff) != KIND) return null;
        ByteBuffer b = ByteBuffer.wrap(data);
        b.get();
        int flags = b.get() & 0xff;
        b.getInt(); // routeId
        int ssrc = b.getInt();
        long seq = b.getLong();
        long ts = b.getInt() & 0xffffffffL;
        int level = b.get() & 0xff;
        b.get(); // keyEpoch
        byte[] opus = new byte[b.remaining()];
        b.get(opus);
        return new SpikeFrame(flags, ssrc, seq, ts, level, opus);
    }

    /**
     * Audio level as -dBov, 0 (loudest) to 127 (silence), as in RFC 6464.
     */
    public static int level(short[] pcm, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) sum += (double) pcm[i] * pcm[i];
        double rms = Math.sqrt(sum / Math.max(1, n)) / 32768.0;
        if (rms <= 0) return 127;
        int dbov = (int) Math.round(-20 * Math.log10(rms));
        return Math.max(0, Math.min(127, dbov));
    }
}
