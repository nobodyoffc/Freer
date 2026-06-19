package com.fc.fc_ajdk.fapi.client;

import static com.fc.fc_ajdk.constants.Constants.DOCK_NO1_NRC7;

import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.TimberLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves home map values (e.g. home.DOCK@No1_NrC7) into usable URLs.
 * <p>
 * Home values come in 3 formats:
 * <ol>
 *   <li>Direct URL: http(s)://... or fudp://...</li>
 *   <li>Bare SID: 64 hex characters</li>
 *   <li>Prefixed SID: (sid)64hex</li>
 * </ol>
 * For SID formats, we fetch service info and return service.urls.API.
 */
public class HomeServiceResolver {
    private static final String TAG = "HomeServiceResolver";
    private static final String SID_PREFIX = "(sid)";
    private static final int SID_HEX_LENGTH = 64;
    private static final String URL_KEY = "API";
    private final Map<String, String> sidToUrlCache = new ConcurrentHashMap<>();
    private final Map<String, Service> sidToServiceCache = new ConcurrentHashMap<>();

    /**
     * Resolve a single home value to a URL.
     *
     * @param homeValue The value from the entity's home map
     * @param fapiClient FapiClient for SID lookups (may be null)
     * @return Resolved URL, or null if unresolvable
     */
    public String resolve(String homeValue, FapiClient fapiClient) {
        if (homeValue == null || homeValue.isEmpty()) {
            return null;
        }

        if (isUrl(homeValue)) {
            return homeValue;
        }

        String sid = extractSid(homeValue);
        if (sid == null) {
            return null;
        }

        String cached = sidToUrlCache.get(sid);
        if (cached != null) {
            return cached;
        }

        if (fapiClient == null) {
            return null;
        }

        try {
            Service service = fapiClient.serviceById(sid);
            if (service != null) {
                sidToServiceCache.put(sid, service);
                String url = homeApiUrl(service);
                if (url != null) {
                    TimberLogger.d(TAG, "Resolved SID %s to URL: %s", sid, url);
                    sidToUrlCache.put(sid, url);
                    return url;
                }
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to resolve SID %s: %s", sid, e.getMessage());
        }

        return null;
    }

    /**
     * Batch-resolve multiple home values. Collects all SIDs needing lookup
     * and resolves them in a single serviceByIds call.
     *
     * @param homeValues Map of key -> homeValue to resolve
     * @param fapiClient FapiClient for SID lookups
     * @return Map of key -> resolved URL (entries with null URLs are omitted)
     */
    public Map<String, String> resolveBatch(Map<String, String> homeValues, FapiClient fapiClient) {
        Map<String, String> result = new HashMap<>();
        if (homeValues == null || homeValues.isEmpty()) {
            return result;
        }

        List<String> sidsToFetch = new ArrayList<>();
        Map<String, String> keyToSid = new HashMap<>();

        for (Map.Entry<String, String> entry : homeValues.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isEmpty()) continue;

            if (isUrl(value)) {
                result.put(key, value);
            } else {
                String sid = extractSid(value);
                if (sid != null) {
                    String cached = sidToUrlCache.get(sid);
                    if (cached != null) {
                        result.put(key, cached);
                    } else {
                        keyToSid.put(key, sid);
                        if (!sidsToFetch.contains(sid)) {
                            sidsToFetch.add(sid);
                        }
                    }
                }
            }
        }

        if (!sidsToFetch.isEmpty() && fapiClient != null) {
            try {
                Map<String, Service> services = fapiClient.serviceByIds(sidsToFetch);
                if (services != null) {
                    for (Map.Entry<String, Service> svcEntry : services.entrySet()) {
                        Service service = svcEntry.getValue();
                        if (service != null) {
                            sidToServiceCache.put(svcEntry.getKey(), service);
                            String url = homeApiUrl(service);
                            if (url != null) {
                                sidToUrlCache.put(svcEntry.getKey(), url);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                TimberLogger.w(TAG, "Batch SID resolve failed: %s", e.getMessage());
            }

            for (Map.Entry<String, String> entry : keyToSid.entrySet()) {
                String url = sidToUrlCache.get(entry.getValue());
                if (url != null) {
                    result.put(entry.getKey(), url);
                }
            }
        }

        return result;
    }

    /**
     * Resolve a service URL from a home map by key prefix.
     * Matches keys equal to the prefix (e.g. "ROAD") or starting with prefix + "@" (e.g. "ROAD@No1_NrC7").
     *
     * @param home The entity's home map
     * @param theKey The service key prefix (e.g. "DOCK", "ROAD", "DISK", "MAP", "FUDP")
     * @param fapiClient FapiClient for SID lookups
     * @return Resolved URL, or null
     */
    public String resolveFromHome(Map<String, String> home, String theKey, FapiClient fapiClient) {
        if (home == null || theKey == null) return null;

        for (Map.Entry<String, String> entry : home.entrySet()) {
            String key = entry.getKey();
            if (key != null && (key.equals(theKey))) {
                String url = resolve(entry.getValue(), fapiClient);
                if (url != null) return url;
            }
        }
        return null;
    }

    /**
     * Resolve a DOCK URL from a home map, trying known DOCK key patterns.
     *
     * @param home The entity's home map
     * @param fapiClient FapiClient for SID lookups
     * @return Resolved DOCK URL, or null
     */
    public String resolveDockFromHome(Map<String, String> home, FapiClient fapiClient) {
        return resolveFromHome(home, DOCK_NO1_NRC7, fapiClient);
    }

    /**
     * Get a previously fetched Service by its SID from the cache.
     *
     * @param sid The service ID (64 hex chars)
     * @return Cached Service, or null if not previously fetched
     */
    public Service getCachedService(String sid) {
        if (sid == null) return null;
        return sidToServiceCache.get(sid);
    }

    /**
     * Clear all caches.
     */
    public void clearCache() {
        sidToUrlCache.clear();
        sidToServiceCache.clear();
    }

    /**
     * Check if a value is a URL (http, https, or fudp scheme).
     */
    public static boolean isUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://") || value.startsWith("fudp://");
    }

    /**
     * Extract SID from a home value. Returns null if not a valid SID format.
     * Handles bare SID (64 hex) and prefixed SID ((sid)64hex).
     */
    public static String extractSid(String value) {
        if (value == null) return null;

        String candidate;
        if (value.startsWith(SID_PREFIX)) {
            candidate = value.substring(SID_PREFIX.length());
        } else {
            candidate = value;
        }

        if (candidate.length() == SID_HEX_LENGTH && isHex(candidate)) {
            return candidate;
        }
        return null;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Read a service's API endpoint from its {@code home} map, tolerating key-case differences
     * ("API" vs "api"). Services may register the URL under either spelling.
     */
    private static String homeApiUrl(Service service) {
        Map<String, String> home = service != null ? service.getHome() : null;
        if (home == null || home.isEmpty()) return null;
        String exact = home.get(URL_KEY);
        if (exact != null) return exact;
        for (Map.Entry<String, String> e : home.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(URL_KEY)) {
                return e.getValue();
            }
        }
        return null;
    }
}
