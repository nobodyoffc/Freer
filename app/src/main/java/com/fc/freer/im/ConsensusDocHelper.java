package com.fc.freer.im;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.data.fcData.DiskItem;
import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.fapi.client.HomeServiceResolver;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.data.DataSyncManager;
import com.fc.freer.manager.HatManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the lifecycle of a team's <b>public</b> consensus document.
 * <p>
 * Unlike chat file shares, a consensus document is uploaded <b>unencrypted</b> and
 * <b>permanent</b> so anyone considering the team can read it. It is content-addressed:
 * {@code consensusId = sha256x2(documentBytes)} doubles as the raw HAT id and is committed
 * on-chain in {@code Team.consensusId}. The document's location is discovered entirely from
 * the on-chain {@code Team.home} map (plaintext {@code (sid)<diskSid>}), so no out-of-band
 * HAT delivery is needed — and {@link DataSyncManager} verifies the downloaded bytes hash
 * back to {@code consensusId} before trusting them.
 */
public class ConsensusDocHelper {
    private static final String TAG = "ConsensusDocHelper";

    private final HatManager hatManager;
    private final DataSyncManager dataSyncManager;
    private String lastError;

    public ConsensusDocHelper(Context context, HatManager hatManager) {
        this.hatManager = hatManager;
        this.dataSyncManager = new DataSyncManager(context, hatManager);
    }

    public String getLastError() {
        return lastError;
    }

    /**
     * Upload a public consensus document to the given DISK (permanent, unencrypted).
     * Must be called on a background thread.
     *
     * @param doc      The consensus document file
     * @param diskSid  The DISK service SID to upload to (the same SID recorded in the team's home)
     * @return the consensusId (= {@code sha256x2(doc)} DID), or null on failure
     */
    public String uploadConsensusDoc(File doc, String diskSid) {
        return uploadConsensusDoc(doc, diskSid, null);
    }

    /**
     * @param progressCallback receives the cumulative bytes uploaded, for UI progress
     */
    public String uploadConsensusDoc(File doc, String diskSid, java.util.function.LongConsumer progressCallback) {
        if (doc == null || !doc.exists()) {
            lastError = "Consensus document does not exist";
            return null;
        }
        if (diskSid == null || diskSid.isEmpty()) {
            lastError = "No DISK service for consensus upload";
            return null;
        }
        try {
            byte[] hash = Hash.sha256x2Bytes(doc);
            if (hash == null) {
                lastError = "Failed to hash consensus document";
                return null;
            }
            String consensusId = Hex.toHex(hash);

            Hat rawHat = hatManager.getHatById(consensusId);
            if (rawHat == null) {
                rawHat = new Hat();
                rawHat.setId(consensusId);
                rawHat.setName(doc.getName());
                rawHat.setSize(doc.length());
                rawHat.setBorn(System.currentTimeMillis());
                rawHat.setLast(System.currentTimeMillis());
                rawHat.setState(Hat.DataState.ACTIVE);
                rawHat.setTypes(Collections.singletonList("text/plain"));
                rawHat.setDesc("Team consensus document");
                hatManager.addHat(rawHat);
            }

            DiskItem item = dataSyncManager.uploadRawData(doc, rawHat, true, diskSid, progressCallback);
            if (item == null) {
                lastError = dataSyncManager.getLastError();
                TimberLogger.e(TAG, "Consensus upload failed: %s", lastError);
                return null;
            }
            TimberLogger.i(TAG, "Uploaded consensus document: consensusId=%s diskSid=%s", consensusId, diskSid);
            return consensusId;
        } catch (Exception e) {
            lastError = "Consensus upload failed: " + e.getMessage();
            TimberLogger.e(TAG, lastError);
            return null;
        }
    }

    /**
     * Download a team's public consensus document into {@code outputFile}.
     * Resolves the DISK SID from the on-chain {@code Team.home}, then fetches by consensusId;
     * {@link DataSyncManager#downloadData} verifies the bytes hash back to the consensusId.
     * Must be called on a background thread.
     *
     * @return true if the document was downloaded and integrity-verified
     */
    public boolean downloadConsensusDoc(Team team, File outputFile) {
        if (team == null || team.getConsensusId() == null || team.getConsensusId().isEmpty()) {
            lastError = "Team has no consensus document";
            return false;
        }
        String diskSid = resolveTeamDiskSid(team);
        if (diskSid == null) {
            lastError = "Team has no DISK service in its home; cannot locate consensus";
            return false;
        }
        return downloadDoc(team.getConsensusId(), diskSid, outputFile);
    }

