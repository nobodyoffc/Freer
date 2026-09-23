package com.fc.freer.call;

import android.content.Context;

import com.fc.fc_ajdk.call.CallSignal;
import com.fc.fc_ajdk.data.fcData.ContentType;
import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.freer.R;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;

/**
 * What a CALL entry says in the chat and the message requests (VOICE_SPEC §10):
 * "Call, 4:12", "Missed call", "Declined". The entry is a local record, or a
 * stranger's INVITE that was held as a request and later let through.
 */
public final class CallText {

    private CallText() {}

    public static boolean isCall(ImMessage m) {
        return m != null && m.getContentType() == ContentType.CALL;
    }

    public static String describe(Context ctx, ImMessage m) {
        String content = m.getContent();
        JsonObject o;
        try {
            o = content == null ? null : JsonParser.parseString(content).getAsJsonObject();
        } catch (RuntimeException e) {
            o = null;
        }
        if (o == null) return ctx.getString(R.string.call_record_call);
        if (o.has("op")) {
            // A held INVITE: the stranger's call was never answered.
            return CallSignal.Op.INVITE.name().equals(o.get("op").getAsString())
                    ? ctx.getString(R.string.call_record_missed) : ctx.getString(R.string.call_record_call);
        }
        String kind = o.has("record") ? o.get("record").getAsString() : "";
        boolean outgoing = o.has("outgoing") && o.get("outgoing").getAsBoolean();
        long duration = o.has("duration") ? o.get("duration").getAsLong() : 0;
        return switch (kind) {
            case "ENDED" -> ctx.getString(outgoing ? R.string.call_record_outgoing : R.string.call_record_incoming,
                    formatDuration(duration));
            case "MISSED" -> ctx.getString(R.string.call_record_missed);
            case "DECLINED" -> ctx.getString(R.string.call_record_declined);
            case "NO_ANSWER" -> ctx.getString(R.string.call_record_no_answer);
            case "BUSY" -> ctx.getString(R.string.call_record_busy);
            case "CANCELLED" -> ctx.getString(R.string.call_record_cancelled);
            case "ANSWERED_ELSEWHERE" -> ctx.getString(R.string.call_record_answered_elsewhere);
            default -> ctx.getString(R.string.call_record_call);
        };
    }

    /** 4:12, or 1:02:03 past an hour. */
    static String formatDuration(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
