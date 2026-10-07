package com.fc.freer.utils;

import com.fc.freer.R;
import com.fc.fc_ajdk.fapi.client.FapiClient;

import java.util.function.Function;

/**
 * Decides whether carving a contact or secret must be an 'add' or an 'update', by asking the
 * chain about the item's id. The local onChain flag is not enough: older versions cleared it
 * when an on-chain item was saved locally, and a carve that has not confirmed yet is unknown to
 * the chain.
 *
 * <ul>
 *   <li>On chain, owned by the live FID and active: UPDATE, which keeps the id.</li>
 *   <li>On chain but deleted: blocked, since the parser rejects an update of a deleted item.</li>
 *   <li>Not on chain and local-only (onChain == false): ADD; the row is re-keyed to the txid.</li>
 *   <li>Not on chain but carved (onChain null = pending, or true): blocked. Its carve has not
 *       confirmed, and an add would create a second item. A pending carve older than
 *       {@link #PENDING_EXPIRY_MS} is taken as dropped instead, and carved as an ADD.</li>
 * </ul>
 */
public final class CarvePlan {

    /**
     * How long a broadcast carve may stay unconfirmed before it is taken as dropped. FCH blocks
     * come about once a minute and the app's fee confirms in the next block when blocks are not
     * full, so two hours is generous. The Mac app uses the same window (CarveExpiry).
     */
    public static final long PENDING_EXPIRY_MS = 2L * 60 * 60 * 1000;

    /** True when a carve broadcast at carveTime should no longer be waited for; an untimed one is. */
    public static boolean isExpired(Long carveTime) {
        return carveTime == null || System.currentTimeMillis() - carveTime > PENDING_EXPIRY_MS;
    }

    /** Pending (onChain null) and still worth waiting for. */
    public static boolean isWaitingForCarve(Boolean onChain, Long carveTime) {
        return onChain == null && !isExpired(carveTime);
    }

    /** Local only, or a pending carve that has expired: it may be saved locally or carved anew. */
    public static boolean isLocalOnly(Boolean onChain, Long carveTime) {
        return Boolean.FALSE.equals(onChain) || (onChain == null && isExpired(carveTime));
    }

    public enum Op { ADD, UPDATE }

    /** The op to carve, or null with the string resource explaining why not. */
    public final Op op;
    public final int blockedMessage;

    private CarvePlan(Op op, int blockedMessage) {
        this.op = op;
        this.blockedMessage = blockedMessage;
    }

    public boolean isUpdate() {
        return op == Op.UPDATE;
    }

    /** Makes a network call: run it off the UI thread. */
    public static <T> CarvePlan decide(FapiClient fapiClient, String entity, Class<T> clazz, String id,
                                       Boolean localOnChain, Long carveTime, String liveFid,
                                       Function<T, String> owner, Function<T, Boolean> active) {
        if (fapiClient == null) return blocked(R.string.toast_failed_check_on_chain);

        T onChain = id == null ? null : fapiClient.entityById(entity, clazz, id);
        if (onChain == null && id != null && !fapiClient.isDataNoFound()) {
            return blocked(R.string.toast_failed_check_on_chain);
        }
        if (onChain != null && liveFid != null && liveFid.equals(owner.apply(onChain))) {
            return Boolean.TRUE.equals(active.apply(onChain))
                    ? new CarvePlan(Op.UPDATE, 0)
                    : blocked(R.string.toast_deleted_on_chain_recover_first);
        }
        return isLocalOnly(localOnChain, carveTime)
                ? new CarvePlan(Op.ADD, 0)
                : blocked(R.string.toast_carve_not_confirmed_wait);
    }

    private static CarvePlan blocked(int message) {
        return new CarvePlan(null, message);
    }
}
