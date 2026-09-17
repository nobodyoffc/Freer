package com.fc.freer.data;


import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fapi.client.HomeServiceResolver;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.utils.ApiCenter;

import java.util.Map;

/**
 * Helper for the live FID's {@code freer.home.DISK@No1_NrC7} entry.
 * <p>
 * The DISK service is identified onchain by its service SID. For privacy the SID is not
 * stored in plaintext: it is encrypted one-way with the live FID's public key, so only the
 * holder of that FID's private key (the same identity on any device) can resolve it.
 * <p>
 * Read path: {@code home.DISK} (encrypted JSON) -&gt; decrypt with prikey -&gt; SID
 * -&gt; {@code serviceById(sid)} -&gt; {@code service.home.API} URL.
 * <p>
 * The Data feature requires {@code liveFid == mainFid} (otherwise there is no private key
 * for encryption/decryption), so resolution always uses the live FID's home map.
 */
public final class DiskHomeManager {
    private static final String TAG = "DiskHomeManager";

    /** Home map key for the DISK service (e.g. "DISK@No1_NrC7"). */
    public static final String DISK_KEY = Constants.DISK_NO1_NRC7;

    /** Algorithm used to encrypt the SID with the FID public key (asymmetric one-way). */
    private static final AlgorithmId ASY_ALG = AlgorithmId.FC_EccK1AesGcm256_No1_NrC7;

    private DiskHomeManager() {}

    /**
     * @return true if the live FID has a non-empty {@code home.DISK} entry (configured),
     * regardless of whether it can currently be decrypted.
     */
    public static boolean isConfigured(KeyInfo liveKeyInfo) {
        return rawValue(liveKeyInfo) != null;
    }

    private static String rawValue(KeyInfo liveKeyInfo) {
        return liveKeyInfo != null ? rawValue(liveKeyInfo.getHome()) : null;
    }

    private static String rawValue(Map<String, String> home) {
        if (home == null) return null;
        String value = home.get(DISK_KEY);
        return (value != null && !value.isEmpty()) ? value : null;
    }

    /**
     * Resolve the configured DISK service SID from the live FID's home map.
     * Accepts both the encrypted form and a legacy plaintext {@code (sid)}/64-hex value.
     *
     * @param liveKeyInfo the live FID's KeyInfo
     * @param prikey      the live FID's private key (required to decrypt the encrypted form)
     * @return the DISK service SID (64 hex), or null if not configured / undecryptable
     */
    public static String resolveSid(KeyInfo liveKeyInfo, byte[] prikey) {
        return resolveSid(liveKeyInfo != null ? liveKeyInfo.getHome() : null, prikey);
    }

    /**
     * {@link #resolveSid(KeyInfo, byte[])} over a home map, such as one just read from the chain.
     */
    public static String resolveSid(Map<String, String> home, byte[] prikey) {
        String value = rawValue(home);
        if (value == null) return null;

        // Legacy / plaintext: bare 64-hex SID or "(sid)<hex>"
        String plain = HomeServiceResolver.extractSid(value);
        if (plain != null) {
            return plain;
        }

        // Encrypted CryptoDataByte JSON -> decrypt with prikey
        if (prikey == null) {
            TimberLogger.w(TAG, "home.DISK is encrypted but no prikey provided");
            return null;
        }
        try {
            Decryptor decryptor = new Decryptor();
            CryptoDataByte result = decryptor.decryptJsonByAsyOneWay(value, prikey);
            if (result != null && result.getData() != null
                    && (result.getCode() == null || result.getCode() == 0)) {
                String sid = Hex.toHex(result.getData());
                String validated = HomeServiceResolver.extractSid(sid);
                return validated != null ? sid : null;
            }
            TimberLogger.w(TAG, "Failed to decrypt home.DISK: code=%s message=%s",
                    result != null ? result.getCode() : "null",
                    result != null ? result.getMessage() : "null");
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error decrypting home.DISK: %s", e.getMessage());
        }
        return null;
    }

    /**
     * Encrypt a DISK service SID with the FID public key for onchain storage.
     *
     * @param sid       the service SID (64 hex)
     * @param pubkeyHex the live FID's public key (hex)
     * @return the encrypted value (CryptoDataByte JSON) to store in {@code home.DISK}, or null on failure
     */
    public static String encryptSid(String sid, String pubkeyHex) {
        if (sid == null || pubkeyHex == null) return null;
        try {
            Encryptor encryptor = new Encryptor(ASY_ALG);
            CryptoDataByte result = encryptor.encryptByAsyOneWay(Hex.fromHex(sid), Hex.fromHex(pubkeyHex));
            if (result.getCode() != null && result.getCode() != 0) {
                TimberLogger.w(TAG, "Failed to encrypt DISK sid: code=%d", result.getCode());
                return null;
            }
            return result.toJson();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error encrypting DISK sid: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Resolve and connect the DISK FapiClient from the configured SID, and cache it in ApiCenter.
     *
     * @param liveKeyInfo the live FID's KeyInfo
     * @param prikey      the live FID's private key (to decrypt the SID)
     * @return the connected DISK FapiClient, or null if not configured / unresolvable
     */
    public static FapiClient resolveAndCacheDiskClient(KeyInfo liveKeyInfo, byte[] prikey) {
        String sid = resolveSid(liveKeyInfo, prikey);
        if (sid == null) return null;

        FapiClient base = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
        if (base == null) {
            TimberLogger.w(TAG, "No BASE FapiClient to bootstrap DISK from sid");
            return null;
        }
        try {
            com.fc.freer.model.Setting setting = ApiCenter.getInstance().getCurrentSetting();
            FapiClient diskClient = FapiClient.bootstrapFromSid(
                    setting != null ? setting.getFudpNode() : null,
                    sid, base,
                    setting != null ? setting.getSettingMap() : null);
            if (diskClient != null && diskClient.isConfigured()) {
                ApiCenter.getInstance().setDiskClient(diskClient);
                TimberLogger.i(TAG, "Resolved DISK client from home.DISK sid=%s", sid);
                return diskClient;
            }
            TimberLogger.w(TAG, "Failed to connect DISK client for sid=%s", sid);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error resolving DISK client from sid %s: %s", sid, e.getMessage());
        }
        return null;
    }
}
