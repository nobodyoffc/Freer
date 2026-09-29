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
 * blocking write, one 20 ms tick per iteration.
 * <p>
 * Each sender picks its own frame length, 20, 40 or 60 ms, and may change it
 * mid-call (§9.1). A stream learns it from each packet's TOC byte, decodes a
 * frame when the last one is used up, and plays it out over as many ticks.
 * A change of length restarts the stream's jitter buffer, since the buffer
 * counts in frames.
 */
public final class PlayoutEngine {

    private static final String TAG = "PlayoutEngine";
    /** A stream silent this long is dropped, decoder and all. Long: DTX silences are normal. */
    private static final long STREAM_IDLE_MS = 120_000;
    /** Mix above this is compressed rather than clipped. */
    private static final int KNEE = 24_576;

    /** The output's period. Every frame length a sender may use is a multiple of it. */
    public static final int TICK_MS = 20;
    private static final int TICK_SAMPLES = Opus.SAMPLE_RATE / 1000 * TICK_MS;

    public static final class Stream {
        public final int ssrc;
        public final JitterBuffer buffer;
        final int frameSamples;
        final Opus.Decoder decoder;
        volatile int level = 127;
        /** The decoded frame being played out, and how much of it is played. */
        final short[] pcm;
        int pcmLength, pcmPlayed;

        Stream(int ssrc, int frameSamples, Opus.Decoder decoder) {
            this.ssrc = ssrc;
            this.frameSamples = frameSamples;
            this.buffer = new JitterBuffer(frameSamples * 1000 / Opus.SAMPLE_RATE);
            this.decoder = decoder;
            this.pcm = new short[frameSamples];
        }

        /** Last level heard, -dBov (127 = silence). */
        public int level() {
            return level;
        }
    }

    private final AudioIo.Backend backend;
    private final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
    private final java.util.Set<Integer> silenced = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private Thread thread;
    private volatile AudioIo.Output output;
    private volatile int outputLatencyMs = -1;
    private volatile long wrongFrameSize;
    /** The app's own echo canceller, told what is played; null for the platform's. */
    private volatile Apm apm;

    public PlayoutEngine(AudioIo.Backend backend) {
        this.backend = backend;
    }

    /** From the network thread: queue a frame. Must return quickly. */
    public void onFrame(EncodedFrame f, long arrivalMs) {
        if (silenced.contains(f.ssrc())) return;
        int samples = OpusToc.samples(f.opus());
        if (samples <= 0 || samples % TICK_SAMPLES != 0) {
            wrongFrameSize++; // 2.5, 5 or 10 ms: no sender of ours uses those
            return;
        }
        Stream s = streams.compute(f.ssrc(), (id, old) -> {
            if (old == null) return new Stream(id, samples, new Opus.Decoder());
            // The sender changed its frame length: the same decoder, a new buffer.
            return old.frameSamples == samples ? old : new Stream(id, samples, old.decoder);
        });
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

    /** Frames of a length we cannot play (not a multiple of 20 ms), or that decoded to another length. */
    public long wrongFrameSize() {
        return wrongFrameSize;
    }

    /** Tell {@code apm} every tick that is played: the echo it is to take out of the microphone. */
    public void setEchoCanceller(Apm apm) {
        this.apm = apm;
    }

    public synchronized void start() {
        if (running) return;
        output = AudioIo.openOutput(backend, TICK_SAMPLES);
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
        int[] mix = new int[TICK_SAMPLES];
        short[] out = new short[TICK_SAMPLES];
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
                if (s.pcmPlayed >= s.pcmLength && !decodeNext(s, now)) continue;
                for (int i = 0; i < TICK_SAMPLES; i++) mix[i] += s.pcm[s.pcmPlayed + i];
                s.pcmPlayed += TICK_SAMPLES;
            }
            for (int i = 0; i < TICK_SAMPLES; i++) out[i] = softClip(mix[i]);
            Apm echo = apm;
            if (echo != null) echo.render(out, TICK_SAMPLES);
            try {
                // Blocks until there is room: this is what paces the loop.
                output.write(out, 0, TICK_SAMPLES);
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

    /**
     * Decode the stream's next frame into {@code s.pcm}: the jitter buffer is
     * asked once per frame of the stream's length, not once per tick.
     * @return false if there is nothing to play this tick
     */
    private boolean decodeNext(Stream s, long now) {
        s.pcmPlayed = s.pcmLength = 0;
        JitterBuffer.Pull p = s.buffer.pull(now);
        int n;
        try {
            n = switch (p.kind()) {
                case NOTHING -> 0;
                case FRAME -> s.decoder.decode(p.data(), s.pcm, s.frameSamples);
                case FEC -> s.decoder.decodeFec(p.data(), s.pcm, s.frameSamples);
                case CONCEAL -> s.decoder.conceal(s.pcm, s.frameSamples);
            };
        } catch (IllegalStateException e) {
            TimberLogger.w(TAG, "decode failed for ssrc %d: %s", s.ssrc, e.getMessage());
            return false;
        }
        if (n == 0) return false;
        if (n != s.frameSamples) {
            wrongFrameSize++;
            return false;
        }
        s.pcmLength = n;
        return true;
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
