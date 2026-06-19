package com.fc.freer.data;

import android.content.Context;

import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.DiskItem;
import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.HatManager;
import com.fc.freer.utils.ApiCenter;
import com.fc.fc_ajdk.data.feipData.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Manages data synchronization between local storage and FAPI.DISK server.
 * Implements the two-HAT cipher model:
 * <ul>
 *   <li>Raw HAT: References the original data. Its {@code cipherIds} list points to cipher HATs.</li>
 *   <li>Cipher HAT: References the encrypted data on DISK. Its {@code rawDid} points back to the raw data,
 *       {@code kCipher} contains the encrypted symmetric key, and {@code locas} contains the DISK location.</li>
 * </ul>
 * 
 * Upload flow:
 * <ol>
 *   <li>Generate random symmetric key</li>
 *   <li>Stream-encrypt raw file to temp cipher file</li>
 *   <li>Stream-hash cipher file to get cipher DID</li>
 *   <li>Encrypt symkey with user's public key (asymmetric one-way)</li>
 *   <li>Upload cipher file to DISK</li>
 *   <li>Create cipher HAT (id=cipherDID, rawDid=rawHAT.id, kCipher, locas=[fudp://host:port])</li>
 *   <li>Add cipherDID to raw HAT's cipherIds</li>
 * </ol>
 * 
 * Download flow:
 * <ol>
 *   <li>Try raw HAT's locas first (direct/unencrypted locations)</li>
 *   <li>If no direct locations, iterate cipherIds</li>
 *   <li>For each cipher HAT: download cipher file, verify rawDid, decrypt kCipher, stream-decrypt</li>
 * </ol>
 */
public class DataSyncManager {
    private static final String TAG = "DataSyncManager";
    
    // Default encryption algorithm for symmetric key encryption (used to encrypt the symkey itself)
    private static final AlgorithmId DEFAULT_ASY_ALG = AlgorithmId.FC_EccK1AesGcm256_No1_NrC7;
    
    // Default symmetric encryption algorithm (used to encrypt the file data)
    private static final AlgorithmId DEFAULT_SYM_ALG = AlgorithmId.FC_AesGcm256_No1_NrC7;
    
    // Symmetric key size in bytes
    private static final int SYM_KEY_SIZE = 32;
    
    // Location prefixes: fudp://host:port, (sid)serviceId
    public static final String FUDP_LOCATION_PREFIX = "fudp://";
    public static final String SID_LOCATION_PREFIX = "(sid)";
    
    // LRU cache for DISK clients resolved from SID locations, avoids re-bootstrapping
    private static final int DISK_CLIENT_CACHE_SIZE = 16;
    private final Map<String, FapiClient> diskClientCache = new LinkedHashMap<>(DISK_CLIENT_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, FapiClient> eldest) {
            return size() > DISK_CLIENT_CACHE_SIZE;
        }
    };

    private final Context context;
    private final HatManager hatManager;
    private final SecureRandom secureRandom = new SecureRandom();
    private String lastError;
    private long lastCharged;
    private byte[] lastSymkey;
    
    public DataSyncManager(Context context, HatManager hatManager) {
        this.context = context;
        this.hatManager = hatManager;
    }
    
    /**
     * Gets the DISK FapiClient from ApiCenter.
     * Uses the home-appointed DISK client (from freer.home.DISK) when available,
     * otherwise falls back to the default FapiClient.
     */
    private FapiClient getFapiClient() {
        try {
            ApiCenter apiCenter = ApiCenter.getInstance();
            FapiClient client = apiCenter.getClient(ApiCenter.ConnectionRole.DISK);
            if (client != null) {
                return client;
            }
            TimberLogger.w(TAG, "getFapiClient: no DISK or default FAPI client available");
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting FapiClient: %s", e.getMessage());
        }
        return null;
    }

    /**
     * Checks if the FapiClient (DISK service) is available.
     * Use this for pre-flight checks before starting uploads.
     *
     * @return true if the FAPI/DISK client is available
     */
    public boolean isFapiClientAvailable() {
        return getFapiClient() != null;
    }

    /**
     * Gets the default DISK endpoint URL (fudp://host:port) for the current FapiClient.
     * Uses getServerUrl() (the actual connection URL) rather than apiAccount.getApiUrl()
     * because the server may advertise localhost in its Service home map.
     */
    private String getDefaultDiskEndpointUrl() {
        FapiClient client = getFapiClient();
        if (client == null) return null;
        String url = client.getServerUrl();
        if (url == null || url.isEmpty()) return null;
        return url.toLowerCase().startsWith("fudp://") ? url : (FUDP_LOCATION_PREFIX + url);
    }

    /**
     * Gets the default DISK service SID.
     */
    private String getDefaultDiskServiceSid() {
        FapiClient client = getFapiClient();
        if (client == null) return null;
        // Prefer apiAccount's providerId; fall back to client's serviceSid
        if (client.getApiAccount() != null && client.getApiAccount().getProviderId() != null) {
            return client.getApiAccount().getProviderId();
        }
        return client.getServiceSid();
    }

    /**
     * Gets FapiClient for the given loca (fudp://host:port or (sid)serviceId).
     * Returns default client if loca matches default; otherwise creates via bootstrapFromUrl/Sid.
     */
    private FapiClient getFapiClientForLoca(String loca) {
        if (loca == null || loca.isEmpty()) return null;
        FapiClient defaultClient = getFapiClient();
        if (defaultClient == null) return null;
        String defaultUrl = getDefaultDiskEndpointUrl();
        String defaultSid = getDefaultDiskServiceSid();
        if (loca.startsWith(FUDP_LOCATION_PREFIX)) {
            String locaUrl = loca.toLowerCase().startsWith("fudp://") ? loca : (FUDP_LOCATION_PREFIX + loca);
            if (normalizeFudpUrl(locaUrl).equals(normalizeFudpUrl(defaultUrl))) {
                return defaultClient;
            }
            // Check cache by URL
            FapiClient cached = diskClientCache.get(locaUrl);
            if (cached != null) return cached;

            com.fc.freer.model.Setting setting = SettingManager.getInstance().getCurrentSetting();
            FapiClient client = FapiClient.bootstrapFromUrl(getFudpNode(), locaUrl,
                    setting != null ? setting.getSettingMap() : null);
            if (client != null) {
                diskClientCache.put(locaUrl, client);
            }
            return client;
        }
        if (loca.startsWith(SID_LOCATION_PREFIX)) {
            String sid = loca.substring(SID_LOCATION_PREFIX.length()).trim();
            if (sid.equals(defaultSid)) return defaultClient;

            // Check cache by SID
            FapiClient cached = diskClientCache.get(sid);
            if (cached != null) return cached;

            com.fc.freer.model.Setting setting = SettingManager.getInstance().getCurrentSetting();
            FapiClient client = FapiClient.bootstrapFromSid(getFudpNode(), sid, defaultClient,
                    setting != null ? setting.getSettingMap() : null);
            if (client != null) {
                diskClientCache.put(sid, client);
                // Also cache by URL for cross-reference
                if (client.getServerUrl() != null) {
                    diskClientCache.put(normalizeFudpUrl(client.getServerUrl()), client);
                }
            }
            return client;
        }
        return null;
    }

    private String normalizeFudpUrl(String url) {
        if (url == null) return "";
        String u = url.trim().toLowerCase();
        if (!u.startsWith("fudp://")) u = FUDP_LOCATION_PREFIX + u;
        return u;
    }

    private com.fc.fc_ajdk.fudp.node.FudpNode getFudpNode() {
        com.fc.freer.model.Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null) return null;
        com.fc.fc_ajdk.fudp.node.FudpNode node = setting.getFudpNode();
        if (node == null || !node.isRunning()) {
            String dataDir = context.getFilesDir().getAbsolutePath() + "/fudp";
            node = setting.initFudpNode(dataDir);
        }
        return node;
    }
    
    /**
     * Gets the live FID's public key for encryption.
     *
     * @return The public key in hex format, or null if not available
     */
    private String getLiveFidPubkey() {
        FidManager fidManager = FidManager.getInstance();
        if (fidManager != null) {
            KeyInfo keyInfo = fidManager.getLiveKeyInfo();
            if (keyInfo != null) {
                return keyInfo.getPubkey();
            }
        }
        return null;
    }
    
    /**
     * Uploads data to DISK with encryption using the two-HAT model.
     */
    public DiskItem uploadData(File rawFile, Hat hat, boolean permanent) {
        return uploadData(rawFile, hat, permanent, null, null);
    }

    /**
     * Uploads data with progress callback.
     */
    public DiskItem uploadData(File rawFile, Hat hat, boolean permanent, LongConsumer progressCallback) {
        return uploadData(rawFile, hat, permanent, null, progressCallback);
    }
    
    /**
     * Uploads data to DISK with encryption using the two-HAT model.
     * <p>
     * Steps:
     * 1. Generate 32-byte random symmetric key
     * 2. Stream-encrypt raw file to temp cipher file
     * 3. Stream-hash cipher file to get cipher DID
     * 4. Encrypt symkey with pubkey (asymmetric one-way) -> kCipher JSON
     * 5. Upload cipher file to DISK
     * 6. Create cipher HAT (id=cipherDID, rawDid=rawHAT.id, kCipher, locas=[fudp://host:port])
     * 7. Add cipherDID to raw HAT's cipherIds
     *
     * @param rawFile      The raw file to encrypt and upload
     * @param hat          The raw HAT associated with this data
     * @param permanent    Whether to carve (permanent) or put (temporary)
     * @param dataLifeDays Days to keep data (only for non-permanent, null for default)
     * @return DiskItem with upload metadata, or null on failure
     */
    public DiskItem uploadData(File rawFile, Hat hat, boolean permanent, Long dataLifeDays) {
        return uploadData(rawFile, hat, permanent, dataLifeDays, null);
    }

    public DiskItem uploadData(File rawFile, Hat hat, boolean permanent, Long dataLifeDays, LongConsumer progressCallback) {
        if (rawFile == null || !rawFile.exists()) {
            lastError = "Raw file does not exist";
            TimberLogger.w(TAG, "Upload step 0 failed: %s", lastError);
            return null;
        }
        
        FapiClient fapiClient = getFapiClient();
        if (fapiClient == null) {
            lastError = "FapiClient not available";
            TimberLogger.w(TAG, "Upload step 0 failed: %s", lastError);
            return null;
        }
        TimberLogger.i(TAG, "Upload: FapiClient ready, apiUrl=%s",
                fapiClient.getApiAccount() != null ? fapiClient.getApiAccount().getApiUrl() : "null");
        
        String pubkey = getLiveFidPubkey();
        if (pubkey == null) {
            lastError = "Public key not available";
            TimberLogger.w(TAG, "Upload step 0 failed: %s", lastError);
            return null;
        }
        TimberLogger.i(TAG, "Upload: pubkey available (length=%d)", pubkey.length());
        
        File cipherTempFile = null;
        try {
            // Step 1: Generate random symmetric key
            byte[] symkey = new byte[SYM_KEY_SIZE];
            secureRandom.nextBytes(symkey);
            lastSymkey = symkey.clone();
            TimberLogger.i(TAG, "Upload step 1: symkey generated");
            
            // Step 2: Stream-encrypt raw file to temp cipher file
            cipherTempFile = File.createTempFile("cipher_", ".tmp", context.getCacheDir());
            Encryptor encryptor = new Encryptor(DEFAULT_SYM_ALG);
            CryptoDataByte encResult = encryptor.encryptFileBySymkey(
                    rawFile.getAbsolutePath(), cipherTempFile.getAbsolutePath(), symkey);
            
            if (encResult.getCode() != null && encResult.getCode() != 0) {
                lastError = "File encryption failed: " + encResult.getMessage();
                TimberLogger.w(TAG, "Upload step 2 failed: %s (code=%d)", lastError, encResult.getCode());
                return null;
            }
            TimberLogger.i(TAG, "Upload step 2: file encrypted, cipher size=%d bytes", cipherTempFile.length());
            
            // Step 3: Stream-hash cipher file to get cipher DID
            byte[] cipherHash = Hash.sha256x2Bytes(cipherTempFile);
            String cipherDid = Hex.toHex(cipherHash);
            TimberLogger.i(TAG, "Upload step 3: cipher DID=%s", cipherDid);
            
            // Step 4: Encrypt symkey with pubkey (asymmetric one-way)
            Encryptor asyEncryptor = new Encryptor(DEFAULT_ASY_ALG);
            CryptoDataByte kCipherResult = asyEncryptor.encryptByAsyOneWay(symkey, Hex.fromHex(pubkey));
            
            if (kCipherResult.getCode() != null && kCipherResult.getCode() != 0) {
                lastError = "Symkey encryption failed: " + kCipherResult.getMessage();
                TimberLogger.w(TAG, "Upload step 4 failed: %s (code=%d)", lastError, kCipherResult.getCode());
                return null;
            }
            String kCipherJson = kCipherResult.toJson();
            TimberLogger.i(TAG, "Upload step 4: symkey encrypted, alg=%s, type=%s, ivLen=%d, cipherLen=%d, pubkeyALen=%d",
                    kCipherResult.getAlg(),
                    kCipherResult.getType(),
                    kCipherResult.getIv() != null ? kCipherResult.getIv().length : -1,
                    kCipherResult.getCipher() != null ? kCipherResult.getCipher().length : -1,
                    kCipherResult.getPubkeyA() != null ? kCipherResult.getPubkeyA().length : -1);
            
            // Step 5: Upload cipher file to DISK
            TimberLogger.i(TAG, "Upload step 5: uploading cipher file to DISK (permanent=%b, size=%d bytes)",
                    permanent, cipherTempFile.length());
            DiskItem diskItem;
            if (permanent) {
                diskItem = fapiClient.diskCarve(cipherTempFile, progressCallback);
            } else {
                diskItem = fapiClient.diskPut(cipherTempFile, dataLifeDays, progressCallback);
            }
            if (diskItem != null && fapiClient.getLastCharged() != null) {
                lastCharged = fapiClient.getLastCharged();
            }
            if (diskItem == null) {
                lastError = "Failed to upload to DISK: " + 
                        (fapiClient.getLastError() != null ? fapiClient.getLastError() : "Unknown error");
                TimberLogger.w(TAG, "Upload step 5 failed: %s", lastError);
                return null;
            }
            TimberLogger.i(TAG, "Upload step 5: uploaded to DISK successfully, charged=%d", lastCharged);
            
            // Step 6: Create cipher HAT. Prefer a stable (sid) location so downloads can
            // resolve the DISK service via serviceById regardless of the live server URL.
            // Fall back to the fudp:// endpoint only when the SID is unavailable.
            String diskLocation;
            String diskSid = getDefaultDiskServiceSid();
            if (diskSid != null && !diskSid.isEmpty()) {
                diskLocation = SID_LOCATION_PREFIX + diskSid;
            } else {
                diskLocation = getDefaultDiskEndpointUrl();
            }
            if (diskLocation == null) diskLocation = FUDP_LOCATION_PREFIX + "unknown";
            Hat cipherHat = hatManager.createCipherHat(
                    cipherDid, hat.getId(), kCipherJson, cipherTempFile.length());
            
            // Add DISK location to cipher HAT
            List<String> cipherLocas = new ArrayList<>();
            cipherLocas.add(diskLocation);
            cipherHat.setLocas(cipherLocas);
            hatManager.updateHat(cipherHat);
            TimberLogger.i(TAG, "Upload step 6: cipher HAT created, location=%s", diskLocation);
            
            // Step 7: Add cipherDID to raw HAT's cipherIds
            hatManager.addCipherId(hat.getId(), cipherDid);
            
            // Commit all changes
            hatManager.commit();
            
            TimberLogger.i(TAG, "Upload step 7: committed. Successfully uploaded encrypted data to DISK: cipher=%s, raw=%s", 
                    cipherDid, hat.getId());
            return diskItem;
            
        } catch (Exception e) {
            lastError = "Upload failed: " + e.getMessage();
            TimberLogger.e(TAG, "Upload exception: %s", e.getMessage());
            e.printStackTrace();
            return null;
        } finally {
            // Clean up temp file
            if (cipherTempFile != null && cipherTempFile.exists()) {
                cipherTempFile.delete();
            }
        }
    }
    
    /**
     * Uploads raw byte data to DISK with encryption using the two-HAT model.
     * Writes data to a temp file first, then delegates to the file-based method.
     *
     * @param data      The raw data to upload
     * @param hat       The raw HAT associated with this data
     * @param permanent Whether to carve (permanent) or put (temporary)
     * @return DiskItem with upload metadata, or null on failure
     */
    public DiskItem uploadData(byte[] data, Hat hat, boolean permanent) {
        return uploadData(data, hat, permanent, null);
    }
    
    /**
     * Uploads raw byte data to DISK with encryption.
     * Writes data to a temp file first, then delegates to the file-based method.
     *
     * @param data         The raw data to upload
     * @param hat          The raw HAT associated with this data
     * @param permanent    Whether to carve (permanent) or put (temporary)
     * @param dataLifeDays Days to keep data (only for non-permanent, null for default)
     * @return DiskItem with upload metadata, or null on failure
     */
    public DiskItem uploadData(byte[] data, Hat hat, boolean permanent, Long dataLifeDays) {
        if (data == null || data.length == 0) {
            lastError = "Data is empty";
            return null;
        }
        
        File tempRawFile = null;
        try {
            // Write byte data to temp file
            tempRawFile = File.createTempFile("raw_", ".tmp", context.getCacheDir());
            try (FileOutputStream fos = new FileOutputStream(tempRawFile)) {
                fos.write(data);
            }
            
            return uploadData(tempRawFile, hat, permanent, dataLifeDays);
            
        } catch (Exception e) {
            lastError = "Upload failed: " + e.getMessage();
            return null;
        } finally {
            if (tempRawFile != null && tempRawFile.exists()) {
                tempRawFile.delete();
            }
        }
    }
    
    /**
     * Downloads and decrypts data from DISK using the two-HAT model.
     * <p>
     * Steps:
     * 1. Try raw HAT's locas first (direct/unencrypted locations)
     * 2. If no direct locations work, iterate cipherIds
     * 3. For each cipherId, get the cipher HAT, download, verify, decrypt
     *
     * @param hat       The raw HAT with location/cipher info
     * @param prikey    The private key for decryption (as byte array)
     * @param outputFile The file to write the decrypted data to
     * @return true if download and decryption succeeded, false otherwise
     */
    public boolean downloadData(Hat hat, byte[] prikey, File outputFile) {
        return downloadData(hat, prikey, outputFile, null);
    }

    public boolean downloadData(Hat hat, byte[] prikey, File outputFile, LongConsumer progressCallback) {
        if (hat == null) {
            lastError = "HAT is null";
            return false;
        }
        FapiClient fapiClient = getFapiClient();
        if (fapiClient == null) {
            lastError = "FapiClient not available";
            TimberLogger.w(TAG, "downloadData: FapiClient not available");
            return false;
        }
        // Step 1: Try raw HAT's locas first (direct download from fudp:// or (sid))
        List<String> remoteLocas = findRemoteLocas(hat);
        for (String loca : remoteLocas) {
            FapiClient clientForLoca = getFapiClientForLoca(loca);
            if (clientForLoca == null) continue;
            String did = hat.getId();
            try {
                DiskItem downloadResult = clientForLoca.diskGet(did, outputFile, progressCallback);
                if (downloadResult != null) {
                    if (clientForLoca.getLastCharged() != null) lastCharged = clientForLoca.getLastCharged();
                    hat.setLast(System.currentTimeMillis());
                    hatManager.updateHat(hat);
                    hatManager.commit();
                    TimberLogger.i(TAG, "Downloaded data directly from raw HAT locations");
                    return true;
                }
            } catch (Exception e) {
                TimberLogger.w(TAG, "Direct download from loca %s failed: %s", loca, e.getMessage());
            }
        }

        // Step 2: Try cipherIds
        List<String> cipherIds = hat.getCipherIds();
        if (cipherIds == null || cipherIds.isEmpty()) {
            lastError = "No locations or cipherIds available in HAT";
            return false;
        }

        // Step 3: Iterate through cipherIds
        for (String cipherId : cipherIds) {
            Hat cipherHat = hatManager.getHatById(cipherId);
            if (cipherHat != null) {
                boolean success = downloadViaCipherHat(hat, cipherId, prikey, outputFile, fapiClient, progressCallback);
                if (success) {
                    hat.setLast(System.currentTimeMillis());
                    hatManager.updateHat(hat);
                    hatManager.commit();
                    return true;
                }
            } else {
                // Cipher HAT not in local DB (received via IM) -- try using plaintext key + raw HAT's locas
                String plainKeyHex = hat.getKey();
                if (plainKeyHex != null && !plainKeyHex.isEmpty()) {
                    boolean success = downloadCipherWithPlainKey(hat, cipherId, plainKeyHex, outputFile, progressCallback);
                    if (success) {
                        hat.setLast(System.currentTimeMillis());
                        hatManager.updateHat(hat);
                        hatManager.commit();
                        return true;
                    }
                } else {
                    TimberLogger.w(TAG, "Cipher HAT %s not in local DB and no plaintext key in raw HAT", cipherId);
                }
            }
        }

        lastError = "Failed to download from any cipher source";
        TimberLogger.w(TAG, "downloadData: all attempts failed for hatId=%s", hat.getId());
        return false;
    }
    
    /**
     * Downloads and decrypts data from DISK via a cipher HAT.
     *
     * @param rawHat     The raw HAT
     * @param cipherId   The cipher DID to try
     * @param prikey     The private key for decryption
     * @param outputFile The file to write decrypted data to
     * @param fapiClient The FAPI client
     * @return true if successful
     */
    private boolean downloadViaCipherHat(Hat rawHat, String cipherId, byte[] prikey, 
                                          File outputFile, FapiClient fapiClient, LongConsumer progressCallback) {
        File cipherTempFile = null;
        try {
            // Get the cipher HAT
            Hat cipherHat = hatManager.getHatById(cipherId);
            if (cipherHat == null) {
                TimberLogger.w(TAG, "Cipher HAT not found: %s", cipherId);
                return false;
            }
            
            // Verify rawDid matches
            if (!rawHat.getId().equals(cipherHat.getRawDid())) {
                TimberLogger.w(TAG, "rawDid mismatch: expected %s, got %s", 
                        rawHat.getId(), cipherHat.getRawDid());
                return false;
            }
            
            String cipherDid = cipherHat.getId();
            cipherTempFile = File.createTempFile("dl_cipher_", ".tmp", context.getCacheDir());

            // Build an ordered list of candidate clients: the cipher HAT's own remote
            // locations first, then the currently-configured DISK client as a fallback.
            // The fallback recovers cipher HATs whose loca is missing or stale
            // (e.g. legacy "fudp://unknown" written before SID-based locations).
            List<FapiClient> candidates = new ArrayList<>();
            for (String loca : findRemoteLocas(cipherHat)) {
                FapiClient c = getFapiClientForLoca(loca);
                if (c != null && !candidates.contains(c)) candidates.add(c);
            }
            if (fapiClient != null && !candidates.contains(fapiClient)) candidates.add(fapiClient);
            if (candidates.isEmpty()) {
                TimberLogger.w(TAG, "No usable client for cipher HAT: %s", cipherId);
                return false;
            }

            DiskItem cipherDownloadResult = null;
            FapiClient usedClient = null;
            for (FapiClient c : candidates) {
                cipherDownloadResult = c.diskGet(cipherDid, cipherTempFile, progressCallback);
                if (cipherDownloadResult != null) {
                    if (c.getLastCharged() != null) lastCharged = c.getLastCharged();
                    usedClient = c;
                    break;
                }
                TimberLogger.w(TAG, "downloadViaCipherHat: diskGet(%s) failed via %s: %s",
                        cipherDid, c.getServerUrl(), c.getLastError());
            }
            if (cipherDownloadResult == null) {
                TimberLogger.w(TAG, "Failed to download cipher file: %s", cipherDid);
                return false;
            }
            TimberLogger.i(TAG, "Downloaded cipher file: %s, size=%d bytes", cipherDid, cipherTempFile.length());

            // If the stored loca was unusable and we recovered via another client,
            // repair the cipher HAT's loca to a stable (sid) so future downloads resolve directly.
            repairCipherLocaIfNeeded(cipherHat, usedClient);
            
            // Decrypt kCipher to get symmetric key
            String kCipherJson = cipherHat.getkCipher();
            if (kCipherJson == null || kCipherJson.isEmpty()) {
                TimberLogger.w(TAG, "No kCipher in cipher HAT: %s", cipherId);
                return false;
            }
            
            CryptoDataByte kCipherData = CryptoDataByte.fromJson(kCipherJson);
            
            // Diagnostic logging for kCipher contents
            TimberLogger.i(TAG, "kCipher diagnostics: alg=%s, type=%s, ivLen=%d, cipherLen=%d, pubkeyALen=%d",
                    kCipherData.getAlg(),
                    kCipherData.getType(),
                    kCipherData.getIv() != null ? kCipherData.getIv().length : -1,
                    kCipherData.getCipher() != null ? kCipherData.getCipher().length : -1,
                    kCipherData.getPubkeyA() != null ? kCipherData.getPubkeyA().length : -1);
            TimberLogger.i(TAG, "prikey length for decryption: %d", prikey != null ? prikey.length : -1);
            
            kCipherData.setPrikeyA(prikey);
            
            Decryptor decryptor = new Decryptor();
            decryptor.decryptByAsyKey(kCipherData);
            CryptoDataByte kDecryptResult = kCipherData;
            
            TimberLogger.i(TAG, "kCipher decryption result: code=%s, message=%s, dataLen=%d",
                    kDecryptResult.getCode(),
                    kDecryptResult.getMessage(),
                    kDecryptResult.getData() != null ? kDecryptResult.getData().length : -1);
            
            if (kDecryptResult.getCode() != null && kDecryptResult.getCode() != 0) {
                TimberLogger.w(TAG, "Failed to decrypt kCipher: code=%d, message=%s", 
                        kDecryptResult.getCode(), kDecryptResult.getMessage());
                return false;
            }
            
            byte[] symkey = kDecryptResult.getData();
            if (symkey == null) {
                TimberLogger.w(TAG, "Decrypted symkey is null");
                return false;
            }
            
            if (symkey.length != 32) {
                TimberLogger.w(TAG, "Decrypted symkey has wrong length: %d (expected 32). " +
                        "kCipher alg=%s, type=%s. This indicates kCipher decryption produced invalid data.",
                        symkey.length, kCipherData.getAlg(), kCipherData.getType());
                return false;
            }
            
            TimberLogger.i(TAG, "Successfully decrypted kCipher, symkey length=%d", symkey.length);
            
            // Stream-decrypt cipher file to output file
            Decryptor fileDecryptor = new Decryptor();
            CryptoDataByte decryptResult = fileDecryptor.decryptFileBySymkey(
                    cipherTempFile.getAbsolutePath(), outputFile.getAbsolutePath(), symkey);
            
            if (decryptResult.getCode() != null && decryptResult.getCode() != 0) {
                TimberLogger.w(TAG, "File decryption failed: code=%d, message=%s, alg=%s", 
                        decryptResult.getCode(), decryptResult.getMessage(), decryptResult.getAlg());
                return false;
            }
            
            // Stream-hash output file to verify DID matches raw HAT ID
            byte[] outputHash = Hash.sha256x2Bytes(outputFile);
            String outputDid = Hex.toHex(outputHash);
            
            if (!rawHat.getId().equalsIgnoreCase(outputDid)) {
                TimberLogger.e(TAG, "Data integrity check failed: expected %s, got %s", 
                        rawHat.getId(), outputDid);
                // Delete the corrupted output
                outputFile.delete();
                return false;
            }
            
            TimberLogger.i(TAG, "Successfully downloaded and decrypted via cipher HAT: %s", cipherId);
            return true;
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error downloading via cipher HAT %s: %s", cipherId, e.getMessage());
            return false;
        } finally {
            if (cipherTempFile != null && cipherTempFile.exists()) {
                cipherTempFile.delete();
            }
        }
    }

    /**
     * Downloads cipher file and decrypts using a plaintext symmetric key.
     * Used when the cipher HAT is not in the local database (e.g. file received via IM)
     * but the raw HAT carries the plaintext key and DISK locations.
     */
    private boolean downloadCipherWithPlainKey(Hat rawHat, String cipherDid, String plainKeyHex,
                                               File outputFile, LongConsumer progressCallback) {
        File cipherTempFile = null;
        try {
            byte[] symkey = Hex.fromHex(plainKeyHex);
            if (symkey == null || symkey.length != SYM_KEY_SIZE) {
                TimberLogger.w(TAG, "Invalid plaintext key length: %d", symkey != null ? symkey.length : -1);
                return false;
            }

            // Use raw HAT's remote locas to find the DISK server
            List<String> remoteLocas = findRemoteLocas(rawHat);
            if (remoteLocas.isEmpty()) {
                TimberLogger.w(TAG, "No remote locas in raw HAT for cipher download");
                return false;
            }

            cipherTempFile = File.createTempFile("dl_cipher_", ".tmp", context.getCacheDir());
            DiskItem downloadResult = null;
            for (String loca : remoteLocas) {
                FapiClient clientForLoca = getFapiClientForLoca(loca);
                if (clientForLoca == null) continue;
                downloadResult = clientForLoca.diskGet(cipherDid, cipherTempFile, progressCallback);
                if (downloadResult != null) {
                    if (clientForLoca.getLastCharged() != null) lastCharged = clientForLoca.getLastCharged();
                    break;
                }
            }
            if (downloadResult == null) {
                TimberLogger.w(TAG, "Failed to download cipher file %s from any loca", cipherDid);
                return false;
            }
            TimberLogger.i(TAG, "Downloaded cipher file via plain key: %s, size=%d bytes", cipherDid, cipherTempFile.length());

            // Decrypt cipher file with the plaintext symkey
            Decryptor fileDecryptor = new Decryptor();
            CryptoDataByte decryptResult = fileDecryptor.decryptFileBySymkey(
                    cipherTempFile.getAbsolutePath(), outputFile.getAbsolutePath(), symkey);

            if (decryptResult.getCode() != null && decryptResult.getCode() != 0) {
                TimberLogger.w(TAG, "Decryption with plain key failed: code=%d, message=%s",
                        decryptResult.getCode(), decryptResult.getMessage());
                return false;
            }

            // Verify integrity
            byte[] outputHash = Hash.sha256x2Bytes(outputFile);
            String outputDid = Hex.toHex(outputHash);

            if (!rawHat.getId().equalsIgnoreCase(outputDid)) {
                TimberLogger.e(TAG, "Integrity check failed: expected %s, got %s", rawHat.getId(), outputDid);
                outputFile.delete();
                return false;
            }

            TimberLogger.i(TAG, "Successfully downloaded and decrypted with plain key: cipher=%s, raw=%s",
                    cipherDid, rawHat.getId());
            return true;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error downloading with plain key: %s", e.getMessage());
            return false;
        } finally {
            if (cipherTempFile != null && cipherTempFile.exists()) {
                cipherTempFile.delete();
            }
        }
    }
    
    /**
     * Downloads and decrypts data from DISK, returning as byte array.
     * This is a convenience wrapper around the file-based downloadData().
     *
     * @param hat    The HAT with location and cipher info
     * @param prikey The private key for decryption (as byte array)
     * @return Decrypted data, or null on failure
     */
    public byte[] downloadData(Hat hat, byte[] prikey) {
        File tempOutputFile = null;
        try {
            tempOutputFile = File.createTempFile("dl_output_", ".tmp", context.getCacheDir());
            boolean success = downloadData(hat, prikey, tempOutputFile);
            if (!success) {
                return null;
            }
            
            // Read the output file
            byte[] result = new byte[(int) tempOutputFile.length()];
            try (FileInputStream fis = new FileInputStream(tempOutputFile)) {
                int totalRead = 0;
                while (totalRead < result.length) {
                    int read = fis.read(result, totalRead, result.length - totalRead);
                    if (read == -1) break;
                    totalRead += read;
                }
            }
            return result;
            
        } catch (Exception e) {
            lastError = "Download failed: " + e.getMessage();
            return null;
        } finally {
            if (tempOutputFile != null && tempOutputFile.exists()) {
                tempOutputFile.delete();
            }
        }
    }
    
    /**
     * Checks if data exists on DISK.
     *
     * @param hat The HAT to check
     * @return DiskItem with metadata if exists, null otherwise
     */
    public DiskItem checkDataOnDisk(Hat hat) {
        if (hat == null) return null;
        String did = hat.getId();
        for (String loca : findRemoteLocas(hat)) {
            FapiClient client = getFapiClientForLoca(loca);
            if (client != null) {
                DiskItem item = client.diskCheck(did);
                if (item != null) return item;
            }
        }
        List<String> cipherIds = hat.getCipherIds();
        if (cipherIds != null) {
            for (String cipherId : cipherIds) {
                Hat cipherHat = hatManager.getHatById(cipherId);
                if (cipherHat != null) {
                    String cipherDid = cipherHat.getId();
                    for (String loca : findRemoteLocas(cipherHat)) {
                        FapiClient client = getFapiClientForLoca(loca);
                        if (client != null) {
                            DiskItem item = client.diskCheck(cipherDid);
                            if (item != null) return item;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Batch check which DIDs exist on the default DISK server. Returns map of DID to DiskItem.
     */
    public Map<String, DiskItem> batchCheckOnDisk(List<Hat> hats) {
        Map<String, DiskItem> result = new HashMap<>();
        if (hats == null || hats.isEmpty()) return result;
        List<String> didsToCheck = new ArrayList<>();
        for (Hat hat : hats) {
            String did = hat.getId();
            if (!didsToCheck.contains(did)) didsToCheck.add(did);
            List<String> cipherIds = hat.getCipherIds();
            if (cipherIds != null) {
                for (String cid : cipherIds) {
                    Hat ch = hatManager.getHatById(cid);
                    if (ch != null && !didsToCheck.contains(ch.getId())) {
                        didsToCheck.add(ch.getId());
                    }
                }
            }
        }
        FapiClient defaultClient = getFapiClient();
        if (defaultClient == null) {
            TimberLogger.w(TAG, "batchCheckOnDisk: FapiClient not available, skipping DISK check for %d DIDs", didsToCheck.size());
            return result;
        }
        TimberLogger.i(TAG, "batchCheckOnDisk: checking %d DIDs on DISK", didsToCheck.size());
        for (int i = 0; i < didsToCheck.size(); i += 200) {
            int end = Math.min(i + 200, didsToCheck.size());
            List<String> batch = didsToCheck.subList(i, end);
            Map<String, DiskItem> batchResult = defaultClient.diskCheck(batch);
            if (batchResult != null) {
                for (Map.Entry<String, DiskItem> e : batchResult.entrySet()) {
                    if (e.getValue() != null) result.put(e.getKey(), e.getValue());
                }
            }
        }
        return result;
    }
    
    /**
     * Uploads a raw file without encryption.
     *
     * @param file      The file to upload
     * @param hat       The HAT associated with this data
     * @param permanent Whether to carve (permanent) or put (temporary)
     * @return DiskItem with upload metadata, or null on failure
     */
    public DiskItem uploadRawData(File file, Hat hat, boolean permanent) {
        if (file == null || !file.exists()) {
            lastError = "File does not exist";
            return null;
        }
        
        FapiClient fapiClient = getFapiClient();
        if (fapiClient == null) {
            lastError = "FapiClient not available";
            return null;
        }
        
        try {
            DiskItem diskItem;
            if (permanent) {
                diskItem = fapiClient.diskCarve(file);
            } else {
                diskItem = fapiClient.diskPut(file);
            }
            
            if (diskItem == null) {
                lastError = "Failed to upload to DISK";
                return null;
            }
            
            // Update HAT with location (fudp://host:port)
            String location = getDefaultDiskEndpointUrl();
            if (location == null) location = FUDP_LOCATION_PREFIX + "unknown";
            List<String> locas = hat.getLocas();
            if (locas == null) {
                locas = new ArrayList<>();
            }
            if (!locas.contains(location)) {
                locas.add(location);
            }
            hat.setLocas(locas);
            hat.setLast(System.currentTimeMillis());
            
            hatManager.updateHat(hat);
            hatManager.commit();
            
            return diskItem;
            
        } catch (Exception e) {
            lastError = "Upload failed: " + e.getMessage();
            return null;
        }
    }
    
    /**
     * Rewrites a cipher HAT's location to a stable {@code (sid)<serviceSid>} when the existing
     * location was unusable and the download was recovered through {@code usedClient}.
     * No-op if the loca is already a {@code (sid)} entry or the client SID is unknown.
     */
    private void repairCipherLocaIfNeeded(Hat cipherHat, FapiClient usedClient) {
        if (cipherHat == null || usedClient == null) return;
        String sid = usedClient.getServiceSid();
        if (sid == null && usedClient.getApiAccount() != null) {
            sid = usedClient.getApiAccount().getProviderId();
        }
        if (sid == null || sid.isEmpty()) return;

        String correctLoca = SID_LOCATION_PREFIX + sid;
        List<String> locas = cipherHat.getLocas();
        if (locas != null && locas.contains(correctLoca)) return;

        List<String> newLocas = new ArrayList<>();
        newLocas.add(correctLoca);
        cipherHat.setLocas(newLocas);
        hatManager.updateHat(cipherHat);
        hatManager.commit();
        TimberLogger.i(TAG, "Repaired cipher HAT %s loca -> %s", cipherHat.getId(), correctLoca);
    }

    /**
     * Finds remote locations (fudp://host:port or (sid)serviceId) in a HAT.
     */
    private List<String> findRemoteLocas(Hat hat) {
        List<String> out = new ArrayList<>();
        List<String> locas = hat.getLocas();
        if (locas == null) return out;
        for (String loca : locas) {
            if (loca != null && (loca.startsWith(FUDP_LOCATION_PREFIX) || loca.startsWith(SID_LOCATION_PREFIX))) {
                out.add(loca);
            }
        }
        return out;
    }
    
    /**
     * Gets the last error message.
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * Gets the last charged amount (in satoshis) from the most recent upload/download.
     */
    public long getLastCharged() {
        return lastCharged;
    }

    /**
     * Gets the plaintext symmetric key used in the most recent upload.
     * Callers should copy/consume this immediately; it is overwritten on the next upload.
     */
    public byte[] getLastSymkey() {
        return lastSymkey;
    }
}
