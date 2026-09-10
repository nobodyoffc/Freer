package com.fc.freer.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.fc.fc_ajdk.data.fchData.Cash;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntToLongFunction;

/**
 * Automatic input selection should pay with young cash, meet a CD requirement with little
 * overshoot, and leave out inputs the TX doesn't need.
 */
public class CashSelectorTest {

    private static final long COIN = 100_000_000L;
    private static final IntToLongFunction FEE = inputs -> 1_000L * inputs + 500L;

    private static Cash cash(long value, long cd) {
        Cash cash = new Cash();
        cash.setValue(value);
        cash.setCd(cd);
        return cash;
    }

    private static boolean containsSame(List<Cash> list, Cash target) {
        for (Cash cash : list) {
            if (cash == target) return true;
        }
        return false;
    }

    @Test
    public void paysWithYoungCashRatherThanOldCashOfCloserValue() {
        Cash old = cash(2 * COIN, 2_000);
        Cash young = cash(50 * COIN, 0);

        CashSelector.Selection selection = CashSelector.select(Arrays.asList(old, young), COIN, 0, FEE);

        assertTrue(selection.hasEnoughValue());
        assertEquals(1, selection.getInputs().size());
        assertSame(young, selection.getInputs().get(0));
    }

    @Test
    public void meetsRequiredCdWithTheSmallestCashThatCovers() {
        Cash cd10 = cash(10 * COIN, 10);
        Cash cd40 = cash(10 * COIN, 40);
        Cash cd60 = cash(10 * COIN, 60);
        Cash cd1000 = cash(10 * COIN, 1_000);

        CashSelector.Selection selection =
                CashSelector.select(Arrays.asList(cd1000, cd10, cd60, cd40), COIN, 50, FEE);

        assertTrue(selection.hasEnoughValue());
        assertTrue(selection.hasEnoughCd());
        assertEquals(1, selection.getInputs().size());
        assertSame(cd60, selection.getInputs().get(0));
    }

    @Test
    public void combinesCdCashAndLetsYoungCashCoverTheValue() {
        Cash cd30 = cash(10 * COIN, 30);
        Cash cd45 = cash(10 * COIN, 45);
        Cash cd80 = cash(10 * COIN, 80);
        Cash young = cash(50 * COIN, 0);

        CashSelector.Selection selection =
                CashSelector.select(Arrays.asList(young, cd30, cd45, cd80), 25 * COIN, 100, FEE);

        assertTrue(selection.hasEnoughValue());
        assertTrue(selection.hasEnoughCd());
        List<Cash> inputs = selection.getInputs();
        assertEquals(3, inputs.size());
        assertTrue(containsSame(inputs, cd80));
        assertTrue(containsSame(inputs, cd30));
        assertTrue(containsSame(inputs, young));
        assertFalse(containsSame(inputs, cd45));
    }

    @Test
    public void dropsAnInputTheOthersNoLongerNeed() {
        Cash small = cash(COIN, 0);
        Cash large = cash(100 * COIN, 100);

        CashSelector.Selection selection = CashSelector.select(Arrays.asList(small, large), 50 * COIN, 0, FEE);

        assertTrue(selection.hasEnoughValue());
        assertEquals(1, selection.getInputs().size());
        assertSame(large, selection.getInputs().get(0));
    }

    @Test
    public void skipsCashWorthLessThanTheFeeItAdds() {
        Cash dust = cash(800, 0);
        Cash coin = cash(COIN, 1);

        CashSelector.Selection selection = CashSelector.select(Arrays.asList(dust, coin), COIN / 2, 0, FEE);

        assertTrue(selection.hasEnoughValue());
        assertEquals(1, selection.getInputs().size());
        assertSame(coin, selection.getInputs().get(0));
    }

    @Test
    public void reportsMissingValue() {
        CashSelector.Selection selection = CashSelector.select(Arrays.asList(cash(COIN, 5)), 2 * COIN, 0, FEE);

        assertFalse(selection.hasEnoughValue());
        assertTrue(selection.hasEnoughCd());
    }

    @Test
    public void reportsMissingCd() {
        CashSelector.Selection selection = CashSelector.select(
                Arrays.asList(cash(10 * COIN, 100), cash(10 * COIN, 100)), COIN, 500, FEE);

        assertTrue(selection.hasEnoughValue());
        assertFalse(selection.hasEnoughCd());
    }
}