    /**
     * Download the document with the given content id from an explicit DISK.
     * <p>
     * Unlike {@link #downloadConsensusDoc(Team, File)} this does not read the id or the DISK
     * from the team, so it can fetch a <b>superseded</b> consensus — whose id is no longer on
     * chain, and which may still live on a DISK the team has since moved away from.
     * The bytes are integrity-checked against {@code consensusId} by {@link DataSyncManager}.
     * Must be called on a background thread.
     */
    public boolean downloadDoc(String consensusId, String diskSid, File outputFile) {
        if (consensusId == null || consensusId.isEmpty()) {
            lastError = "No consensus id to download";
            return false;
        }
        if (diskSid == null || diskSid.isEmpty()) {
            lastError = "No DISK service to download the consensus from";
            return false;
        }
        Hat rawHat = hatManager.getHatById(consensusId);
        if (rawHat == null) {
            rawHat = new Hat();
            rawHat.setId(consensusId);
            rawHat.setBorn(System.currentTimeMillis());
            rawHat.setState(Hat.DataState.ACTIVE);
            hatManager.addHat(rawHat);
        }

        // Point the HAT at the team's DISK via a stable (sid) location.
        List<String> locas = rawHat.getLocas();
        if (locas == null) locas = new ArrayList<>();
        String loca = DataSyncManager.SID_LOCATION_PREFIX + diskSid;
        if (!locas.contains(loca)) locas.add(loca);
        rawHat.setLocas(locas);
        hatManager.updateHat(rawHat);
        hatManager.commit();

        boolean ok = dataSyncManager.downloadData(rawHat, null, outputFile);
        if (!ok) {
            lastError = dataSyncManager.getLastError();
        }
        return ok;
    }

