package com.fc.freer.onboarding;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * FEIP9 {@code Home}: the map of services a FID can be reached through.
 * <p>
 * <b>{@code register} replaces the whole map</b>, so a carve that only meant to set the DOCK
 * would erase every other entry. {@link #merged} therefore lays changes over the map the chain
 * holds <i>now</i>, read fresh: a cached copy missing an entry added elsewhere would erase it.
 */
public final class HomeFeip {
    private HomeFeip() {}

    /**
     * {@code stored} with {@code changes} laid over it. A null or blank change leaves that entry
     * as it is. Returns null when nothing would change: a carve for that costs a fee to say
     * nothing.
     */
    public static Map<String, String> merged(Map<String, String> stored, Map<String, String> changes) {
        return merged(stored, changes, java.util.Set.of());
    }

    /** As {@link #merged(Map, Map)}, also dropping the {@code removals} keys, e.g. to stop taking calls. */
    public static Map<String, String> merged(Map<String, String> stored, Map<String, String> changes,
                                             java.util.Set<String> removals) {
        Map<String, String> base = stored != null ? stored : new HashMap<>();
        Map<String, String> merged = new HashMap<>(base);
        if (removals != null) merged.keySet().removeAll(removals);
        if (changes != null) {
            for (Map.Entry<String, String> change : changes.entrySet()) {
                String value = change.getValue() != null ? change.getValue().trim() : "";
                if (change.getKey() == null || value.isEmpty()) continue;
                merged.put(change.getKey(), value);
            }
        }
        if (merged.isEmpty() || merged.equals(base)) return null;
        return merged;
    }

    /**
     * Whether {@code home} names a service of {@code kind} ("DOCK", "DISK") with a non-blank
     * value. Keys match by prefix, ignoring case: a map written by another client may use a
     * bare {@code DOCK} key, and that FID is still reachable.
     */
    public static boolean declares(String kind, Map<String, String> home) {
        if (home == null || kind == null) return false;
        String prefix = kind.toUpperCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : home.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key != null && key.toUpperCase(Locale.ROOT).startsWith(prefix)
                    && value != null && !value.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
