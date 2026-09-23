package com.fc.freer.call.engine;

/**
 * libopus through the NDK (VOICE_SPEC §9.1): mono, 48 kHz, VOIP mode.
 * One {@link Encoder} per outgoing stream and one {@link Decoder} per
 * incoming stream; neither is thread-safe.
 */
public final class Opus {

    public static final int SAMPLE_RATE = 48_000;

    // opus_defines.h request codes
    static final int SET_BITRATE = 4002;
    static final int SET_VBR = 4006;
    static final int SET_COMPLEXITY = 4010;
    static final int SET_INBAND_FEC = 4012;
    static final int SET_PACKET_LOSS_PERC = 4014;
    static final int SET_DTX = 4016;
    static final int SET_SIGNAL = 4024;
    static final int SIGNAL_VOICE = 3001;

    /** Largest Opus packet we ask for; a 60 ms frame at 32 kbps is ~240 bytes. */
    public static final int MAX_PACKET = 1000;

    static {
        System.loadLibrary("freervoice");
    }

    private Opus() {}

    static native long encoderCreate(int sampleRate);
    static native int encoderCtl(long handle, int request, int value);
    static native int encode(long handle, short[] pcm, int frameSize, byte[] out);
    static native void encoderDestroy(long handle);
    static native long decoderCreate(int sampleRate);
    static native int decode(long handle, byte[] data, int length, short[] pcm, int frameSize, boolean fec);
    static native void decoderDestroy(long handle);

    public static final class Encoder implements AutoCloseable {
        private long handle;

        /** Spec settings (§9.1): VBR, voice signal, in-band FEC on. */
        public Encoder(int bitrate, boolean dtx, int expectedLossPercent) {
            handle = encoderCreate(SAMPLE_RATE);
            if (handle == 0) throw new IllegalStateException("opus_encoder_create failed");
            ctl(SET_SIGNAL, SIGNAL_VOICE);
            ctl(SET_VBR, 1);
            ctl(SET_COMPLEXITY, 9);
            ctl(SET_INBAND_FEC, 1);
            setBitrate(bitrate);
            setDtx(dtx);
            setPacketLossPercent(expectedLossPercent);
        }

        public void setBitrate(int bitsPerSecond) {
            ctl(SET_BITRATE, bitsPerSecond);
        }

        public void setDtx(boolean on) {
            ctl(SET_DTX, on ? 1 : 0);
        }

        /** How much loss FEC should be sized for. 0 turns FEC's redundancy off in practice. */
        public void setPacketLossPercent(int percent) {
            ctl(SET_PACKET_LOSS_PERC, Math.max(0, Math.min(100, percent)));
        }

        /** @return bytes written to {@code out}; 1–2 bytes is a DTX frame not worth sending */
        public int encode(short[] pcm, int frameSize, byte[] out) {
            int n = Opus.encode(handle, pcm, frameSize, out);
            if (n < 0) throw new IllegalStateException("opus_encode failed: " + n);
            return n;
        }

        private void ctl(int request, int value) {
            int r = encoderCtl(handle, request, value);
            if (r != 0) throw new IllegalStateException("opus_encoder_ctl(" + request + ") failed: " + r);
        }

        @Override
        public void close() {
            if (handle != 0) encoderDestroy(handle);
            handle = 0;
        }
    }

    public static final class Decoder implements AutoCloseable {
        private long handle;

        public Decoder() {
            handle = decoderCreate(SAMPLE_RATE);
            if (handle == 0) throw new IllegalStateException("opus_decoder_create failed");
        }

        /** Decode one frame of {@code frameSize} samples into {@code pcm}. */
        public int decode(byte[] packet, short[] pcm, int frameSize) {
            return check(Opus.decode(handle, packet, packet.length, pcm, frameSize, false));
        }

        /** Recover the frame before {@code nextPacket} from the FEC data it carries. */
        public int decodeFec(byte[] nextPacket, short[] pcm, int frameSize) {
            return check(Opus.decode(handle, nextPacket, nextPacket.length, pcm, frameSize, true));
        }

        /** Packet-loss concealment for one missing frame. */
        public int conceal(short[] pcm, int frameSize) {
            return check(Opus.decode(handle, null, 0, pcm, frameSize, false));
        }

        private static int check(int n) {
            if (n < 0) throw new IllegalStateException("opus_decode failed: " + n);
            return n;
        }

        @Override
        public void close() {
            if (handle != 0) decoderDestroy(handle);
            handle = 0;
        }
    }
}
