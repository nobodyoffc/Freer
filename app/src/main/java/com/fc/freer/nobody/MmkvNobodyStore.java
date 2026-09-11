package com.fc.freer.nobody;

import com.tencent.mmkv.MMKV;

import java.util.HashSet;
import java.util.Set;

/**
 * Persists known nobodies across restarts. Global rather than per identity:
 * whether a key was published is a fact of the chain, not of the user.
 */
final class MmkvNobodyStore implements NobodyRegistry.Store {

    private static final String MMKV_ID = "nobody_registry";
    private static final String NOBODY_PREFIX = "n:";
    private static final String ALERTED_PREFIX = "a:";

    private final MMKV mmkv = MMKV.mmkvWithID(MMKV_ID);

    @Override
    public Set<String> loadNobodies() {
        Set<String> fids = new HashSet<>();
        String[] keys = mmkv.allKeys();
        if (keys == null) return fids;
        for (String key : keys) {
            if (key.startsWith(NOBODY_PREFIX)) fids.add(key.substring(NOBODY_PREFIX.length()));
        }
        return fids;
    }

    @Override
    public void saveNobody(String fid) {
        mmkv.encode(NOBODY_PREFIX + fid, true);
    }

    @Override
    public boolean isAlerted(String fid) {
        return mmkv.decodeBool(ALERTED_PREFIX + fid, false);
    }

    @Override
    public void setAlerted(String fid) {
        mmkv.encode(ALERTED_PREFIX + fid, true);
    }
}
