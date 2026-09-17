package com.fc.freer.onboarding;

import com.fc.fc_ajdk.data.feipData.CidOpData;

import java.util.List;
import java.util.Objects;

/**
 * FEIP3 {@code CID}: a human-readable name for a FID.
 * <p>
 * <b>The carve names a name, not a CID.</b> The parser appends the suffix (the FID's last four
 * characters, one more for every collision with a different FID), so what the user will be
 * called is decided on the indexer. {@link #preview} runs the same rule against the chain now,
 * which is the best a client can say before paying.
 */
public final class CidFeip {
    private CidFeip() {}

    /** The spec's limit on {@code usedCids}; a fifth distinct CID is ignored. */
    public static final int MAX_USED_CIDS = 4;
    public static final int BASE_SUFFIX_LENGTH = 4;

    /** Which FID has ever used a CID, or null. */
    public interface OwnerLookup {
        String ownerOf(String cid) throws Exception;
    }

    public static boolean isGoodName(String name) {
        return CidOpData.isGoodName(name);
    }

    /** What registering a name would do, decided the way the parser decides it. */
    public static final class Preview {
        public enum Kind {
            /** A new CID for this FID. */
            NEW,
            /** One of this FID's own earlier CIDs, made current again. Never counts toward the limit. */
            REACTIVATE,
            /** Would be a fifth used CID, which the parser ignores. */
            LIMIT_REACHED,
            /** Every suffix up to the whole FID is taken by somebody else. */
            UNAVAILABLE
        }

        public final Kind kind;
        public final String cid;

        public Preview(Kind kind, String cid) {
            this.kind = kind;
            this.cid = cid;
        }

        /** Whether a carve for this preview would be read. */
        public boolean isCarvable() {
            return kind == Kind.NEW || kind == Kind.REACTIVATE;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Preview)) return false;
            Preview that = (Preview) o;
            return kind == that.kind && Objects.equals(cid, that.cid);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, cid);
        }

        @Override
        public String toString() {
            return kind + (cid != null ? "(" + cid + ")" : "");
        }
    }

    /**
     * Run FEIP3's rules for {@code name}. {@code ownUsedCids} must be read fresh from the chain:
     * a CID registered from another device since the last refresh counts toward the limit.
     *
     * @throws IllegalArgumentException when the name breaks rule 1
     * @throws Exception                when {@code ownerOf} fails
     */
    public static Preview preview(String name, String fid, List<String> ownUsedCids,
                                  OwnerLookup ownerOf) throws Exception {
        if (!isGoodName(name)) throw new IllegalArgumentException("Bad CID name: " + name);
        int used = ownUsedCids != null ? ownUsedCids.size() : 0;
        for (int length = Math.min(BASE_SUFFIX_LENGTH, fid.length()); length <= fid.length(); length++) {
            String cid = name + "_" + fid.substring(fid.length() - length);
            if (ownUsedCids != null && ownUsedCids.contains(cid)) {
                return new Preview(Preview.Kind.REACTIVATE, cid);
            }
            String owner = ownerOf.ownerOf(cid);
            if (owner != null && !owner.equals(fid)) continue;
            return new Preview(used >= MAX_USED_CIDS ? Preview.Kind.LIMIT_REACHED : Preview.Kind.NEW, cid);
        }
        return new Preview(Preview.Kind.UNAVAILABLE, null);
    }
}
