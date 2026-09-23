package com.fc.freer.call.engine;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;

import com.fc.fc_ajdk.utils.TimberLogger;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Send side (VOICE_SPEC §9.1, §9.2): {@code AudioRecord} with the
 * VOICE_COMMUNICATION source, which gives the platform's echo cancellation,
 * noise suppression and gain control, then Opus, then one {@link SpikeFrame}
 * per frame to the {@link Sink}.
 */
public final class CaptureEngine {

    private static final String TAG = "CaptureEngine";
    /** Quieter than -50 dBov counts as silence for the VAD flag. */
    static final int VAD_LEVEL = 50;

    public interface Sink {
        void send(byte[] frame);
    }

    public record Settings(int frameMs, int bitrate, boolean dtx, int expectedLossPercent) {}

    private final Settings settings;
    private final int frameSamples;
    private final Sink sink;
    private final int ssrc;
    private volatile boolean running;
    private volatile boolean muted;
    private volatile int level = 127;
    private volatile long framesSent, dtxSkipped, bytesSent;
    private volatile String effects = "";
    private Thread thread;
    private AudioRecord record;
    private Opus.Encoder encoder;

    public CaptureEngine(Settings settings, Sink sink) {
        this.settings = settings;
        this.frameSamples = Opus.SAMPLE_RATE / 1000 * settings.frameMs();
        this.sink = sink;
        this.ssrc = new SecureRandom().nextInt(); // fresh on every join (§4.3)
    }

    public int ssrc() {
        return ssrc;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
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

    /** Which platform effects are active, for the test screen. */
    public String effects() {
        return effects;
    }

    /** Needs RECORD_AUDIO, which the caller has checked. */
    @SuppressLint("MissingPermission")
    public synchronized void start() {
        if (running) return;
        int minBytes = AudioRecord.getMinBufferSize(Opus.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, Opus.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBytes, 4 * frameSamples * 2));
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            record = null;
            throw new IllegalStateException("AudioRecord failed to initialise");
        }
        effects = enableEffects(record.getAudioSessionId());
        encoder = new Opus.Encoder(settings.bitrate(), settings.dtx(), settings.expectedLossPercent());
        running = true;
        record.startRecording();
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
        if (record != null) {
            record.stop();
            record.release();
            record = null;
        }
        if (encoder != null) {
            encoder.close();
            encoder = null;
        }
    }

    /**
     * VOICE_COMMUNICATION usually applies these already; asking for them
     * explicitly makes the result visible, since some devices don't.
     */
    private static String enableEffects(int session) {
        StringBuilder sb = new StringBuilder();
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler aec = AcousticEchoCanceler.create(session);
            if (aec != null && aec.setEnabled(true) == 0) sb.append("AEC ");
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor ns = NoiseSuppressor.create(session);
            if (ns != null && ns.setEnabled(true) == 0) sb.append("NS ");
        }
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl agc = AutomaticGainControl.create(session);
            if (agc != null && agc.setEnabled(true) == 0) sb.append("AGC ");
        }
        return sb.length() == 0 ? "none" : sb.toString().trim();
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] pcm = new short[frameSamples];
        byte[] packet = new byte[Opus.MAX_PACKET];
        long seq = 0;
        boolean afterDtx = false;
        long timestamp = new SecureRandom().nextInt() & 0xffffffffL; // random start per ssrc (§5)
        while (running) {
            int got = 0;
            while (got < frameSamples && running) {
                int n = record.read(pcm, got, frameSamples - got);
                if (n < 0) {
                    TimberLogger.e(TAG, "AudioRecord.read failed: %d", n);
                    running = false;
                    return;
                }
                got += n;
            }
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
            int flags = (lvl <= VAD_LEVEL ? SpikeFrame.FLAG_VAD : 0) | (afterDtx ? SpikeFrame.FLAG_DTX : 0);
            afterDtx = false;
            SpikeFrame f = new SpikeFrame(flags, ssrc, thisSeq, thisTs, lvl, Arrays.copyOf(packet, len));
            byte[] wire = f.encode();
            sink.send(wire);
            framesSent++;
            bytesSent += wire.length;
        }
    }
}
