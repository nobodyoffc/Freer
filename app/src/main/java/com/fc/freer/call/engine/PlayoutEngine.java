package com.fc.freer.call.engine;

import android.os.Process;
import android.os.SystemClock;

import com.fc.fc_ajdk.utils.TimberLogger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receive side (VOICE_SPEC §9.2, §9.3): a jitter buffer and decoder per
 * incoming {@code ssrc}, mixed and played through one {@link AudioIo.Output}
 * (AudioTrack or AAudio). The playout thread is paced by the output's
 * blocking write, one frame per iteration.
 */
public final class PlayoutEngine {

    private static final String TAG = "PlayoutEngine";
    /** A stream silent this long is dropped, decoder and all. Long: DTX silences are normal. */
    private static final long STREAM_IDLE_MS = 120_000;
    /** Mix above this is compressed rather than clipped. */
    private static final int KNEE = 24_576;

    public static final class Stream {
        public final int ssrc;
        public final JitterBuffer buffer;
        final Opus.Decoder decoder = new Opus.Decoder();
        volatile int level = 127;

        Stream(int ssrc, int frameMs) {
            this.ssrc = ssrc;
            this.buffer = new JitterBuffer(frameMs);
        }

        /** Last level heard, -dBov (127 = silence). */
        public int level() {
            return level;
        }
    }

    private final int frameMs;
    private final int frameSamples;
    private final AudioIo.Backend backend;
    private final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
    private final java.util.Set<Integer> silenced = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private Thread thread;
    private volatile AudioIo.Output output;
    private volatile int outputLatencyMs = -1;
    private volatile long wrongFrameSize;

    public PlayoutEngine(int frameMs, AudioIo.Backend backend) {
        this.frameMs = frameMs;
        this.frameSamples = Opus.SAMPLE_RATE / 1000 * frameMs;
        this.backend = backend;
    }

    /** From the network thread: queue a frame. Must return quickly. */
    public void onFrame(EncodedFrame f, long arrivalMs) {
        if (silenced.contains(f.ssrc())) return;
        Stream s = streams.computeIfAbsent(f.ssrc(), id -> new Stream(id, frameMs));
        s.level = f.level();
        s.buffer.put(f.seq(), f.timestamp(), f.opus(), !f.voiceActive(), f.afterDtx(), f.level(), arrivalMs);
    }

    /** Stop playing one stream for good: its audio could not be attributed (§5.1). */
    public void silence(int ssrc) {
        Stream s = streams.remove(ssrc);
        if (s != null) s.decoder.close();
        silenced.add(ssrc);
    }

    public Map<Integer, Stream> streams() {
        return streams;
    }

    /** Our buffer in the output stream, ms: part of mouth-to-ear delay. */
    public int trackBufferMs() {
        AudioIo.Output o = output;
        return o == null ? 0 : o.bufferMs();
    }

    /** Times the output ran dry, each of which grew {@link #trackBufferMs}. */
    public int underruns() {
        AudioIo.Output o = output;
        return o == null ? 0 : o.underruns();
    }

    /**
     * Written to heard: from handing a frame to the output to the moment the
     * device reports presenting it. Covers our buffer, the platform mixer,
     * DSP and driver. -1 until the device reports a timestamp.
     */
    public int outputLatencyMs() {
        return outputLatencyMs;
    }

    public String describe() {
        AudioIo.Output o = output;
        return o == null ? "" : o.describe();
    }

    /** Frames decoded to a length other than our frame size: the peer uses another frame size. */
    public long wrongFrameSize() {
        return wrongFrameSize;
    }

    public synchronized void start() {
        if (running) return;
        output = AudioIo.openOutput(backend, frameSamples);
        running = true;
        thread = new Thread(this::loop, "voice-playout");
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        if (output != null) {
            output.close();
            output = null;
        }
        for (Stream s : streams.values()) s.decoder.close();
        streams.clear();
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] pcm = new short[frameSamples];
        int[] mix = new int[frameSamples];
        short[] out = new short[frameSamples];
        int ticks = 0;
        while (running) {
            long now = SystemClock.elapsedRealtime();
            java.util.Arrays.fill(mix, 0);
            for (Stream s : streams.values()) {
                if (now - s.buffer.lastArrivalMs() > STREAM_IDLE_MS) {
                    streams.remove(s.ssrc);
                    s.decoder.close();
                    continue;
                }
                JitterBuffer.Pull p = s.buffer.pull(now);
                int n;
                try {
                    n = switch (p.kind()) {
                        case NOTHING -> 0;
                        case FRAME -> s.decoder.decode(p.data(), pcm, frameSamples);
                        case FEC -> s.decoder.decodeFec(p.data(), pcm, frameSamples);
                        case CONCEAL -> s.decoder.conceal(pcm, frameSamples);
                    };
                } catch (IllegalStateException e) {
                    TimberLogger.w(TAG, "decode failed for ssrc %d: %s", s.ssrc, e.getMessage());
                    n = 0;
                }
                if (n != 0 && n != frameSamples) {
                    wrongFrameSize++;
                    continue;
                }
                for (int i = 0; i < n; i++) mix[i] += pcm[i];
            }
            for (int i = 0; i < frameSamples; i++) out[i] = softClip(mix[i]);
            try {
                // Blocks until there is room: this is what paces the loop.
                output.write(out, 0, frameSamples);
            } catch (IllegalStateException e) {
                TimberLogger.e(TAG, "playout stopped: %s", e.getMessage());
                running = false;
                return;
            }
            if (++ticks % 25 == 0) {
                int l = output.latencyMs();
                if (l >= 0) outputLatencyMs = l;
            }
        }
    }

    /** Linear below the knee, compressed smoothly towards full scale above it. */
    static short softClip(int x) {
        int a = Math.abs(x);
        if (a <= KNEE) return (short) x;
        int room = Short.MAX_VALUE - KNEE;
        int over = a - KNEE;
        int y = KNEE + (int) ((long) over * room / (over + room));
        return (short) (x < 0 ? -y : y);
    }
}
