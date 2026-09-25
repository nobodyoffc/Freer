package com.fc.freer.call;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Relays this phone has reached, with the key and service id discovery
 * found: the next call to the same relay connects with them, over FUDP's
 * retransmitted handshake, instead of HELLO/PING discovery, whose packets
 * are never resent and fail on a lossy path (VOICE_SPEC §6.2 step 1). A
 * relay's key is public; the list only says which relays were used, and
 * stays in the app's private storage.
 */
interface KnownRelays {

    /** @return {pubkeyHex, sid}, or null */
    String[] get(String url);

    void put(String url, String pubkeyHex, String sid);

    void forget(String url);

    static KnownRelays of(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("call_relays", Context.MODE_PRIVATE);
        return new KnownRelays() {
            @Override
            public String[] get(String url) {
                String v = prefs.getString(url, null);
                int bar = v == null ? -1 : v.indexOf('|');
                return bar <= 0 || bar == v.length() - 1 ? null : new String[]{v.substring(0, bar), v.substring(bar + 1)};
            }

            @Override
            public void put(String url, String pubkeyHex, String sid) {
                if (url != null && pubkeyHex != null && sid != null) {
                    prefs.edit().putString(url, pubkeyHex + "|" + sid).apply();
                }
            }

            @Override
            public void forget(String url) {
                prefs.edit().remove(url).apply();
            }
        };
    }
}
