package com.fc.freer.onboarding;

import java.util.Objects;

/**
 * Where one checklist step stands. Immutable; compare with {@link #equals}.
 */
public final class OnboardingStatus {

    public enum Kind {
        DONE,
        SKIPPED,
        /** Can be done now. */
        OPEN,
        /** Cannot be done yet: an earlier step has to happen first ({@link #waitStep}). */
        WAITING_STEP,
        /** Cannot be done yet: the coins have not aged enough to pay FEIP0's CDD. */
        WAITING_COIN_DAYS,
        /**
         * Carved and broadcast; the chain does not show it yet. Nothing to do but wait, and
         * offering the carve again would charge for it twice.
         */
        PENDING,
        /**
         * Carved over a day ago and still not on the chain: the transaction most likely
         * failed. Open again, with the txid to check.
         */
        STALLED,
        /** Depends on the chain, which has not answered yet. */
        UNKNOWN
    }

    public static final OnboardingStatus DONE = new OnboardingStatus(Kind.DONE, null, 0, 0, null, null);
    public static final OnboardingStatus SKIPPED = new OnboardingStatus(Kind.SKIPPED, null, 0, 0, null, null);
    public static final OnboardingStatus OPEN = new OnboardingStatus(Kind.OPEN, null, 0, 0, null, null);
    public static final OnboardingStatus UNKNOWN = new OnboardingStatus(Kind.UNKNOWN, null, 0, 0, null, null);

    public final Kind kind;
    /** For {@link Kind#WAITING_STEP}. */
    public final OnboardingStep waitStep;
    /** For {@link Kind#WAITING_COIN_DAYS}: coin days held now. */
    public final long have;
    /** For {@link Kind#WAITING_COIN_DAYS}: coin days a carve must destroy. */
    public final long need;
    /**
     * For {@link Kind#WAITING_COIN_DAYS}: a rough forecast of days left at the current
     * balance, or null when there is no balance to age.
     */
    public final Integer days;
    /** For {@link Kind#PENDING} and {@link Kind#STALLED}. */
    public final String txid;

    private OnboardingStatus(Kind kind, OnboardingStep waitStep, long have, long need, Integer days, String txid) {
        this.kind = kind;
        this.waitStep = waitStep;
        this.have = have;
        this.need = need;
        this.days = days;
        this.txid = txid;
    }

    public static OnboardingStatus waitingFor(OnboardingStep step) {
        return new OnboardingStatus(Kind.WAITING_STEP, step, 0, 0, null, null);
    }

    public static OnboardingStatus coinDays(long have, long need, Integer days) {
        return new OnboardingStatus(Kind.WAITING_COIN_DAYS, null, have, need, days, null);
    }

    public static OnboardingStatus pending(String txid) {
        return new OnboardingStatus(Kind.PENDING, null, 0, 0, null, txid);
    }

    public static OnboardingStatus stalled(String txid) {
        return new OnboardingStatus(Kind.STALLED, null, 0, 0, null, txid);
    }

    /** Done or skipped: nothing more will be asked for this step. */
    public boolean isSettled() {
        return kind == Kind.DONE || kind == Kind.SKIPPED;
    }

    public boolean isWaiting() {
        return kind == Kind.WAITING_STEP || kind == Kind.WAITING_COIN_DAYS;
    }

    /**
     * Whether the step's actions are offered: not while it is settled, unknown, or pending on
     * a carve already paid for.
     */
    public boolean isActionable() {
        switch (kind) {
            case OPEN:
            case WAITING_STEP:
            case WAITING_COIN_DAYS:
            case STALLED:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OnboardingStatus)) return false;
        OnboardingStatus that = (OnboardingStatus) o;
        return kind == that.kind && waitStep == that.waitStep && have == that.have
                && need == that.need && Objects.equals(days, that.days)
                && Objects.equals(txid, that.txid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, waitStep, have, need, days, txid);
    }

    @Override
    public String toString() {
        switch (kind) {
            case WAITING_STEP: return "WAITING_STEP(" + waitStep + ")";
            case WAITING_COIN_DAYS: return "WAITING_COIN_DAYS(" + have + "/" + need + ", days=" + days + ")";
            case PENDING:
            case STALLED: return kind + "(" + txid + ")";
            default: return kind.name();
        }
    }
}
