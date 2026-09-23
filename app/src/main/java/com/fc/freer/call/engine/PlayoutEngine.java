package com.fc.freer.call.engine;

import android.media.AudioAttributes;
import android.media.AudioTimestamp;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;
import android.os.SystemClock;

import com.fc.fc_ajdk.utils.TimberLogger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receive side (VOICE_SPEC §9.2, §9.3): a jitter buffer and decoder per
 * incoming {@code ssrc}, mixed and played through one {@link AudioTrack}.
 * The playout thread is paced by the track's blocking write, one frame per
 * iteration.
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
    private final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
    private volatile boolean running;
    private Thread thread;
    private AudioTrack track;
    private volatile int trackBufferMs;
    private volatile int underruns;
    private volatile int outputLatencyMs = -1;
    private long framesWritten;
    private volatile long wrongFrameSize;

    public PlayoutEngine(int frameMs) {
        this.frameMs = frameMs;
        this.frameSamples = Opus.SAMPLE_RATE / 1000 * frameMs;
    }

    /** From the network thread: queue a frame. Must return quickly. */
    public void onFrame(SpikeFrame f, long arrivalMs) {
        Stream s = streams.computeIfAbsent(f.ssrc(), id -> new Stream(id, frameMs));
        s.level = f.level();
        s.buffer.put(f.seq(), f.timestamp(), f.opus(), !f.voiceActive(), f.afterDtx(), f.level(), arrivalMs);
    }

    public Map<Integer, Stream> streams() {
        return streams;
    }

    /** Playout buffer in the AudioTrack, ms: part of mouth-to-ear delay. */
    public int trackBufferMs() {
        return trackBufferMs;
    }

    /**
     * Written to heard: from handing a frame to the AudioTrack to the moment
     * the device reports presenting it. Covers our buffer, the platform mixer,
     * DSP and driver. -1 until the device reports a timestamp.
     */
    public int outputLatencyMs() {
        return outputLatencyMs;
    }

    /** Times the AudioTrack ran dry, each of which grew {@link #trackBufferMs}. */
    public int underruns() {
        return underruns;
    }

    /** Frames decoded to a length other than our frame size: the peer uses another frame size. */
    public long wrongFrameSize() {
        return wrongFrameSize;
    }

    public synchronized void start() {
        if (running) return;
        int minBytes = AudioTrack.getMinBufferSize(Opus.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(Opus.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                // Two frames: enough to ride out scheduling, no more.
                .setBufferSizeInBytes(Math.max(minBytes, 2 * frameSamples * 2))
                .build();
        // The voice path's minimum buffer is often 80-100 ms, the largest fixed
        // delay in the call. Use only two frames of it, and grow on underrun.
        track.setBufferSizeInFrames(Math.min(track.getBufferCapacityInFrames(), 2 * frameSamples));
        trackBufferMs = track.getBufferSizeInFrames() * 1000 / Opus.SAMPLE_RATE;
        running = true;
        track.play();
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
        if (track != null) {
            track.stop();
            track.release();
            track = null;
        }
        for (Stream s : streams.values()) s.decoder.close();
        streams.clear();
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] pcm = new short[frameSamples];
        int[] mix = new int[frameSamples];
        short[] out = new short[frameSamples];
        AudioTimestamp ts = new AudioTimestamp();
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
            // Blocks until there is room: this is what paces the loop.
            track.write(out, 0, frameSamples);
            framesWritten += frameSamples;
            growOnUnderrun();
            if (++ticks % 25 == 0) measureOutputLatency(ts);
        }
    }

    /**
     * The device presented frame {@code ts.framePosition} at {@code ts.nanoTime};
     * the last frame written will be presented that many frames later.
     */
    private void measureOutputLatency(AudioTimestamp ts) {
        if (!track.getTimestamp(ts)) return;
        long queuedFrames = framesWritten - ts.framePosition;
        long sinceNs = System.nanoTime() - ts.nanoTime;
        outputLatencyMs = (int) (queuedFrames * 1000 / Opus.SAMPLE_RATE - sinceNs / 1_000_000);
    }

    /** The track ran dry: give it half a frame more, up to what it holds. */
    private void growOnUnderrun() {
        int n = track.getUnderrunCount();
        if (n <= underruns) return;
        underruns = n;
        int size = Math.min(track.getBufferCapacityInFrames(), track.getBufferSizeInFrames() + frameSamples / 2);
        track.setBufferSizeInFrames(size);
        trackBufferMs = track.getBufferSizeInFrames() * 1000 / Opus.SAMPLE_RATE;
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
