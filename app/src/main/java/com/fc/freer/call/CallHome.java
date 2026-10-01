package com.fc.freer.call;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.fapi.client.HomeServiceResolver;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The CALL entry of a Room's or Team's {@code home}: the relay its meetings
 * run on (VOICE_SPEC §8). A screen states the CALL it means and this lays it
 * over the stored map, so the entries it does not draw survive.
 */
public final class CallHome {

    private CallHome() {}

    /** Whether {@code key} names a service of {@code kind}: the bare kind or kind@anything, any case. */
    public static boolean isKeyOf(String key, String kind) {
        if (key == null) return false;
        String u = key.toUpperCase(Locale.ROOT), k = kind.toUpperCase(Locale.ROOT);
        return u.equals(k) || u.startsWith(k + "@");
    }

    /**
     * {@code stored} with its CALL set to {@code input}, or removed when
     * {@code input} is blank. Every other spelling of the CALL key goes, so
     * members find one value. A service id is written {@code (sid)<id>}.
     */
    public static Map<String, String> withCall(Map<String, String> stored, String input) {
        Map<String, String> home = new HashMap<>();
        if (stored != null) {
            for (Map.Entry<String, String> e : stored.entrySet()) {
                if (!isKeyOf(e.getKey(), "CALL")) home.put(e.getKey(), e.getValue());
            }
        }
        String value = input == null ? "" : input.trim();
        if (!value.isEmpty()) {
            String sid = HomeServiceResolver.isUrl(value) ? null : HomeServiceResolver.extractSid(value);
            home.put(Constants.CALL_NO1_NRC7, sid != null ? "(sid)" + sid : value);
        }
        return home;
    }

    /** The CALL entry of {@code home} as a form shows it: a bare service id, or the URL; "" if none. */
    public static String display(Map<String, String> home) {
        String value = home == null ? null : home.get(Constants.CALL_NO1_NRC7);
        if (value == null || value.trim().isEmpty()) return "";
        String sid = HomeServiceResolver.isUrl(value) ? null : HomeServiceResolver.extractSid(value);
        return sid != null ? sid : value.trim();
    }
}
