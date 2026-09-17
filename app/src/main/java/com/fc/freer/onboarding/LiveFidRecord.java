package com.fc.freer.onboarding;

import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fapi.message.FapiResponse;
import com.fc.fc_ajdk.utils.ObjectUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live FID's on-chain record as one {@code base.freerByIds} reply gave it: the FID's own
 * fields plus the height the index answered at, which decides whether a carve needs coin days
 * ({@link FeipCdd}). Kept together so no decision mixes fields from different replies.
 * <p>
 * {@link #confirmed} holds only what the chain said <b>in this process</b>. A KeyInfo cached
 * from an earlier launch must not tick or untick checklist steps, and a refresh in flight does
 * not blank what is already known.
 */
public final class LiveFidRecord {

    public final String fid;
    /** False when the index has no record for this FID: it has never received anything. */
    public final boolean exists;
    public final Long balance;
    public final Long cd;
    public final String cid;
    public final List<String> usedCids;
    public final String guide;
    public final String master;
    public final Map<String, String> home;
    public final Long bestHeight;

    public LiveFidRecord(String fid, boolean exists, Long balance, Long cd, String cid,
                         List<String> usedCids, String guide, String master,
                         Map<String, String> home, Long bestHeight) {
        this.fid = fid;
        this.exists = exists;
        this.balance = balance;
        this.cd = cd;
        this.cid = cid;
        this.usedCids = usedCids;
        this.guide = guide;
        this.master = master;
        this.home = home;
        this.bestHeight = bestHeight;
    }

    public static LiveFidRecord of(String fid, Freer freer, Long bestHeight) {
        if (freer == null) {
            return new LiveFidRecord(fid, false, 0L, 0L, null, null, null, null, null, bestHeight);
        }
        return new LiveFidRecord(fid, true, freer.getBalance(), freer.getCd(), freer.getCid(),
                freer.getUsedCids(), freer.getGuide(), freer.getMaster(), freer.getHome(), bestHeight);
    }

    /** What {@link #fetch} returns: the record, and the raw Freer when the index has one. */
    public static final class Fetched {
        public final LiveFidRecord record;
        public final Freer freer;

        Fetched(LiveFidRecord record, Freer freer) {
            this.record = record;
            this.freer = freer;
        }
    }

    /**
     * Read {@code fid}'s record fresh from the chain. A 404 is an answer (the FID holds
     * nothing yet), not a failure. Must not run on the main thread.
     *
     * @throws Exception when the index could not be asked or did not answer
     */
    public static Fetched fetch(FapiClient client, String fid) throws Exception {
        if (client == null) throw new IllegalStateException("No FAPI client");
        if (fid == null || fid.isEmpty()) throw new IllegalArgumentException("No FID");
        FapiResponse response = client.freerByIdsResponse(Collections.singletonList(fid));
        if (response == null || response.getCode() == null) {
            throw new IllegalStateException("No reply from the index");
        }
        Long height = response.getBestHeight();
        if (response.getCode() == 404) {
            return new Fetched(of(fid, null, height), null);
        }
        if (response.getCode() != 0) {
            throw new IllegalStateException(response.getMessage() != null
                    ? response.getMessage() : "Index error " + response.getCode());
        }
        Freer freer = null;
        if (response.getData() != null) {
            Map<String, Freer> found = ObjectUtils.objectToMap(response.getData(), String.class, Freer.class);
            if (found != null) freer = found.get(fid);
        }
        return new Fetched(of(fid, freer, height), freer);
    }

    // ---- this session's confirmed records ----

    private static final Map<String, LiveFidRecord> confirmed = new ConcurrentHashMap<>();

    /** Record what the chain just said about {@code record.fid}. */
    public static void confirm(LiveFidRecord record) {
        if (record != null && record.fid != null) confirmed.put(record.fid, record);
    }

    /** The record the chain gave for {@code fid} in this process, or null if it has not answered yet. */
    public static LiveFidRecord confirmed(String fid) {
        return fid != null ? confirmed.get(fid) : null;
    }
}