    /**
     * Make sure the document is stored on {@code targetDiskSid}, wherever it currently lives, so
     * that a member holding only the team's DISK SID can fetch it.
     * <p>
     * Tried in order, cheapest first: already on the target; local bytes on this device; pulled
     * from {@code fallbackDiskSid} (the DISK the team is leaving) and pushed across. Checking the
     * target first matters — carving is a payment, so re-uploading a document that is already
     * there costs the owner money for nothing.
     * <p>
     * {@code fallbackDiskSid} may be null or equal to the target: a team that never had a DISK
     * has nowhere to be pulled from, which is a plain "not found", not an error worth dressing up.
     * Must be called on a background thread.
     *
     * @return true when the document is on {@code targetDiskSid} afterwards
     */
    public boolean ensureDocOnDisk(Context context, String consensusId, String fallbackDiskSid,
                                   String targetDiskSid, java.util.function.LongConsumer progressCallback) {
        if (consensusId == null || consensusId.isEmpty()) {
            lastError = "No consensus document to place";
            return false;
        }
        if (targetDiskSid == null || targetDiskSid.isEmpty()) {
            lastError = "No target DISK for the consensus document";
            return false;
        }

        if (isDocOnDisk(consensusId, targetDiskSid)) return true;

        File local = resolveLocalDoc(context, hatManager, consensusId);
        if (local != null) {
            return uploadConsensusDoc(local, targetDiskSid, progressCallback) != null;
        }

        if (fallbackDiskSid == null || fallbackDiskSid.isEmpty()
                || fallbackDiskSid.equals(targetDiskSid)) {
            lastError = "The consensus document is not on this device and no other DISK holds it";
            return false;
        }

        File temp = null;
        try {
            temp = File.createTempFile("consensus_migrate_", ".tmp", context.getCacheDir());
            if (!downloadDoc(consensusId, fallbackDiskSid, temp)) return false;
            return uploadConsensusDoc(temp, targetDiskSid, progressCallback) != null;
        } catch (Exception e) {
            lastError = "Failed to place the consensus document: " + e.getMessage();
            TimberLogger.e(TAG, lastError);
            return false;
        } finally {
            if (temp != null && temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    /**
     * Is the document already stored on this specific DISK?
     * <p>
     * Asks that one DISK only — a copy sitting on some other service the HAT happens to know
     * about is no use to a member, who can only resolve the SID published in the team's home.
     * Cheap next to re-carving: presence is a query, carving is a payment.
     * Must be called on a background thread.
     */
    public boolean isDocOnDisk(String consensusId, String diskSid) {
        if (consensusId == null || consensusId.isEmpty()) return false;
        if (diskSid == null || diskSid.isEmpty()) return false;
        try {
            // A transient HAT carrying just this one location, so the check cannot be satisfied
            // by another loca on the stored HAT — and so nothing is written back.
            Hat probe = new Hat();
            probe.setId(consensusId);
            probe.setLocas(Collections.singletonList(DataSyncManager.SID_LOCATION_PREFIX + diskSid));
            return dataSyncManager.checkDataOnDisk(probe) != null;
        } catch (Exception e) {
            TimberLogger.w(TAG, "Consensus presence check failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Resolve the local, unencrypted bytes for a data DID, or null when they are not on this
     * device. Looks for a {@code local://} loca on the HAT, then the default
     * {@code files/data/<did>} file — the two places {@link com.fc.freer.data.DataActivity}
     * writes imported data to.
     */
    public static File resolveLocalDoc(Context context, HatManager hatManager, String did) {
        if (did == null || did.isEmpty()) return null;
        Hat hat = hatManager.getHatById(did);
        if (hat != null && hat.getLocas() != null) {
            for (String loca : hat.getLocas()) {
                if (loca != null && loca.startsWith(DataSyncManager.LOCAL_LOCATION_PREFIX)) {
                    File f = new File(loca.substring(DataSyncManager.LOCAL_LOCATION_PREFIX.length()));
                    if (f.exists()) return f;
                }
            }
        }
        File defaultFile = new File(new File(context.getFilesDir(), "data"), did);
        return defaultFile.exists() ? defaultFile : null;
    }

    /**
     * Keep a verified download: move it into the data store under its content id and register it,
     * so the bytes that were paid for and hash-checked are read locally next time instead of being
     * fetched again — and so the member can open the file from Data like any other.
     * <p>
     * Only the caller's own temp file is moved; the store's files are never touched here.
     * Must be called on a background thread.
     *
     * @return the stored file, or null on failure (see {@link #getLastError()})
     */
    public File adoptDownloadedDoc(Context context, File temp, String did, String name, String mimeType) {
        if (temp == null || !temp.exists()) {
            lastError = "No downloaded document to keep";
            return null;
        }
        if (did == null || did.isEmpty()) {
            lastError = "No content id for the downloaded document";
            return null;
        }
        try {
            File dataDir = new File(context.getFilesDir(), "data");
            //noinspection ResultOfMethodCallIgnored
            dataDir.mkdirs();
            File localFile = new File(dataDir, did);
            if (localFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            } else if (!temp.renameTo(localFile)) {
                // Fallback for a cross-filesystem rename: the cache and the files directory are
                // usually the same volume, but nothing promises it.
                copy(temp, localFile);
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
            registerLocalDoc(did, localFile, name, mimeType);
            return localFile;
        } catch (Exception e) {
            lastError = "Failed to keep the downloaded document: " + e.getMessage();
            TimberLogger.e(TAG, lastError);
            return null;
        }
    }

    /**
     * A cached copy of a stored document under a readable name, for handing to another app.
     * <p>
     * The store names its files by their hash and nothing else, so an app receiving one has no
     * extension to go on and no name worth showing. The copy lives in the cache, which the
     * FileProvider already serves, and the store's own file is left alone.
     */
    public static File namedCopy(Context context, File source, String name) throws java.io.IOException {
        File dir = new File(context.getCacheDir(), "consensus");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File target = new File(dir, name);
        if (target.exists() && target.length() == source.length()) return target;
        copy(source, target);
        return target;
    }

    private static void copy(File from, File to) throws java.io.IOException {
        try (InputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    /** A document imported into the local HAT store: its content id and the file holding it. */
    public static class ImportedDoc {
        public final String did;
        public final File file;
        ImportedDoc(String did, File file) {
            this.did = did;
            this.file = file;
        }
    }

    /**
     * Import a document picked through the system file picker into the local HAT store, so it can
     * be uploaded as a consensus. Mirrors {@link com.fc.freer.data.DataActivity}'s import: stream
     * to {@code files/data/}, content-address with {@code sha256x2}, register a HAT carrying a
     * {@code local://} loca. Re-importing the same bytes is idempotent — the id is the hash.
     * Must be called on a background thread.
     *
     * @return the imported document, or null on failure (see {@link #getLastError()})
     */
    public ImportedDoc importDocFromUri(Context context, Uri uri) {
        if (uri == null) {
            lastError = "No document selected";
            return null;
        }
        File dataDir = new File(context.getFilesDir(), "data");
        //noinspection ResultOfMethodCallIgnored
        dataDir.mkdirs();
        File temp = null;
        try {
            String fileName = queryDisplayName(context, uri);
            String mimeType = context.getContentResolver().getType(uri);

            temp = File.createTempFile("consensus_", null, dataDir);
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    lastError = "Cannot open the selected document";
                    return null;
                }
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
            }

            byte[] didBytes = Hash.sha256x2Bytes(temp);
            if (didBytes == null) {
                lastError = "Failed to hash the selected document";
                return null;
            }
            String did = Hex.toHex(didBytes);

            File localFile = new File(dataDir, did);
            if (localFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            } else if (!temp.renameTo(localFile)) {
                // Fallback for a cross-filesystem rename.
                try (InputStream in = new FileInputStream(temp);
                     FileOutputStream out = new FileOutputStream(localFile)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
            temp = null;

            registerLocalDoc(did, localFile, fileName, mimeType);

            TimberLogger.i(TAG, "Imported consensus document: did=%s", did);
            return new ImportedDoc(did, localFile);
        } catch (Exception e) {
            lastError = "Failed to import the document: " + e.getMessage();
            TimberLogger.e(TAG, lastError);
            return null;
        } finally {
            if (temp != null && temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    /**
     * Write text into the local data store as a content-addressed document, so it can be opened
     * in the text editor and later uploaded as a consensus. Used to seed a new team with an
     * editable consensus template.
     * Must be called on a background thread.
     *
     * @return the stored document, or null on failure (see {@link #getLastError()})
     */
    public ImportedDoc createDocFromText(Context context, String text, String name) {
        if (text == null) {
            lastError = "No document text";
            return null;
        }
        try {
            byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] didBytes = Hash.sha256x2(data);
            if (didBytes == null) {
                lastError = "Failed to hash the document";
                return null;
            }
            String did = Hex.toHex(didBytes);

            File dataDir = new File(context.getFilesDir(), "data");
            //noinspection ResultOfMethodCallIgnored
            dataDir.mkdirs();
            File localFile = new File(dataDir, did);
            if (!localFile.exists()) {
                try (FileOutputStream out = new FileOutputStream(localFile)) {
                    out.write(data);
                }
            }
            registerLocalDoc(did, localFile, name, "text/plain");
            TimberLogger.i(TAG, "Created consensus document from text: did=%s", did);
            return new ImportedDoc(did, localFile);
        } catch (Exception e) {
            lastError = "Failed to create the document: " + e.getMessage();
            TimberLogger.e(TAG, lastError);
            return null;
        }
    }

    /**
     * Register (or refresh) the HAT for a document held in the local data store.
     * <p>
     * A name and a type are filled in when the HAT has none — a HAT created by
     * {@link #downloadDoc} carries only the id, and the name is what later gives an
     * extensionless, hash-named file something for another app to go on.
     */
    public void registerLocalDoc(String did, File localFile, String name, String mimeType) {
        Hat hat = hatManager.getHatById(did);
        if (hat == null) {
            hat = new Hat();
            hat.setId(did);
            hat.setBorn(System.currentTimeMillis());
            hat.setState(Hat.DataState.ACTIVE);
            hatManager.addHat(hat);
        }
        if (name != null && (hat.getName() == null || hat.getName().isEmpty())) hat.setName(name);
        if (mimeType != null && (hat.getTypes() == null || hat.getTypes().isEmpty())) {
            hat.setTypes(Collections.singletonList(mimeType));
        }
        hat.setSize(localFile.length());
        hat.setLast(System.currentTimeMillis());
        List<String> locas = hat.getLocas();
        if (locas == null) locas = new ArrayList<>();
        String loca = DataSyncManager.LOCAL_LOCATION_PREFIX + localFile.getAbsolutePath();
        if (!locas.contains(loca)) locas.add(loca);
        hat.setLocas(locas);
        hatManager.updateHat(hat);
        hatManager.commit();
    }

    private static String queryDisplayName(Context context, Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) name = cursor.getString(index);
                }
            } catch (Exception ignored) {
                // Fall through to the path-derived name.
            }
        }
        if (name == null) {
            name = uri.getPath();
            if (name != null) {
                int cut = name.lastIndexOf('/');
                if (cut != -1) name = name.substring(cut + 1);
            }
        }
        return name;
    }

    /** Resolve the plaintext DISK SID from a team's on-chain home map. */
    public static String resolveTeamDiskSid(Team team) {
        if (team == null) return null;
        Map<String, String> home = team.getHome();
        if (home == null) return null;
        String value = home.get(Constants.DISK_NO1_NRC7);
        if (value == null || value.isEmpty()) return null;
        return HomeServiceResolver.extractSid(value);
    }
}
