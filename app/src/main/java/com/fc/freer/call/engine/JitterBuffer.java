package com.fc.freer.call.engine;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.TreeMap;

/**
 * Adaptive jitter buffer for one incoming stream (VOICE_SPEC §9.3). It holds
 * encoded frames and tells the playout loop, once per frame period, what to
 * play: a frame, the FEC copy carried by the next frame, or concealment. It
 * does no decoding, so it can be tested without the codec.
 *
 * <ul>
 *   <li><b>Target delay:</b> the 95th percentile of arrival jitter over the
 *   last 2 s, kept between 40 and 300 ms. Jitter is each frame's transit
 *   time (arrival minus media timestamp) above the window's fastest.</li>
 *   <li><b>Shrinking:</b> while the buffer is more than two frames over
 *   target, by skipping a quiet frame: one the sender marked silent, or 15 dB
 *   below the speaker's recent loudest (the gaps between syllables). If a
 *   second passes over target without one, a frame is dropped anyway, at most
 *   one per 200 ms: continuous speech would otherwise keep the extra delay
 *   until the speaker paused.</li>
 *   <li><b>Growing:</b> when the next frame is late and nothing after it has
 *   arrived, play concealment and wait for it instead of skipping it.</li>
 *   <li><b>Lost frame:</b> recovered from the next frame's FEC when that is
 *   already here, concealed otherwise.</li>
 *   <li><b>Late frame:</b> one that arrives after its turn is discarded.</li>
 *   <li><b>Talk spurts:</b> after a pause (DTX, or the sender stopped),
 *   playout restarts at the first new frame behind a fresh target delay.</li>
 * </ul>
 *
 * Thread-safe: {@link #put} runs on the network thread, {@link #pull} on the
 * playout thread.
 */
public final class JitterBuffer {

    public enum Kind { NOTHING, FRAME, FEC, CONCEAL }

    /** What to play this period. {@code data} is the frame (FRAME) or the next frame (FEC). */
    public record Pull(Kind kind, byte[] data) {
        static final Pull NOTHING = new Pull(Kind.NOTHING, null);
        static final Pull CONCEAL = new Pull(Kind.CONCEAL, null);
    }

    public static final int MIN_TARGET_MS = 40;
    public static final int MAX_TARGET_MS = 300;
    static final int WINDOW_MS = 2_000;
    /** Longer than this without a frame is a pause: the next frame starts a new spurt. */
    static final int PAUSE_MS = 200;
    /** A frame this many dB below the speaker's recent loudest counts as quiet. */
    static final int QUIET_DB = 15;
    /** Over target this long with no quiet frame to skip: drop one anyway. */
    static final int FORCE_AFTER_MS = 1_000;
    static final int FORCE_GAP_MS = 200;

    private record Entry(byte[] data, boolean silent, boolean afterDtx, int level) {}

    private final int frameMs;
    private final TreeMap<Long, Entry> frames = new TreeMap<>();
    /** (arrivalMs, transitMs) of recent frames, oldest first. */
    private final ArrayDeque<long[]> window = new ArrayDeque<>();

    private boolean playing;
    /** Next frame to play. Kept across pauses, so a stale frame never starts a spurt. */
    private long nextSeq = Long.MIN_VALUE;
    private long maxSeq = -1;
    private long spurtStartMs;
    private long lastArrivalMs;
    private int waitedFrames;
    /** Level (-dBov) of the speaker's recent loudest frames; decays towards silence. */
    private double loudest = 127;
    private long overSinceMs = -1, lastForcedMs = Long.MIN_VALUE / 2;

    // Counters, for the UI and tests.
    private long received, played, fecRecovered, concealed, dtxGap, late, skipped, forced, stretched, duplicates;

    public JitterBuffer(int frameMs) {
        this.frameMs = frameMs;
    }

    /**
     * @param timestamp media timestamp, 48 kHz samples
     * @param silent    true for a quiet frame, which is safe to skip
     * @param afterDtx  the sender skipped the frames before this one as DTX
     * @param level     audio level, -dBov (0 loudest, 127 silence)
     */
    public synchronized void put(long seq, long timestamp, byte[] data, boolean silent, boolean afterDtx,
                                 int level, long arrivalMs) {
        if (seq < nextSeq) {
            late++;
            return;
        }
        if (frames.putIfAbsent(seq, new Entry(data, silent, afterDtx, level)) != null) {
            duplicates++;
            return;
        }
        received++;
        // Follow the speaker's loudest: jump up at once, fall back ~2.5 dB/s.
        loudest = level < loudest ? level : Math.min(127, loudest + 0.05);
        if (seq > maxSeq) maxSeq = seq;
        if (frames.size() == 1 && !playing) spurtStartMs = arrivalMs;
        lastArrivalMs = arrivalMs;

        window.addLast(new long[]{arrivalMs, arrivalMs - timestamp / (Opus.SAMPLE_RATE / 1000)});
        while (!window.isEmpty() && window.peekFirst()[0] < arrivalMs - WINDOW_MS) window.pollFirst();
    }

