package com.fc.freer.call.engine;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

/**
 * Where the call's audio comes from and goes to (VOICE_SPEC §9.2): the Java
 * AudioRecord/AudioTrack path, or AAudio through the NDK. Both use the
 * platform's voice-call settings; AAudio buffers less.
 */
public final class AudioIo {

    public enum Backend { JAVA, AAUDIO, AAUDIO_EXCLUSIVE }

    public interface Input extends AutoCloseable {
        /** @return samples read (0 if none were ready without blocking), or negative on error */
        int read(short[] buf, int off, int n, boolean block);

        /** Sound to samples, ms, from the device's timestamps; -1 if it gives none. */
        int latencyMs();

        String effects();

        String describe();

        @Override
        void close();
    }

    public interface Output extends AutoCloseable {
        /** Blocks until all of it is queued: this paces the playout loop. */
        void write(short[] buf, int off, int n);

        /** Our buffer in the output stream, ms. */
        int bufferMs();

        /** Times it ran dry, each of which grew {@link #bufferMs}. */
        int underruns();

        /** Written to heard, ms, from the device's timestamps; -1 if it gives none. */
        int latencyMs();

        String describe();

        @Override
        void close();
    }

    private AudioIo() {}

    public static Input openInput(Backend backend, int frameSamples) {
        return openInput(backend, frameSamples, true);
    }

    /**
     * @param platformEffects the platform's voice processing (VOICE_COMMUNICATION);
     *                        false when the app cancels echo itself (VOICE_RECOGNITION)
     */
    public static Input openInput(Backend backend, int frameSamples, boolean platformEffects) {
        return backend == Backend.JAVA ? new JavaInput(frameSamples, platformEffects)
                : new AAudio.Input(backend == Backend.AAUDIO_EXCLUSIVE, platformEffects);
    }

    public static Output openOutput(Backend backend, int frameSamples) {
        return backend == Backend.JAVA ? new JavaOutput(frameSamples)
                : new AAudio.Output(backend == Backend.AAUDIO_EXCLUSIVE);
    }

    /** AudioRecord with the VOICE_COMMUNICATION source, or VOICE_RECOGNITION under the app's own echo canceller. */
    static final class JavaInput implements Input {
        private final AudioRecord record;
        private final String effects;
        private final AudioTimestamp ts = new AudioTimestamp();
        private long samplesRead;

        @SuppressLint("MissingPermission") // the caller has checked RECORD_AUDIO
        JavaInput(int frameSamples, boolean platformEffects) {
            int minBytes = AudioRecord.getMinBufferSize(Opus.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord(platformEffects ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    : MediaRecorder.AudioSource.VOICE_RECOGNITION, Opus.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBytes, 4 * frameSamples * 2));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                throw new IllegalStateException("AudioRecord failed to initialise");
            }
            effects = describeEffects(record.getAudioSessionId());
            record.startRecording();
        }

        /**
         * Which of the platform's software effects the capture session has,
         * and whether the VOICE_COMMUNICATION preset turned them on, for the
         * log. They are never turned on here: in call mode the device's own
         * (often hardware) echo canceller already runs, and a second echo
         * canceller and noise suppressor stacked after it cut the local
         * speaker off whenever both sides talk, as the A05s did on speaker.
         */
        static String describeEffects(int session) {
            if (session <= 0) return "preset only";
            StringBuilder sb = new StringBuilder();
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler e = AcousticEchoCanceler.create(session);
                    if (e != null) {
                        sb.append("AEC ").append(e.getEnabled() ? "on " : "off ");
                        e.release();
                    }
                }
                if (NoiseSuppressor.isAvailable()) {
                    NoiseSuppressor e = NoiseSuppressor.create(session);
                    if (e != null) {
                        sb.append("NS ").append(e.getEnabled() ? "on " : "off ");
                        e.release();
                    }
                }
                if (AutomaticGainControl.isAvailable()) {
                    AutomaticGainControl e = AutomaticGainControl.create(session);
                    if (e != null) {
                        sb.append("AGC ").append(e.getEnabled() ? "on " : "off ");
                        e.release();
                    }
                }
            } catch (RuntimeException ignored) {
                // an effect the device lists but will not open: nothing to report
            }
            return sb.length() == 0 ? "preset only" : sb.toString().trim();
        }

        @Override
        public int read(short[] buf, int off, int n, boolean block) {
            int r = record.read(buf, off, n, block ? AudioRecord.READ_BLOCKING : AudioRecord.READ_NON_BLOCKING);
            if (r > 0) samplesRead += r;
            return r;
        }

        @Override
        public int latencyMs() {
            if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) return -1;
            long capturedNs = ts.nanoTime + (samplesRead - ts.framePosition) * 1_000_000_000L / Opus.SAMPLE_RATE;
            return (int) ((System.nanoTime() - capturedNs) / 1_000_000);
        }

        @Override
        public String effects() {
            return effects;
        }

        @Override
        public String describe() {
            return "AudioRecord";
        }

        @Override
        public void close() {
            record.stop();
            record.release();
        }
    }

    /** A low-latency AudioTrack, using two frames of its buffer and growing on underrun. */
    static final class JavaOutput implements Output {
        private final AudioTrack track;
        private final int frameSamples;
        private final AudioTimestamp ts = new AudioTimestamp();
        private long written;
        private int underruns;

        JavaOutput(int frameSamples) {
            this.frameSamples = frameSamples;
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
                    .setBufferSizeInBytes(Math.max(minBytes, 2 * frameSamples * 2))
                    .build();
            // The voice path's minimum buffer is often 80-100 ms. Use two frames of it.
            track.setBufferSizeInFrames(Math.min(track.getBufferCapacityInFrames(), 2 * frameSamples));
            track.play();
        }

        @Override
        public void write(short[] buf, int off, int n) {
            track.write(buf, off, n);
            written += n;
            int u = track.getUnderrunCount();
            if (u > underruns) {
                underruns = u;
                track.setBufferSizeInFrames(Math.min(track.getBufferCapacityInFrames(),
                        track.getBufferSizeInFrames() + frameSamples / 2));
            }
        }

        @Override
        public int bufferMs() {
            return track.getBufferSizeInFrames() * 1000 / Opus.SAMPLE_RATE;
        }

        @Override
        public int underruns() {
            return underruns;
        }

        @Override
        public int latencyMs() {
            if (!track.getTimestamp(ts)) return -1;
            return (int) ((written - ts.framePosition) * 1000 / Opus.SAMPLE_RATE
                    - (System.nanoTime() - ts.nanoTime) / 1_000_000);
        }

        @Override
        public String describe() {
            return "AudioTrack";
        }

        @Override
        public void close() {
            track.stop();
            track.release();
        }
    }
}
