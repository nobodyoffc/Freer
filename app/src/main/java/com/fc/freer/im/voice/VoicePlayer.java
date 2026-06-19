package com.fc.freer.im.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import timber.log.Timber;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Singleton voice message player. Only one voice plays at a time.
 */
public class VoicePlayer {

    private static VoicePlayer instance;

    private MediaPlayer player;
    private String currentMessageId;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private PlaybackListener listener;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private Context appContext;

    public interface PlaybackListener {
        void onProgress(String messageId, int currentMs, int totalMs);
        void onComplete(String messageId);
        void onError(String messageId);
    }

    private VoicePlayer() {
    }

    public static synchronized VoicePlayer getInstance() {
        if (instance == null) {
            instance = new VoicePlayer();
        }
        return instance;
    }

    public void init(Context context) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
    }

    public void setListener(PlaybackListener listener) {
        this.listener = listener;
    }

    /**
     * Play audio from raw bytes (decoded from Base64).
     */
    public void play(String messageId, byte[] audioData) {
        File tempFile = writeTempFile(audioData);
        if (tempFile != null) {
            play(messageId, tempFile);
        } else if (listener != null) {
            listener.onError(messageId);
        }
    }

    /**
     * Play audio from a file.
     */
    public void play(String messageId, File audioFile) {
        stop();

        if (!requestAudioFocus()) {
            Timber.w("Failed to get audio focus");
        }

        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            player.setDataSource(audioFile.getAbsolutePath());
            player.prepare();
            player.start();
            currentMessageId = messageId;

            player.setOnCompletionListener(mp -> {
                String id = currentMessageId;
                stopInternal();
                if (listener != null) listener.onComplete(id);
            });

            player.setOnErrorListener((mp, what, extra) -> {
                String id = currentMessageId;
                stopInternal();
                if (listener != null) listener.onError(id);
                return true;
            });

            startProgressUpdates();
        } catch (IOException e) {
            Timber.e(e, "Failed to play voice message");
            stopInternal();
            if (listener != null) listener.onError(messageId);
        }
    }

    public void pause() {
        if (player != null && player.isPlaying()) {
            player.pause();
            stopProgressUpdates();
        }
    }

    public void resume() {
        if (player != null && !player.isPlaying() && currentMessageId != null) {
            player.start();
            startProgressUpdates();
        }
    }

    public void stop() {
        stopInternal();
    }

    public void seekTo(int positionMs) {
        if (player != null) {
            player.seekTo(positionMs);
        }
    }

    public boolean isPlaying(String messageId) {
        return messageId != null && messageId.equals(currentMessageId)
                && player != null && player.isPlaying();
    }

    public boolean isPaused(String messageId) {
        return messageId != null && messageId.equals(currentMessageId)
                && player != null && !player.isPlaying();
    }

    public String getCurrentMessageId() {
        return currentMessageId;
    }

    public int getCurrentPosition() {
        if (player != null && currentMessageId != null) {
            try {
                return player.getCurrentPosition();
            } catch (IllegalStateException e) {
                return 0;
            }
        }
        return 0;
    }

    public int getDuration() {
        if (player != null && currentMessageId != null) {
            try {
                return player.getDuration();
            } catch (IllegalStateException e) {
                return 0;
            }
        }
        return 0;
    }

    public void release() {
        stopInternal();
        instance = null;
    }

    private void stopInternal() {
        stopProgressUpdates();
        abandonAudioFocus();
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        currentMessageId = null;
    }

    private void startProgressUpdates() {
        progressHandler.post(new Runnable() {
            @Override
            public void run() {
                if (player != null && player.isPlaying() && listener != null) {
                    try {
                        listener.onProgress(currentMessageId,
                                player.getCurrentPosition(), player.getDuration());
                    } catch (IllegalStateException ignored) {
                    }
                    progressHandler.postDelayed(this, 100);
                }
            }
        });
    }

    private void stopProgressUpdates() {
        progressHandler.removeCallbacksAndMessages(null);
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) return false;
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build())
                .build();
        return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (audioManager != null && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
    }

    private File writeTempFile(byte[] data) {
        try {
            File dir = new File(appContext.getCacheDir(), "voice");
            if (!dir.exists()) dir.mkdirs();
            File temp = new File(dir, "play_" + System.currentTimeMillis() + ".m4a");
            try (FileOutputStream fos = new FileOutputStream(temp)) {
                fos.write(data);
            }
            return temp;
        } catch (IOException e) {
            Timber.e(e, "Failed to write temp audio file");
            return null;
        }
    }
}
