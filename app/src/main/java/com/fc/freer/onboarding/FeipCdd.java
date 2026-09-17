package com.fc.freer.onboarding;

import com.fc.fc_ajdk.data.feipData.Feip;

/**
 * FEIP0 §9: the coin days a carve has to destroy before any parser reads it.
 * <p>
 * Below {@link Feip#CDD_CHECK_HEIGHT} nothing is required. From it on, a FEIP carve that
 * destroys less than {@link Feip#CD_REQUIRED} is <b>silently ignored</b>: the transaction
 * confirms, the fee is gone, and nothing changes. That is why a newcomer's first carve has to
 * wait for their first coins to age, and why the checklist says so instead of offering a
 * button that pays for nothing. {@link com.fc.freer.tx.TxSender} enforces the same constants
 * as the last guard, so there is one rule, not two that agree.
 */
public final class FeipCdd {
    private FeipCdd() {}

    public static long activationHeight() {
        return Feip.CDD_CHECK_HEIGHT;
    }

    public static long minimum() {
        return Feip.CD_REQUIRED != null ? Feip.CD_REQUIRED : 0L;
    }

    /**
     * Whether a carve at {@code height} needs coin days. An unknown height counts as needing
     * them: waiting a day costs less than a carve no parser reads.
     */
    public static boolean isRequired(Long height) {
        return height == null || height >= activationHeight();
    }
}
