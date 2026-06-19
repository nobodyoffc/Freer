package com.fc.freer.im.voice;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import timber.log.Timber;

import java.io.File;
import java.io.IOException;

/**
 * Wraps MediaRecorder for voice message recording.
 * Records AAC audio at 16kHz mono, max 5 minutes.
 */
public class VoiceRecorder {

    public static final int MAX_DURATION_MS = 300_000; // 5 minutes
    public static final int MIN_DURATION_MS = 500;     // minimum valid recording
    private static final int SAMPLE_RATE = 16000;
    private static final int BIT_RATE = 64000;

    private MediaRecorder recorder;
    private File outputFile;
    private long startTimeMs;
    private boolean recording;
    private final Context context;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private TimerCallback timerCallback;

    public interface TimerCallback {
        void onTimerUpdate(long elapsedMs);
        void onMaxDurationReached();
    }

    public VoiceRecorder(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setTimerCallback(TimerCallback callback) {
        this.timerCallback = callback;
    }

    /**
     * Start recording to a temp file.
     * @return true if recording started successfully
     */
    public boolean start() {
        if (recording) return false;

        File voiceDir = new File(context.getCacheDir(), "voice");
        if (!voiceDir.exists()) voiceDir.mkdirs();
        outputFile = new File(voiceDir, "voice_" + System.currentTimeMillis() + ".m4a");

        try {
            recorder = new MediaRecorder(context);
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(SAMPLE_RATE);
            recorder.setAudioChannels(1);
            recorder.setAudioEncodingBitRate(BIT_RATE);
            recorder.setMaxDuration(MAX_DURATION_MS);
            recorder.setOutputFile(outputFile.getAbsolutePath());

            recorder.setOnInfoListener((mr, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    if (timerCallback != null) timerCallback.onMaxDurationReached();
                }
            });

            recorder.prepare();
            recorder.start();
            startTimeMs = System.currentTimeMillis();
            recording = true;
            startTimerUpdates();
            return true;
        } catch (IOException | IllegalStateException e) {
            Timber.e(e, "Failed to start voice recording");
            releaseRecorder();
            if (outputFile != null && outputFile.exists()) outputFile.delete();
            return false;
        }
    }

    /**
     * Stop recording and return the result.
     * @return result with audio file and duration, or null if recording was too short or failed
     */
    public VoiceRecordResult stop() {
        if (!recording) return null;

        long durationMs = System.currentTimeMillis() - startTimeMs;
        stopTimerUpdates();

        // If recording is too short, MediaRecorder.stop() throws RuntimeException
        // because no audio frames have been written yet. Treat as "too short".
        if (durationMs < MIN_DURATION_MS) {
            releaseRecorder();
            recording = false;
            if (outputFile != null && outputFile.exists()) outputFile.delete();
            return null;
        }

        try {
            recorder.stop();
        } catch (RuntimeException e) {
            Timber.w(e, "Recorder stop failed (likely too short)");
            releaseRecorder();
            if (outputFile != null && outputFile.exists()) outputFile.delete();
            recording = false;
            return null;
        }

        releaseRecorder();
        recording = false;

        return new VoiceRecordResult(outputFile, durationMs, SAMPLE_RATE);
    }

    /**
     * Cancel recording and discard the file.
     */
    public void cancel() {
        if (!recording) return;
        stopTimerUpdates();

        try {
            recorder.stop();
        } catch (RuntimeException ignored) {
        }

        releaseRecorder();
        recording = false;

        if (outputFile != null && outputFile.exists()) outputFile.delete();
    }

    public boolean isRecording() {
        return recording;
    }

    public long getElapsedMs() {
        if (!recording) return 0;
        return System.currentTimeMillis() - startTimeMs;
    }

    public void release() {
        if (recording) cancel();
        releaseRecorder();
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
    }

    private void startTimerUpdates() {
        timerHandler.post(new Runnable() {
            @Override
            public void run() {
                if (recording && timerCallback != null) {
                    timerCallback.onTimerUpdate(getElapsedMs());
                    timerHandler.postDelayed(this, 200);
                }
            }
        });
    }

    private void stopTimerUpdates() {
        timerHandler.removeCallbacksAndMessages(null);
    }

    /**
     * Result of a voice recording.
     */
    public static class VoiceRecordResult {
        public final File audioFile;
        public final long durationMs;
        public final int sampleRate;

        public VoiceRecordResult(File audioFile, long durationMs, int sampleRate) {
            this.audioFile = audioFile;
            this.durationMs = durationMs;
            this.sampleRate = sampleRate;
        }
    }
}
