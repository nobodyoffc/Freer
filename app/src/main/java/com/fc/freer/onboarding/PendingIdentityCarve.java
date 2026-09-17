package com.fc.freer.onboarding;

import java.util.Map;

/**
 * A CID, master or home carve this identity has broadcast and the chain does not show yet.
 * <p>
 * <b>Why keep it.</b> Until the block confirms, the chain still says "no CID" and "no DOCK",
 * so every screen reading the chain offers the carve again, and a second press pays a second
 * fee. This is what lets those screens and the checklist say "waiting for the chain" instead.
 * <p>
 * <b>Cleared by the chain, not by time.</b> {@link #isLanded} compares the record to what was
 * carved, and {@link PendingIdentityCarves#reconcile} runs on every refresh of the FID's
 * record. A carve still missing after {@link #OVERDUE_MS} is kept but reported overdue, so the
 * screens reopen the carve with a warning rather than wait forever.
 */
public final class PendingIdentityCarve {

    public static final long OVERDUE_MS = 24L * 60 * 60 * 1000;

    public enum Kind { CID, MASTER, HOME }

    public String fid;
    public Kind kind;
    /** For CID: the name carved, without the suffix the parser adds. */
    public String name;
    /** For MASTER: the FID named. */
    public String master;
    /** For HOME: the whole map carved. */
    public Map<String, String> home;
    public String txid;
    /** Epoch ms. */
    public long broadcastAt;

    public PendingIdentityCarve() {}

    public static PendingIdentityCarve cid(String fid, String name, String txid, long broadcastAt) {
        PendingIdentityCarve p = base(fid, Kind.CID, txid, broadcastAt);
        p.name = name;
        return p;
    }

    public static PendingIdentityCarve master(String fid, String master, String txid, long broadcastAt) {
        PendingIdentityCarve p = base(fid, Kind.MASTER, txid, broadcastAt);
        p.master = master;
        return p;
    }

    public static PendingIdentityCarve home(String fid, Map<String, String> home, String txid, long broadcastAt) {
        PendingIdentityCarve p = base(fid, Kind.HOME, txid, broadcastAt);
        p.home = home;
        return p;
    }

    private static PendingIdentityCarve base(String fid, Kind kind, String txid, long broadcastAt) {
        PendingIdentityCarve p = new PendingIdentityCarve();
        p.fid = fid;
        p.kind = kind;
        p.txid = txid;
        p.broadcastAt = broadcastAt;
        return p;
    }

    public boolean isOverdue(long nowMs) {
        return nowMs - broadcastAt >= OVERDUE_MS;
    }

    /**
     * Whether the chain's record shows this carve took effect.
     * <p>
     * A CID is matched by shape, since the suffix is the parser's choice: the carved name, an
     * underscore, then at least four characters that end the FID. A bare prefix test would take
     * {@code Al_x_vkUV} as the result of carving {@code Al}.
     */
    public boolean isLanded(LiveFidRecord record) {
        if (record == null || kind == null) return false;
        switch (kind) {
            case CID: {
                String cid = record.cid;
                if (name == null || cid == null || fid == null || !cid.startsWith(name + "_")) return false;
                String suffix = cid.substring(name.length() + 1);
                return suffix.length() >= CidFeip.BASE_SUFFIX_LENGTH && fid.endsWith(suffix);
            }
            case MASTER:
                // Any master counts: FEIP6 is write-once, so once the chain holds one this carve
                // can no longer change it, landed or not.
                return record.master != null && !record.master.trim().isEmpty();
            case HOME: {
                if (home == null) return true;
                Map<String, String> stored = record.home;
                for (Map.Entry<String, String> entry : home.entrySet()) {
                    if (stored == null || entry.getValue() == null
                            || !entry.getValue().equals(stored.get(entry.getKey()))) {
                        return false;
                    }
                }
                return true;
            }
            default:
                return false;
        }
    }
}
