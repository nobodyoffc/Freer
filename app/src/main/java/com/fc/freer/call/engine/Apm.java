package com.fc.freer.call.engine;

import com.fc.fc_ajdk.utils.TimberLogger;

/**
 * The app's own echo canceller (VOICE_SPEC §11.1): WebRTC's audio processing
 * module, AEC3 with noise suppression, adaptive digital gain and a high-pass
 * filter, at the call's 48 kHz mono.
 * <p>
 * For phones whose own voice processing goes half-duplex on the loudspeaker,
 * cutting one side off whenever both talk. The playout engine hands it every
 * tick it plays ({@link #render}); the capture engine runs every frame through
 * it before encoding ({@link #capture}). The microphone then opens without the
 * platform's processing: two echo cancellers in series fight.
 * <p>
 * Render and capture run on their own threads; the module locks each side.
 */
public final class Apm implements AutoCloseable {

    private static final String TAG = "Apm";
    /** Samples in the module's 10 ms frame. */
    public static final int FRAME_SAMPLES = Opus.SAMPLE_RATE / 100;
    /** A guess at play-to-capture delay until the devices report theirs; AEC3 finds the true one. */
    static final int DEFAULT_DELAY_MS = 80;

    static {
        System.loadLibrary("freervoice");
    }

    private static native long nativeCreate(int sampleRate, boolean noiseSuppression, boolean gainControl);

    private static native void nativeRender(long h, short[] pcm, int count);

    private static native void nativeCapture(long h, short[] pcm, int count, int delayMs);

    private static native int[] nativeStats(long h);

    private static native void nativeRelease(long h);

    private volatile long h;

    private Apm(long h) {
        this.h = h;
    }

    /** @return null if the module could not be created: the call goes on with the platform's processing */
    public static Apm create() {
        try {
            long h = nativeCreate(Opus.SAMPLE_RATE, true, true);
            return h == 0 ? null : new Apm(h);
        } catch (UnsatisfiedLinkError e) {
            TimberLogger.e(TAG, "no echo canceller in this build: %s", e.getMessage());
            return null;
        }
    }

    /** What is about to be played; {@code count} a multiple of {@link #FRAME_SAMPLES}. From the playout thread. */
    public void render(short[] pcm, int count) {
        long x = h;
        if (x != 0) nativeRender(x, pcm, count);
    }

    /**
     * Take the echo out of what was captured, in place; {@code count} a
     * multiple of {@link #FRAME_SAMPLES}. From the capture thread.
     *
     * @param delayMs a rough play-to-capture delay
     */
    public void capture(short[] pcm, int count, int delayMs) {
        long x = h;
        if (x != 0) nativeCapture(x, pcm, count, delayMs);
    }

    /** For the call's log: echo return loss and enhancement, and the delay it found. */
    public String describe() {
        long x = h;
        if (x == 0) return "off";
        int[] s = nativeStats(x);
        return String.format(java.util.Locale.US, "ERL %s dB, ERLE %s dB, delay %s ms",
                s[0] < 0 ? "?" : String.valueOf(s[0] / 100.0), s[1] < 0 ? "?" : String.valueOf(s[1] / 100.0),
                s[2] < 0 ? "?" : String.valueOf(s[2]));
    }

    /** After the capture and playout engines have stopped. */
    @Override
    public synchronized void close() {
        long x = h;
        h = 0;
        if (x != 0) nativeRelease(x);
    }
}
