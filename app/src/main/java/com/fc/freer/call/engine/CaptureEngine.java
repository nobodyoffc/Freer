package com.fc.freer.call.engine;

import android.os.Process;

import com.fc.fc_ajdk.utils.TimberLogger;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Send side (VOICE_SPEC §9.1, §9.2): the voice-call input, which gives the
 * platform's echo cancellation, noise suppression and gain control, through
 * {@link AudioIo} (AudioRecord or AAudio), then Opus, then one
 * {@link EncodedFrame} per frame to the {@link Sink}, which frames it for the
 * wire.
 */
public final class CaptureEngine {

    private static final String TAG = "CaptureEngine";
    /** Quieter than -50 dBov counts as silence for the VAD flag. */
    static final int VAD_LEVEL = 50;
    /** The frame header on the wire (§5), for the bitrate the screen shows. */
    private static final int HEADER_ESTIMATE = 24;

    public interface Sink {
        void send(EncodedFrame frame);
    }

    public record Settings(int frameMs, int bitrate, boolean dtx, int expectedLossPercent, AudioIo.Backend backend) {}

    /** The recorder's read size: every frame length is a multiple of it. */
    private static final int QUANTUM_SAMPLES = Opus.SAMPLE_RATE / 1000 * 20;
    private static final int MAX_FRAME_SAMPLES = Opus.SAMPLE_RATE / 1000 * 60;

    private final Settings settings;
    /** 20, 40 or 60; takes effect at the next frame. */
    private volatile int frameMs;
    private final Sink sink;
    private final int ssrc;
    private volatile boolean running;
    private volatile boolean muted;
    private volatile int level = 127;
    private volatile long framesSent, dtxSkipped, bytesSent;
    private volatile MicBacklog backlog;
    private Thread thread;
    private volatile AudioIo.Input input;
    private Opus.Encoder encoder;
    /** The app's own echo canceller, or null for the platform's processing. */
    private volatile Apm apm;
    private volatile int echoDelayMs = Apm.DEFAULT_DELAY_MS;

    public CaptureEngine(Settings settings, Sink sink) {
        this.settings = settings;
        this.frameMs = checkFrameMs(settings.frameMs());
        this.sink = sink;
        this.ssrc = new SecureRandom().nextInt(); // fresh on every join (§4.3)
    }

