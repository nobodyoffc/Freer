package com.fc.freer.onboarding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The getting-started checklist, decided.
 * <p>
 * <b>Every tick comes from state, never from the user ticking it.</b> A backup is the user's
 * word because nothing else can know; everything else is on the chain or on this phone, so a
 * step done from another device, or before this checklist existed, shows as done without being
 * told.
 * <p>
 * <b>Order is dependency.</b> Everything after the first FCH is a carve, and past
 * {@link FeipCdd#activationHeight()} a carve destroying less than one coin day is paid for and
 * ignored, so those steps wait for the coins to age rather than offer a button.
 */
public final class Onboarding {

    public static final class Item {
        public final OnboardingStep step;
        public final OnboardingStatus status;

        Item(OnboardingStep step, OnboardingStatus status) {
            this.step = step;
            this.status = status;
        }
    }

    private final List<Item> items;
    private final String guide;
    private final boolean canCarve;

    public Onboarding(OnboardingFacts facts) {
        LiveFidRecord chain = facts.chain;
        guide = chain != null && chain.guide != null && !chain.guide.isEmpty() ? chain.guide : null;

        // Null while the chain has not answered. A FID that spent everything still has a guide.
        final Boolean funded = chain == null ? null
                : (chain.balance != null && chain.balance > 0) || guide != null;
        final OnboardingStatus cdWait = chain != null ? coinDayWait(chain) : null;
        canCarve = Boolean.TRUE.equals(funded) && cdWait == null;

        List<Item> list = new ArrayList<>();
        list.add(new Item(OnboardingStep.BACKUP_PRIKEY,
                facts.prikeyBackedUp ? OnboardingStatus.DONE : OnboardingStatus.OPEN));
        list.add(new Item(OnboardingStep.FIRST_FCH, firstFchStep(facts, funded)));

        String cid = chain != null && chain.cid != null ? chain.cid.trim() : "";
        list.add(new Item(OnboardingStep.REGISTER_CID,
                carveStep(facts, OnboardingStep.REGISTER_CID, !cid.isEmpty(), funded, cdWait)));

        boolean homeSet = chain != null
                && HomeFeip.declares("DOCK", chain.home) && HomeFeip.declares("DISK", chain.home);
        list.add(new Item(OnboardingStep.SET_HOME,
                carveStep(facts, OnboardingStep.SET_HOME, homeSet, funded, cdWait)));

        if (facts.skipped.contains(OnboardingStep.ADD_GUIDE)) {
            list.add(new Item(OnboardingStep.ADD_GUIDE, OnboardingStatus.SKIPPED));
        } else if (facts.guideIsContact) {
            list.add(new Item(OnboardingStep.ADD_GUIDE, OnboardingStatus.DONE));
        } else if (Boolean.FALSE.equals(funded)) {
            list.add(new Item(OnboardingStep.ADD_GUIDE, OnboardingStatus.waitingFor(OnboardingStep.FIRST_FCH)));
        } else if (funded == null) {
            list.add(new Item(OnboardingStep.ADD_GUIDE, OnboardingStatus.UNKNOWN));
        } else if (guide != null) {
            // A contact is local, so this never waits on coin age. Funded with no guide on
            // record: there is nobody to add, so the step is left out.
            list.add(new Item(OnboardingStep.ADD_GUIDE, OnboardingStatus.OPEN));
        }

        if (facts.skipped.contains(OnboardingStep.JOIN_SQUARE)) {
            list.add(new Item(OnboardingStep.JOIN_SQUARE, OnboardingStatus.SKIPPED));
        } else {
            list.add(new Item(OnboardingStep.JOIN_SQUARE,
                    carveStep(facts, OnboardingStep.JOIN_SQUARE, facts.joinedSquare, funded, cdWait)));
        }
        items = Collections.unmodifiableList(list);
    }

    /**
     * Pending after an ask, until the coins come. An ask costs nothing, so a day on it just
     * reopens the step instead of warning that something failed.
     */
    private static OnboardingStatus firstFchStep(OnboardingFacts facts, Boolean funded) {
        if (funded == null) return OnboardingStatus.UNKNOWN;
        if (funded) return OnboardingStatus.DONE;
        OnboardingFacts.Pending asked = facts.pending.get(OnboardingStep.FIRST_FCH);
        return asked != null && !asked.overdue ? OnboardingStatus.pending(null) : OnboardingStatus.OPEN;
    }

    /**
     * A carve-backed step: done by the chain, else pending on a carve already broadcast, else
     * blocked by the first FCH, else by coin age. <b>Done only when the chain says so</b>, never
     * on broadcast: a carve can still be dropped, or confirmed and ignored, and a step ticked
     * early could complete the checklist and hide it before that shows.
     */
    private static OnboardingStatus carveStep(OnboardingFacts facts, OnboardingStep step, boolean done,
                                              Boolean funded, OnboardingStatus cdWait) {
        if (funded == null) return OnboardingStatus.UNKNOWN;
        if (done) return OnboardingStatus.DONE;
        OnboardingFacts.Pending pending = facts.pending.get(step);
        if (pending != null) {
            return pending.overdue ? OnboardingStatus.stalled(pending.txid) : OnboardingStatus.pending(pending.txid);
        }
        if (!funded) return OnboardingStatus.waitingFor(OnboardingStep.FIRST_FCH);
        if (cdWait != null) return cdWait;
        return OnboardingStatus.OPEN;
    }

    /** The steps that apply, in order. */
    public List<Item> getItems() {
        return items;
    }

    /** The FID that funded this one, when the chain knows it. */
    public String getGuide() {
        return guide;
    }

    /** Whether a carve from this FID would be read now. */
    public boolean canCarve() {
        return canCarve;
    }

    /** The status of {@code step}, or null when the step does not apply. */
    public OnboardingStatus statusOf(OnboardingStep step) {
        for (Item item : items) {
            if (item.step == step) return item.status;
        }
        return null;
    }

    /**
     * The first step there is something to do about: the one the card expands. A pending carve
     * is passed over, since it only needs waiting for.
     */
    public Item current() {
        for (Item item : items) {
            OnboardingStatus.Kind kind = item.status.kind;
            if (item.status.isSettled() || kind == OnboardingStatus.Kind.UNKNOWN
                    || kind == OnboardingStatus.Kind.PENDING) continue;
            return item;
        }
        return null;
    }

    public int settledCount() {
        int n = 0;
        for (Item item : items) if (item.status.isSettled()) n++;
        return n;
    }

    public boolean isComplete() {
        return settledCount() == items.size();
    }

    /** Whether a required step is still to do, as far as anyone knows. */
    public boolean hasRequiredStepOpen() {
        for (Item item : items) {
            if (!item.step.isSkippable() && !item.status.isSettled()
                    && item.status.kind != OnboardingStatus.Kind.UNKNOWN) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether to draw the checklist.
     * <p>
     * A required step still open always shows it. The two optional steps only keep it up for
     * an identity that went through the checklist ({@code started}): somebody who registered a
     * CID and set their home long before this existed has plainly finished getting started,
     * and a card appearing to ask them to join a square would be a nag, not help.
     */
    public boolean shouldShow(boolean started) {
        if (hasRequiredStepOpen()) return true;
        return started && !isComplete();
    }

    /**
     * FEIP0's CDD rule against this FID's record: a {@link OnboardingStatus.Kind#WAITING_COIN_DAYS}
     * status, or null when it is met or does not apply at this height.
     */
    public static OnboardingStatus coinDayWait(LiveFidRecord record) {
        if (!FeipCdd.isRequired(record.bestHeight)) return null;
        long have = record.cd != null ? record.cd : 0L;
        long need = FeipCdd.minimum();
        if (have >= need) return null;
        // A CD is one coin held one day, so the balance in coins is the rate coin days accrue at.
        double coins = (record.balance != null ? record.balance : 0L) / 100_000_000d;
        Integer days = coins > 0 ? Math.max(1, (int) Math.ceil((need - have) / coins)) : null;
        return OnboardingStatus.coinDays(have, need, days);
    }
}