    /** Called once per frame period by the playout loop. */
    public synchronized Pull pull(long nowMs) {
        if (!playing) {
            if (frames.isEmpty()) return Pull.NOTHING;
            // Start a spurt behind the target delay, or sooner if that much is already queued.
            long firstSeq = frames.firstKey();
            boolean queued = (maxSeq - firstSeq + 1) * frameMs >= targetMs();
            if (!queued && nowMs - spurtStartMs < targetMs()) return Pull.NOTHING;
            playing = true;
            nextSeq = firstSeq;
            waitedFrames = 0;
        }

        // Shrink: more than two frames over target. Skip a quiet frame, or after
        // a second over target with none, any frame.
        long depth = maxSeq - nextSeq + 1;
        long targetFrames = (targetMs() + frameMs - 1) / frameMs;
        boolean over = depth > targetFrames + 2;
        if (!over) overSinceMs = -1;
        else if (overSinceMs < 0) overSinceMs = nowMs;
        Entry head = frames.get(nextSeq);
        if (over && head != null && frames.containsKey(nextSeq + 1)) {
            boolean quiet = head.silent || head.level >= loudest + QUIET_DB;
            boolean force = nowMs - overSinceMs >= FORCE_AFTER_MS && nowMs - lastForcedMs >= FORCE_GAP_MS;
            if (quiet || force) {
                frames.remove(nextSeq++);
                skipped++;
                if (!quiet) {
                    forced++;
                    lastForcedMs = nowMs;
                }
                head = frames.get(nextSeq);
            }
        }
        // Far over the ceiling, whatever the content: catch up.
        long maxFrames = MAX_TARGET_MS / frameMs + 2;
        if (depth > maxFrames) {
            long to = maxSeq - targetFrames + 1;
            skipped += frames.headMap(to).size();
            frames.headMap(to).clear();
            nextSeq = to;
            head = frames.get(nextSeq);
        }

        if (head != null) {
            frames.remove(nextSeq++);
            stretched += waitedFrames; // the wait paid off: the delay grew by that much
            waitedFrames = 0;
            played++;
            return new Pull(Kind.FRAME, head.data);
        }

        // Missing. Nothing newer has arrived: it is late, or the sender paused.
        if (frames.isEmpty()) {
            if (nowMs - lastArrivalMs > PAUSE_MS) {
                // A pause: stop, and let the next frame start a new spurt. The
                // wait was the end of speech, not growth, so it is not counted.
                playing = false;
                waitedFrames = 0;
                return Pull.NOTHING;
            }
            if (waitedFrames * frameMs < MAX_TARGET_MS) {
                // Grow: conceal and wait for it rather than skip it. Counted in
                // `stretched` once the wait ends in the frame or its loss.
                waitedFrames++;
                return Pull.CONCEAL;
            }
        }

        // Not sent: the next frame says the sender was in DTX. Silence, not loss.
        var following = frames.ceilingEntry(nextSeq);
        if (following != null && following.getValue().afterDtx) {
            nextSeq++;
            waitedFrames = 0; // waiting through DTX grew nothing
            dtxGap++;
            return Pull.CONCEAL;
        }

        // Lost: something after it is here (or we waited long enough).
        nextSeq++;
        stretched += waitedFrames;
        waitedFrames = 0;
        Entry next = frames.get(nextSeq);
        if (next != null) {
            fecRecovered++;
            return new Pull(Kind.FEC, next.data);
        }
        concealed++;
        return Pull.CONCEAL;
    }

    /** Current target delay, ms. */
    public synchronized int targetMs() {
        if (window.size() < 2) return MIN_TARGET_MS;
        long min = Long.MAX_VALUE;
        for (long[] w : window) min = Math.min(min, w[1]);
        long[] jitter = new long[window.size()];
        int i = 0;
        for (long[] w : window) jitter[i++] = w[1] - min;
        Arrays.sort(jitter);
        long p95 = jitter[(int) Math.ceil(0.95 * jitter.length) - 1];
        return (int) Math.max(MIN_TARGET_MS, Math.min(MAX_TARGET_MS, p95));
    }

    /** Frames queued ahead of playout, in ms. */
    public synchronized int depthMs() {
        return playing ? (int) Math.max(0, (maxSeq - nextSeq + 1) * frameMs) : frames.size() * frameMs;
    }

    public synchronized long lastArrivalMs() {
        return lastArrivalMs;
    }

    public synchronized Stats stats() {
        return new Stats(received, played, fecRecovered, concealed, dtxGap, late, skipped, forced, stretched,
                duplicates, targetMs(), depthMs());
    }

    /**
     * {@code concealed} and {@code fecRecovered} are frames lost; {@code dtxGap}
     * frames were never sent; {@code stretched} is concealment played while
     * waiting for a late frame (growing the delay). {@code skipped} frames were
     * dropped to shrink it; {@code forced} of them were not quiet.
     */
    public record Stats(long received, long played, long fecRecovered, long concealed, long dtxGap, long late,
                        long skipped, long forced, long stretched, long duplicates, int targetMs, int depthMs) {

        /** Lost frames over frames that should have arrived, in percent. */
        public double lossPercent() {
            long lost = fecRecovered + concealed;
            long expected = played + lost;
            return expected == 0 ? 0 : 100.0 * lost / expected;
        }
    }
}
