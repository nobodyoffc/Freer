package com.fc.freer.im.voice;

import android.util.Base64;

import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.ImType;

import timber.log.Timber;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Orchestrates building voice ImMessages from recording results.
 * Handles both inline (small) and HAT (large) voice messages.
 */
public class VoiceMessageHelper {

    /**
     * Build an ImMessage from a voice recording result.
     * For recordings under MAX_INLINE_DATA_SIZE, creates an inline VOICE message.
     * For larger recordings, returns null (caller should use FileShareHelper for HAT upload).
     *
     * @return ImMessage with ContentType.VOICE, or null if file is too large for inline
     */
    public static ImMessage buildVoiceMessage(VoiceRecorder.VoiceRecordResult result,
                                               ImType imType, String senderId, String targetId) {
        File audioFile = result.audioFile;

        if (audioFile.length() > ImMessage.MAX_INLINE_DATA_SIZE) {
            // Caller should handle HAT upload for large files
            return null;
        }

        byte[] audioBytes = readFile(audioFile);
        if (audioBytes == null) return null;

        String dataBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP);
        String metaJson = buildMetaJson(result.durationMs, result.sampleRate);

        // Clean up temp file after encoding
        audioFile.delete();

        return ImMessage.createVoice(imType, senderId, targetId, metaJson, dataBase64);
    }

    /**
     * Build metadata JSON for voice message content field.
     */
    public static String buildMetaJson(long durationMs, int sampleRate) {
        return String.format(Locale.US,
                "{\"durationMs\":%d,\"sampleRate\":%d,\"format\":\"aac\"}",
                durationMs, sampleRate);
    }

    /**
     * Format duration in milliseconds to "M:SS" string.
     */
    public static String formatDuration(long ms) {
        long totalSecs = ms / 1000;
        long mins = totalSecs / 60;
        long secs = totalSecs % 60;
        return String.format(Locale.getDefault(), "%d:%02d", mins, secs);
    }

    /**
     * Parse durationMs from voice message content JSON.
     */
    public static long parseDurationMs(String contentJson) {
        if (contentJson == null) return 0;
        try {
            // Simple parsing without pulling in a JSON library dependency
            int idx = contentJson.indexOf("\"durationMs\":");
            if (idx < 0) return 0;
            int start = idx + "\"durationMs\":".length();
            int end = start;
            while (end < contentJson.length() && (Character.isDigit(contentJson.charAt(end)))) {
                end++;
            }
            return Long.parseLong(contentJson.substring(start, end));
        } catch (Exception e) {
            Timber.w(e, "Failed to parse voice duration");
            return 0;
        }
    }

    private static byte[] readFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = 0;
            while (read < data.length) {
                int n = fis.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            return data;
        } catch (IOException e) {
            Timber.e(e, "Failed to read audio file");
            return null;
        }
    }
}
