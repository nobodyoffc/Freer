package com.fc.freer.call.engine;

import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;

import java.util.Locale;

/**
 * AAudio through the NDK: low-latency capture and playout for calls
 * (VOICE_SPEC §9.2). Voice-call input preset and output usage, as with
 * AudioRecord/AudioTrack, so the platform's echo canceller still applies;
 * the difference is the smaller buffering. Exclusive mode asks for MMAP,
 * the lowest latency, which on some devices bypasses the effects.
 */
final class AAudio {

    static {
        System.loadLibrary("freervoice");
    }

    private static final int Q_SHARING = 0, Q_PERFORMANCE = 1, Q_BURST = 2, Q_BUFFER = 3, Q_CAPACITY = 4,
            Q_XRUNS = 5, Q_SESSION = 6, Q_LATENCY_MS = 7;
    private static final int SHARING_EXCLUSIVE = 0;
    private static final int PERFORMANCE_LOW_LATENCY = 12;
    private static final long TIMEOUT_NS = 200_000_000L;

    private AAudio() {}

    static native long open(boolean input, int sampleRate, boolean exclusive);
    static native int read(long h, short[] buf, int off, int n, long timeoutNs);
    static native int write(long h, short[] buf, int off, int n, long timeoutNs);
    static native int query(long h, int what);
    static native int setBufferFrames(long h, int frames);
    static native void close(long h);

    private static String mode(long h) {
        String sharing = query(h, Q_SHARING) == SHARING_EXCLUSIVE ? "exclusive" : "shared";
        String perf = query(h, Q_PERFORMANCE) == PERFORMANCE_LOW_LATENCY ? "low-latency" : "normal";
        return sharing + " " + perf + ", burst " + query(h, Q_BURST);
    }

    static final class Input implements AudioIo.Input {
        private long h;
        private final String effects;

        Input(boolean exclusive) {
            h = open(true, Opus.SAMPLE_RATE, exclusive);
            if (h == 0) throw new IllegalStateException("AAudio input would not open at 48 kHz mono");
            effects = attachEffects(query(h, Q_SESSION));
        }

        /** The preset asks for these; attaching them too shows whether the device has them. */
        private static String attachEffects(int session) {
            if (session <= 0) return "preset only";
            StringBuilder sb = new StringBuilder();
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler aec = AcousticEchoCanceler.create(session);
                if (aec != null && aec.setEnabled(true) == 0) sb.append("AEC ");
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor ns = NoiseSuppressor.create(session);
                if (ns != null && ns.setEnabled(true) == 0) sb.append("NS ");
            }
            return sb.length() == 0 ? "none" : sb.toString().trim();
        }

        @Override
        public int read(short[] buf, int off, int n, boolean block) {
            return AAudio.read(h, buf, off, n, block ? TIMEOUT_NS : 0);
        }

        @Override
        public int latencyMs() {
            return query(h, Q_LATENCY_MS);
        }

        @Override
        public String effects() {
            return effects;
        }

        @Override
        public String describe() {
            return "AAudio " + mode(h);
        }

        @Override
        public void close() {
            if (h != 0) AAudio.close(h);
            h = 0;
        }
    }

    static final class Output implements AudioIo.Output {
        private long h;
        private int xruns;

        Output(boolean exclusive) {
            h = open(false, Opus.SAMPLE_RATE, exclusive);
            if (h == 0) throw new IllegalStateException("AAudio output would not open at 48 kHz mono");
        }

        @Override
        public void write(short[] buf, int off, int n) {
            int r = AAudio.write(h, buf, off, n, TIMEOUT_NS);
            if (r < 0) throw new IllegalStateException("AAudio write failed: " + r);
            // Ran dry since last time: one more burst of buffer, up to capacity.
            int x = query(h, Q_XRUNS);
            if (x > xruns) {
                xruns = x;
                setBufferFrames(h, Math.min(query(h, Q_CAPACITY), query(h, Q_BUFFER) + query(h, Q_BURST)));
            }
        }

        @Override
        public int bufferMs() {
            return query(h, Q_BUFFER) * 1000 / Opus.SAMPLE_RATE;
        }

        @Override
        public int underruns() {
            return xruns;
        }

        @Override
        public int latencyMs() {
            return query(h, Q_LATENCY_MS);
        }

        @Override
        public String describe() {
            return String.format(Locale.US, "AAudio %s", mode(h));
        }

        @Override
        public void close() {
            if (h != 0) AAudio.close(h);
            h = 0;
        }
    }
}
