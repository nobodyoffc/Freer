package com.fc.freer.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.fc.fc_ajdk.data.fchData.Cash;

import org.junit.Test;

import java.util.Arrays;

/**
 * CD is counted locally with the parser's formula, because the server's cd is only as fresh as
 * the last time it indexed a cash.
 */
public class CashCdTest {

    private static final long COIN = 100_000_000L;
    private static final long DAY = 1440L;

    private static Cash cash(long value, Long birthHeight, Long cd) {
        Cash cash = new Cash();
        cash.setValue(value);
        cash.setBirthHeight(birthHeight);
        cash.setCd(cd);
        return cash;
    }

    @Test
    public void countsWholeDaysTimesCoins() {
        Cash cash = cash(3 * COIN, 1_000L, 0L);
        assertEquals(Long.valueOf(15), cash.makeCd(1_000 + 5 * DAY + 100));
    }

    @Test
    public void ageUnderADayIsZero() {
        Cash cash = cash(1_000 * COIN, 1_000L, 7L);
        assertEquals(Long.valueOf(0), cash.makeCd(1_000 + DAY - 1));
    }

    @Test
    public void fractionalCoinsRoundDown() {
        Cash cash = cash(3 * COIN / 2, 1_000L, 0L);
        assertEquals(Long.valueOf(4), cash.makeCd(1_000 + 3 * DAY));
    }

    @Test
    public void cashNotInABlockKeepsItsCd() {
        Cash unconfirmed = cash(10 * COIN, null, null);
        assertNull(unconfirmed.makeCd(900_000));
        assertNull(unconfirmed.getCd());

        Cash zeroHeight = cash(10 * COIN, 0L, 5L);
        assertNull(zeroHeight.makeCd(900_000));
        assertEquals(Long.valueOf(5), zeroHeight.getCd());
    }

    @Test
    public void refreshCdUpdatesTheListAtTheGivenHeight() {
        Cash aged = cash(10 * COIN, 1_000L, 0L);
        Cash unconfirmed = cash(COIN, null, 3L);

        Cash.refreshCd(Arrays.asList(aged, unconfirmed), 1_000 + 2 * DAY);

        assertEquals(Long.valueOf(20), aged.getCd());
        assertEquals(Long.valueOf(3), unconfirmed.getCd());
    }

    @Test
    public void refreshCdWithoutAHeightChangesNothing() {
        Cash aged = cash(10 * COIN, 1_000L, 9L);
        Cash.refreshCd(Arrays.asList(aged), null);
        assertEquals(Long.valueOf(9), aged.getCd());
    }
}