    public int ssrc() {
        return ssrc;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    private static int checkFrameMs(int ms) {
        if (ms != 20 && ms != 40 && ms != 60) throw new IllegalArgumentException("frame length " + ms + " ms");
        return ms;
    }

    /**
     * Live change of frame length (§9.1), from the next frame on. Receivers
     * read each packet's length, so nothing else needs telling; seq and the
     * timestamp run on.
     */
    public void setFrameMs(int ms) {
        frameMs = checkFrameMs(ms);
    }

    public int frameMs() {
        return frameMs;
    }

    /** Live change, as the network adaptation of §9.4 will do. */
    public void setBitrate(int bitsPerSecond) {
        Opus.Encoder e = encoder;
        if (e != null) e.setBitrate(bitsPerSecond);
    }

    public int level() {
        return level;
    }

    public long framesSent() {
        return framesSent;
    }

    public long dtxSkipped() {
        return dtxSkipped;
    }

    public long bytesSent() {
        return bytesSent;
    }

    /**
     * Audio captured but not yet encoded that never drains, ms: a standing
     * backlog adds that much to every frame. Above one frame it is dropped
     * ({@link #micDroppedMs}). -1 before the first second.
     */
    public int micQueueMs() {
        MicBacklog b = backlog;
        return b == null ? -1 : b.queueMs();
    }

    /** Audio dropped to clear a standing backlog, ms. */
    public long micDroppedMs() {
        MicBacklog b = backlog;
        return b == null ? 0 : b.droppedMs();
    }

    /** Which platform effects are active, for the test screen. */
    public String effects() {
        AudioIo.Input in = input;
        return in == null ? "" : in.effects();
    }

    public String describe() {
        AudioIo.Input in = input;
        return in == null ? "" : in.describe();
    }

    /**
     * Mic to app, ms, from the device's capture timestamps; -1 if it gives
     * none. Some devices stamp at read time, which reads as ~0.
     */
    public int inputLatencyMs() {
        AudioIo.Input in = input;
        return in == null ? -1 : in.latencyMs();
    }

    /**
     * Cancel echo with the app's own {@link Apm} instead of the platform's
     * voice processing, which the microphone then opens without. Before
     * {@link #start}.
     */
    public void setEchoCanceller(Apm apm) {
        this.apm = apm;
    }

    /** Play-to-capture delay, as the devices report it: a hint for the echo canceller. */
    public void setEchoDelayMs(int ms) {
        if (ms > 0) echoDelayMs = ms;
    }

    /** Needs RECORD_AUDIO, which the caller has checked. */
    public synchronized void start() {
        if (running) return;
        input = AudioIo.openInput(settings.backend(), QUANTUM_SAMPLES, apm == null);
        encoder = new Opus.Encoder(settings.bitrate(), settings.dtx(), settings.expectedLossPercent());
        running = true;
        thread = new Thread(this::loop, "voice-capture");
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        if (input != null) {
            input.close();
            input = null;
        }
        if (encoder != null) {
            encoder.close();
            encoder = null;
        }
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] pcm = new short[MAX_FRAME_SAMPLES];
        short[] chunk = new short[8 * QUANTUM_SAMPLES];
        SampleFifo fifo = new SampleFifo(16 * MAX_FRAME_SAMPLES);
        MicBacklog mic = null;
        byte[] packet = new byte[Opus.MAX_PACKET];
        long seq = 0;
        boolean afterDtx = false;
        long timestamp = new SecureRandom().nextInt() & 0xffffffffL; // random start per ssrc (§5)
        while (running) {
            int frameSamples = Opus.SAMPLE_RATE / 1000 * frameMs;
            if (mic == null || mic.frameSamples() != frameSamples) {
                mic = new MicBacklog(frameSamples); // it keeps one frame of slack, so it follows the length
                backlog = mic;
            }
            // Wait for one frame, then take whatever else is already queued, so
            // the queue lives in our FIFO where it can be measured.
            while (fifo.size() < frameSamples && running) {
                if (!readInto(fifo, chunk, frameSamples - fifo.size(), true)) return;
            }
            if (!readInto(fifo, chunk, Math.min(chunk.length, fifo.free()), false)) return;
            fifo.pop(pcm, frameSamples);
            int drop = mic.observe(fifo.size(), android.os.SystemClock.elapsedRealtime());
            if (drop > 0) fifo.drop(drop); // the dropped audio is simply never sent; seq and timestamp run on
            Apm echo = apm;
            if (echo != null) echo.capture(pcm, frameSamples, echoDelayMs); // every frame, muted or not: it keeps learning

            if (muted) Arrays.fill(pcm, (short) 0);
            int lvl = SpikeFrame.level(pcm, frameSamples);
            level = lvl;

            int len = encoder.encode(pcm, frameSamples, packet);
            long thisSeq = seq++;
            long thisTs = timestamp;
            timestamp = (timestamp + frameSamples) & 0xffffffffL;
            if (len <= 2) {
                // DTX: nothing worth sending. The receiver's buffer pauses and
                // restarts at the next frame (JitterBuffer talk spurts).
                dtxSkipped++;
                afterDtx = true;
                continue;
            }
            EncodedFrame f = new EncodedFrame(ssrc, thisSeq, thisTs, lvl, lvl <= VAD_LEVEL, afterDtx,
                    Arrays.copyOf(packet, len));
            afterDtx = false;
            sink.send(f);
            framesSent++;
            bytesSent += len + HEADER_ESTIMATE;
        }
    }

    /** @return false if the recorder failed */
    private boolean readInto(SampleFifo fifo, short[] chunk, int want, boolean block) {
        if (want <= 0) return true;
        int n = input.read(chunk, 0, want, block);
        if (n < 0) {
            TimberLogger.e(TAG, "audio read failed: %d", n);
            running = false;
            return false;
        }
        fifo.push(chunk, n);
        return true;
    }

    /** A ring of 16-bit samples. Only the capture thread uses it. */
    static final class SampleFifo {
        private final short[] buf;
        private int head, size;

        SampleFifo(int capacity) {
            buf = new short[capacity];
        }

        int size() {
            return size;
        }

        int free() {
            return buf.length - size;
        }

        void push(short[] src, int n) {
            for (int i = 0; i < n; i++) buf[(head + size + i) % buf.length] = src[i];
            size += n;
        }

        void pop(short[] dst, int n) {
            for (int i = 0; i < n; i++) dst[i] = buf[(head + i) % buf.length];
            drop(n);
        }

        void drop(int n) {
            n = Math.min(n, size);
            head = (head + n) % buf.length;
            size -= n;
        }
    }
}
