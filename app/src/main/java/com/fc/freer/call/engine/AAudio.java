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
    private static final String TAG = "AAudio";
    private static final int AAUDIO_ERROR_CLOSED = -869;
    /**
     * A stream is disconnected whenever routing changes: entering call mode,
     * speaker or earpiece, a headset. The fix is to open a new one (as Oboe
     * does); this many times per stream, so a device that keeps failing
     * still ends the call instead of spinning.
     */
    static final int MAX_REOPENS = 50;

    private AAudio() {}

    static native long open(boolean input, int sampleRate, boolean exclusive);
    static native int read(long h, short[] buf, int off, int n, long timeoutNs);
    static native int write(long h, short[] buf, int off, int n, long timeoutNs);
    static native int query(long h, int what);
    static native int setBufferFrames(long h, int frames);
    static native void close(long h);

    private static String mode(long h) {
        if (h == 0) return "closed";
        String sharing = query(h, Q_SHARING) == SHARING_EXCLUSIVE ? "exclusive" : "shared";
        String perf = query(h, Q_PERFORMANCE) == PERFORMANCE_LOW_LATENCY ? "low-latency" : "normal";
        return sharing + " " + perf + ", burst " + query(h, Q_BURST);
    }

    static final class Input implements AudioIo.Input {
        private final boolean exclusive;
        private volatile long h;
        private volatile String effects;
        private int reopens;

        Input(boolean exclusive) {
            this.exclusive = exclusive;
            h = open(true, Opus.SAMPLE_RATE, exclusive);
            if (h == 0) throw new IllegalStateException("AAudio input would not open at 48 kHz mono");
            effects = attachEffects(query(h, Q_SESSION));
        }

        /** @return false if it cannot be reopened, or has been too often */
        private synchronized boolean reopen(int error) {
            if (h == 0 || reopens >= MAX_REOPENS) return false;
            reopens++;
            com.fc.fc_ajdk.utils.TimberLogger.w(TAG, "input failed (%d): reopening, time %d", error, reopens);
            AAudio.close(h);
            h = open(true, Opus.SAMPLE_RATE, exclusive);
            if (h == 0) return false;
            effects = attachEffects(query(h, Q_SESSION));
            return true;
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
        public synchronized int read(short[] buf, int off, int n, boolean block) {
            if (h == 0) return AAUDIO_ERROR_CLOSED;
            int r = AAudio.read(h, buf, off, n, block ? TIMEOUT_NS : 0);
            return r < 0 && reopen(r) ? 0 : r;
        }

        @Override
        public synchronized int latencyMs() {
            return h == 0 ? -1 : query(h, Q_LATENCY_MS);
        }

        @Override
        public synchronized String effects() {
            return effects;
        }

        @Override
        public synchronized String describe() {
            return "AAudio " + mode(h) + (reopens > 0 ? ", reopened " + reopens : "");
        }

        @Override
        public synchronized void close() {
            if (h != 0) AAudio.close(h);
            h = 0;
        }
    }

    static final class Output implements AudioIo.Output {
        private final boolean exclusive;
        private volatile long h;
        private int xruns;
        private int reopens;

        Output(boolean exclusive) {
            this.exclusive = exclusive;
            h = open(false, Opus.SAMPLE_RATE, exclusive);
            if (h == 0) throw new IllegalStateException("AAudio output would not open at 48 kHz mono");
        }

        private synchronized boolean reopen(int error) {
            if (h == 0 || reopens >= MAX_REOPENS) return false;
            reopens++;
            com.fc.fc_ajdk.utils.TimberLogger.w(TAG, "output failed (%d): reopening, time %d", error, reopens);
            AAudio.close(h);
            h = open(false, Opus.SAMPLE_RATE, exclusive);
            xruns = 0;
            return h != 0;
        }

        @Override
        public synchronized void write(short[] buf, int off, int n) {
            int r = h == 0 ? AAUDIO_ERROR_CLOSED : AAudio.write(h, buf, off, n, TIMEOUT_NS);
            if (r < 0 && reopen(r)) r = AAudio.write(h, buf, off, n, TIMEOUT_NS);
            if (r < 0) throw new IllegalStateException("AAudio write failed: " + r);
            // Ran dry since last time: one more burst of buffer, up to capacity.
            int x = query(h, Q_XRUNS);
            if (x > xruns) {
                xruns = x;
                setBufferFrames(h, Math.min(query(h, Q_CAPACITY), query(h, Q_BUFFER) + query(h, Q_BURST)));
            }
        }

        @Override
        public synchronized int bufferMs() {
            return h == 0 ? 0 : query(h, Q_BUFFER) * 1000 / Opus.SAMPLE_RATE;
        }

        @Override
        public synchronized int underruns() {
            return xruns;
        }

        @Override
        public synchronized int latencyMs() {
            return h == 0 ? -1 : query(h, Q_LATENCY_MS);
        }

        @Override
        public synchronized String describe() {
            return String.format(Locale.US, "AAudio %s%s", mode(h), reopens > 0 ? ", reopened " + reopens : "");
        }

        @Override
        public synchronized void close() {
            if (h != 0) AAudio.close(h);
            h = 0;
        }
    }
}
