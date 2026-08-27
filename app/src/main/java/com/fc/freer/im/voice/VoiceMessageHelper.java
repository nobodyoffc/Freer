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
     *
     * <p>Returns null when the recording will not fit inline, and the caller
     * must then upload it to a DISK and share it as a HAT. That fallback is
     * permanent: the real ceiling is whatever the destination DOCK advertises
     * (see {@link ImMessage#ASSUMED_DOCK_ITEM_LIMIT}), so no content type --
     * voice included -- can be assumed to always travel inline.
     *
     * @param inlineBudget the destination's resolved per-item ceiling in bytes.
     *                     Pass {@link ImMessage#ASSUMED_DOCK_ITEM_LIMIT} when it
     *                     is not known; do NOT hard-code a larger number.
     * @return ImMessage with ContentType.VOICE, or null if it must go to DISK
     */
    public static ImMessage buildVoiceMessage(VoiceRecorder.VoiceRecordResult result,
                                               ImType imType, String senderId, String targetId,
                                               int inlineBudget) {
        File audioFile = result.audioFile;

        // Measured against the audio alone, with room left for the envelope,
        // the body framing and the seal. The authoritative check is on the
        // encoded envelope at send time; this one only avoids building a
        // message that is certain to be refused.
        if (audioFile.length() > inlineBudget - WIRE_OVERHEAD_ALLOWANCE) {
            // Caller should handle HAT upload for large files
            return null;
        }

        byte[] audioBytes = readFile(audioFile);
        if (audioBytes == null) return null;

        String metaJson = buildMetaJson(result.durationMs, result.sampleRate);

        // Clean up temp file after encoding
        audioFile.delete();

        return ImMessage.createVoice(imType, senderId, targetId, metaJson, audioBytes);
    }

    /**
     * Headroom left for the envelope header, the body framing and the seal.
     * The AsyTwoWay bundle alone is 52 bytes; the rest is ids, timestamps and
     * the voice metadata JSON.
     */
    private static final int WIRE_OVERHEAD_ALLOWANCE = 1024;

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
