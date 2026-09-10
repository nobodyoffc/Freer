package com.fc.freer.manager;

import com.fc.fc_ajdk.data.fchData.Cash;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToLongFunction;

/**
 * Picks TX inputs whose value covers the payment plus fee and whose CD covers any required
 * CD, while destroying as little CD as it can.
 */
public final class CashSelector {

    public static final class Selection {
        private final List<Cash> inputs;
        private final boolean enoughValue;
        private final boolean enoughCd;

        private Selection(List<Cash> inputs, boolean enoughValue, boolean enoughCd) {
            this.inputs = inputs;
            this.enoughValue = enoughValue;
            this.enoughCd = enoughCd;
        }

        public List<Cash> getInputs() {
            return inputs;
        }

        public boolean hasEnoughValue() {
            return enoughValue;
        }

        public boolean hasEnoughCd() {
            return enoughCd;
        }
    }

    private CashSelector() {
    }

    /**
     * @param spendable    valid, unconflicted, unlocked cash whose cd is its CD at the current height
     * @param payValue     total output value in satoshi
     * @param requiredCd   CD the TX must destroy, 0 if none
     * @param feeForInputs fee in satoshi for the TX with the given number of inputs
     */
    public static Selection select(List<Cash> spendable, long payValue, long requiredCd,
                                   IntToLongFunction feeForInputs) {
        List<Cash> remaining = new ArrayList<>();
        for (Cash cash : spendable) {
            if (cash.getValue() != null && cash.getValue() > 0) remaining.add(cash);
        }

        List<Cash> picked = new ArrayList<>();
        long totalValue = 0;
        long totalCd = 0;

        // Required CD first: take the smallest cash that closes the gap alone, or the largest
        // when none does, so the CD destroyed overshoots the requirement as little as possible.
        if (requiredCd > 0) {
            List<Cash> withCd = new ArrayList<>();
            for (Cash cash : remaining) {
                if (cdOf(cash) > 0) withCd.add(cash);
            }
            withCd.sort((a, b) -> Long.compare(cdOf(a), cdOf(b)));
            while (totalCd < requiredCd && !withCd.isEmpty()) {
                long gap = requiredCd - totalCd;
                Cash choice = withCd.get(withCd.size() - 1);
                for (Cash cash : withCd) {
                    if (cdOf(cash) >= gap) {
                        choice = cash;
                        break;
                    }
                }
                removeSame(withCd, choice);
                removeSame(remaining, choice);
                picked.add(choice);
                totalValue += choice.getValue();
                totalCd += cdOf(choice);
            }
        }

        // Then the value: youngest cash first (least CD per satoshi), larger first among
        // equals. Cash worth no more than the fee its input adds is skipped.
        remaining.sort((a, b) -> {
            int byAge = Double.compare((double) cdOf(a) / a.getValue(), (double) cdOf(b) / b.getValue());
            return byAge != 0 ? byAge : Long.compare(b.getValue(), a.getValue());
        });
        for (Cash cash : remaining) {
            if (!picked.isEmpty() && totalValue >= payValue + feeForInputs.applyAsLong(picked.size())) break;
            long addedFee = feeForInputs.applyAsLong(picked.size() + 1) - feeForInputs.applyAsLong(picked.size());
            if (cash.getValue() <= addedFee) continue;
            picked.add(cash);
            totalValue += cash.getValue();
            totalCd += cdOf(cash);
        }

        // Drop picks the rest can do without, most CD first: an early small pick is often
        // made redundant by a later, larger one.
        List<Cash> mostCdFirst = new ArrayList<>(picked);
        mostCdFirst.sort((a, b) -> Long.compare(cdOf(b), cdOf(a)));
        for (Cash cash : mostCdFirst) {
            if (picked.size() <= 1) break;
            long valueLeft = totalValue - cash.getValue();
            long cdLeft = totalCd - cdOf(cash);
            if (valueLeft >= payValue + feeForInputs.applyAsLong(picked.size() - 1) && cdLeft >= requiredCd) {
                removeSame(picked, cash);
                totalValue = valueLeft;
                totalCd = cdLeft;
            }
        }

        boolean enoughValue = !picked.isEmpty()
                && totalValue >= payValue + feeForInputs.applyAsLong(picked.size());
        return new Selection(picked, enoughValue, totalCd >= requiredCd);
    }

    private static long cdOf(Cash cash) {
        return cash.getCd() != null ? cash.getCd() : 0L;
    }

    private static void removeSame(List<Cash> list, Cash target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) {
                list.remove(i);
                return;
            }
        }
    }
}
