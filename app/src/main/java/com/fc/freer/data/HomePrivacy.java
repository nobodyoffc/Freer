package com.fc.freer.data;

import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.fapi.client.HomeServiceResolver;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;

import java.util.Locale;
import java.util.Map;

/**
 * Home entries only their owner can read: {@code home.BASE} and {@code home.DISK}.
 * <p>
 * <b>Only those two.</b> A DOCK and a CALL service exist to be found — a sender has to reach the
 * DOCK, a caller the relay — so sealing either would only make the FID unreachable. Where you
 * read the chain and where your files live are nobody else's business, and some people would
 * rather say them publicly anyway, so for those two it is a choice.
 * <p>
 * <b>Sealed form:</b> the 32 raw bytes of the service id, encrypted one-way to the FID's own
 * pubkey with {@code FC_EccK1AesGcm256_No1_NrC7}, stored as the CryptoDataByte JSON. This is the
 * form {@code home.DISK} has always been written in here, and the one the Mac app reads and
 * writes. Only a service id can be sealed: an address is not 32 bytes. <b>Public form:</b>
 * {@code (sid)<hex>}, or an address, as for every other entry.
 */
public final class HomePrivacy {
    private static final String TAG = "HomePrivacy";
    private static final String SID_PREFIX = "(sid)";
    private static final AlgorithmId ASY_ALG = AlgorithmId.FC_EccK1AesGcm256_No1_NrC7;

    private HomePrivacy() {}

    /** Whether a stored value is sealed. Every sealed value is JSON; no SID or address starts with a brace. */
    public static boolean isSealed(String value) {
        return value != null && value.trim().startsWith("{");
    }

    /** The value {@code home} holds for {@code kind}, under kind@No1_NrC7 or any key naming the kind. */
    public static String valueOf(Map<String, String> home, String kind) {
        if (home == null || kind == null) return null;
        String upper = kind.toUpperCase(Locale.ROOT);
        String own = home.get(upper + "@No1_NrC7");
        if (own != null && !own.trim().isEmpty()) return own.trim();
        for (Map.Entry<String, String> entry : home.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null || value.trim().isEmpty()) continue;
            String k = key.toUpperCase(Locale.ROOT);
            if (k.equals(upper) || k.startsWith(upper + "@")) return value.trim();
        }
        return null;
    }

    /**
     * Seal a service id (bare or {@code (sid)}-prefixed) to {@code pubkeyHex}. Fresh randomness
     * each time, so the same id never seals to the same string twice.
     *
     * @return the CryptoDataByte JSON, or null if it could not be sealed
     * @throws IllegalArgumentException when {@code value} is not a service id
     */
    public static String seal(String value, String pubkeyHex) {
        String sid = HomeServiceResolver.extractSid(value != null ? value.trim() : null);
        if (sid == null) throw new IllegalArgumentException("Only a service id can be kept private");
        if (pubkeyHex == null) return null;
        try {
            Encryptor encryptor = new Encryptor(ASY_ALG);
            CryptoDataByte result = encryptor.encryptByAsyOneWay(Hex.fromHex(sid), Hex.fromHex(pubkeyHex));
            if (result.getCode() != null && result.getCode() != 0) {
                TimberLogger.w(TAG, "Failed to seal sid: code=%d", result.getCode());
                return null;
            }
            return result.toJson();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error sealing sid: %s", e.getMessage());
            return null;
        }
    }

    /**
     * A stored value as a plain home value: unchanged when public, {@code (sid)<hex>} when sealed.
     * Null when blank, or sealed and {@code prikey} does not open it.
     */
    public static String open(String value, byte[] prikey) {
        if (value == null || value.trim().isEmpty()) return null;
        String trimmed = value.trim();
        if (!isSealed(trimmed)) return trimmed;
        if (prikey == null) return null;
        try {
            CryptoDataByte result = new Decryptor().decryptJsonByAsyOneWay(trimmed, prikey);
            if (result != null && result.getData() != null
                    && (result.getCode() == null || result.getCode() == 0)) {
                String sid = HomeServiceResolver.extractSid(Hex.toHex(result.getData()));
                return sid != null ? SID_PREFIX + sid : null;
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Could not open a sealed home value: %s", e.getMessage());
        }
        return null;
    }

    /** A value as written publicly: a service id as {@code (sid)<hex>}, anything else as typed. */
    public static String publicForm(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        String sid = HomeServiceResolver.extractSid(trimmed);
        return sid != null ? SID_PREFIX + sid : trimmed;
    }

    /**
     * What to carve for {@code wanted} over {@code stored}, or null when the chain already says
     * it — the same service, kept the same way.
     * <p>
     * <b>Compared opened, never as bytes.</b> A sealed value differs every time it is sealed,
     * so comparing strings would make every carve of an unchanged private entry look like a
     * change, at a fee.
     *
     * @throws IllegalArgumentException when asked to seal something that is not a service id
     * @throws IllegalStateException    when sealing fails
     */
    public static String pending(String wanted, boolean sealed, String stored, byte[] prikey, String pubkeyHex) {
        String want = publicForm(wanted);
        if (want.isEmpty()) return null;
        String storedOpen = open(stored, prikey);
        String storedPlain = storedOpen != null ? publicForm(storedOpen) : null;
        if (want.equals(storedPlain) && isSealed(stored) == sealed) return null;
        if (!sealed) return want;
        String out = seal(want, pubkeyHex);
        if (out == null) throw new IllegalStateException("Could not seal " + want);
        return out;
    }
}
