package com.fc.freer.im;

import android.content.Context;

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

        String consensusId = team.getConsensusId();
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

        boolean ok = dataSyncManager.downloadData(rawHat, null, outputFile);
        if (!ok) {
            lastError = dataSyncManager.getLastError();
        }
        return ok;
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
