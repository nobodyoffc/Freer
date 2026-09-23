package com.fc.freer.call.engine;

/**
 * Keeps captured audio from queueing up before the encoder. If the capture
 * thread ever falls behind (a slow start, a GC pause), the samples it missed
 * stay queued for the rest of the call: it then reads at exactly real time,
 * so the queue never drains, and every frame is sent that much late.
 * <p>
 * The capture loop takes everything already queued after each frame, so the
 * queue is visible. What stays queued even at its lowest over a window is a
 * standing backlog, not burstiness, and all of it but one frame is dropped.
 */
final class MicBacklog {

    static final int WINDOW_MS = 1_000;

    private final int frameSamples;
    private long windowStartMs = -1;
    private int windowMin = Integer.MAX_VALUE;
    private int lastMinSamples;
    private long droppedSamples;

    MicBacklog(int frameSamples) {
        this.frameSamples = frameSamples;
    }

    /**
     * @param queued samples still queued after taking this frame
     * @return samples to discard now: whole frames, oldest first
     */
    int observe(int queued, long nowMs) {
        if (windowStartMs < 0) windowStartMs = nowMs;
        windowMin = Math.min(windowMin, queued);
        if (nowMs - windowStartMs < WINDOW_MS) return 0;
        int standing = windowMin;
        lastMinSamples = standing;
        windowStartMs = nowMs;
        windowMin = Integer.MAX_VALUE;
        int drop = (standing - frameSamples) / frameSamples * frameSamples; // keep one frame of slack
        if (drop <= 0) return 0;
        droppedSamples += drop;
        return drop;
    }

    /** The standing queue over the last full window, ms. */
    int queueMs() {
        return lastMinSamples * 1000 / Opus.SAMPLE_RATE;
    }

    long droppedMs() {
        return droppedSamples * 1000 / Opus.SAMPLE_RATE;
    }
}
