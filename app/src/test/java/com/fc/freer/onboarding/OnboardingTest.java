package com.fc.freer.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The getting-started checklist: which steps show, what each waits on, and when the card
 * appears at all. Ported from the Mac build's OnboardingTests.
 */
public class OnboardingTest {

    private static final String GUIDE = "FGuideFid1111111111111111111111111";

    private static LiveFidRecord record(long balance, long cd, String guide, String cid,
                                        Map<String, String> home, Long bestHeight) {
        return new LiveFidRecord("FNewcomer", true, balance, cd, cid, null, guide, null, home, bestHeight);
    }

    /** A funded FID past the CDD activation height with coins old enough to carve. */
    private static LiveFidRecord funded() {
        return record(100_000_000L, 5, GUIDE, null, null, 4_100_000L);
    }

    private static OnboardingStatus status(OnboardingFacts facts, OnboardingStep step) {
        return new Onboarding(facts).statusOf(step);
    }

    /** A funded FID whose chain record names {@code master}. */
    private static LiveFidRecord withMaster(String master) {
        return new LiveFidRecord("FNewcomer", true, 100_000_000L, 5L, null, null, GUIDE, master,
                null, 4_100_000L);
    }

    // ---- a master is a backup ----

    @Test
    public void aMasterOnChainBacksThePrikeyUp() {
        Onboarding ob = new Onboarding(new OnboardingFacts(false, withMaster("FMasterFid11111111111111111111111")));

        assertEquals("the master holds the key, sealed to it, on the chain",
                OnboardingStatus.DONE, ob.statusOf(OnboardingStep.BACKUP_PRIKEY));
        assertEquals("the card names who can open that copy",
                "FMasterFid11111111111111111111111", ob.getBackupMaster());
    }

    @Test
    public void aBlankMasterFieldBacksNothingUp() {
        Onboarding ob = new Onboarding(new OnboardingFacts(false, withMaster("   ")));

        assertEquals(OnboardingStatus.OPEN, ob.statusOf(OnboardingStep.BACKUP_PRIKEY));
        assertNull(ob.getBackupMaster());
    }

    @Test
    public void aMasterCarveOnItsWayLeavesTheBackupWaitingForTheChain() {
        // Broadcast is not done — the carve can still be dropped — but the step has to say
        // that something is in flight rather than sit there as if nothing happened.
        OnboardingFacts facts = new OnboardingFacts(false, funded());
        facts.pending.put(OnboardingStep.BACKUP_PRIKEY, new OnboardingFacts.Pending("tx1", false));

        assertEquals(OnboardingStatus.pending("tx1"), status(facts, OnboardingStep.BACKUP_PRIKEY));
        assertNull(new Onboarding(facts).getBackupMaster());
    }

    @Test
    public void aMasterCarveOverADayOldStallsTheBackup() {
        OnboardingFacts facts = new OnboardingFacts(false, funded());
        facts.pending.put(OnboardingStep.BACKUP_PRIKEY, new OnboardingFacts.Pending("tx1", true));

        assertEquals(OnboardingStatus.stalled("tx1"), status(facts, OnboardingStep.BACKUP_PRIKEY));
    }

    @Test
    public void aCopyOfYourOwnStillSettlesTheBackupWhileTheMasterCarveConfirms() {
        OnboardingFacts facts = new OnboardingFacts(true, funded());
        facts.pending.put(OnboardingStep.BACKUP_PRIKEY, new OnboardingFacts.Pending("tx1", false));

        assertEquals(OnboardingStatus.DONE, status(facts, OnboardingStep.BACKUP_PRIKEY));
    }

    // ---- the CDD rule ----

    @Test
    public void cddIsRequiredFromTheActivationHeightOn() {
        assertFalse(FeipCdd.isRequired(3_999_999L));
        assertTrue(FeipCdd.isRequired(4_000_000L));
        assertTrue("an unknown height must not let a carve through for nothing", FeipCdd.isRequired(null));
    }

    // ---- steps ----

    @Test
    public void aBrandNewFidWaitsOnItsFirstFch() {
        LiveFidRecord fresh = LiveFidRecord.of("FNewcomer", null, 4_100_000L);
        Onboarding ob = new Onboarding(new OnboardingFacts(false, fresh));

        assertEquals(OnboardingStatus.OPEN, ob.statusOf(OnboardingStep.BACKUP_PRIKEY));
        assertEquals(OnboardingStatus.OPEN, ob.statusOf(OnboardingStep.FIRST_FCH));
        OnboardingStatus afterFch = OnboardingStatus.waitingFor(OnboardingStep.FIRST_FCH);
        assertEquals(afterFch, ob.statusOf(OnboardingStep.REGISTER_CID));
        assertEquals(afterFch, ob.statusOf(OnboardingStep.SET_HOME));
        assertEquals(afterFch, ob.statusOf(OnboardingStep.ADD_GUIDE));
        assertEquals(afterFch, ob.statusOf(OnboardingStep.JOIN_SQUARE));
        assertEquals(OnboardingStep.BACKUP_PRIKEY, ob.current().step);
        assertFalse(ob.canCarve());
    }

    @Test
    public void unknownChainStateIsNeitherDoneNorOpen() {
        Onboarding ob = new Onboarding(new OnboardingFacts(true, null));
        assertEquals(OnboardingStatus.UNKNOWN, ob.statusOf(OnboardingStep.FIRST_FCH));
        assertEquals(OnboardingStatus.UNKNOWN, ob.statusOf(OnboardingStep.REGISTER_CID));
        assertNull("nothing to expand until the chain answers", ob.current());
        assertFalse("a slow index must not flash the card for a finished identity", ob.hasRequiredStepOpen());
    }

    @Test
    public void youngCoinsWaitWithAForecast() {
        // 0.25 FCH with no coin days yet: four days to one CD.
        OnboardingFacts facts = new OnboardingFacts(true, record(25_000_000L, 0, GUIDE, null, null, 4_100_000L));
        assertEquals(OnboardingStatus.DONE, status(facts, OnboardingStep.FIRST_FCH));
        assertEquals(OnboardingStatus.coinDays(0, 1, 4), status(facts, OnboardingStep.REGISTER_CID));
        assertEquals("adding the guide as a contact costs nothing",
                OnboardingStatus.OPEN, status(facts, OnboardingStep.ADD_GUIDE));
        assertFalse(new Onboarding(facts).canCarve());
    }

    @Test
    public void forecastRoundsUpAndIsAtLeastADay() {
        // 3 FCH: a third of a day to one CD still reads as one day.
        OnboardingStatus wait = Onboarding.coinDayWait(record(300_000_000L, 0, GUIDE, null, null, 4_100_000L));
        assertEquals(OnboardingStatus.coinDays(0, 1, 1), wait);
    }

    @Test
    public void beforeActivationYoungCoinsCanCarve() {
        OnboardingFacts facts = new OnboardingFacts(true, record(100_000_000L, 0, GUIDE, null, null, 3_900_000L));
        assertEquals(OnboardingStatus.OPEN, status(facts, OnboardingStep.REGISTER_CID));
        assertTrue(new Onboarding(facts).canCarve());
    }

    @Test
    public void anUnknownHeightStillWaitsForCoinDays() {
        OnboardingFacts facts = new OnboardingFacts(true, record(100_000_000L, 0, GUIDE, null, null, null));
        assertEquals(OnboardingStatus.coinDays(0, 1, 1), status(facts, OnboardingStep.REGISTER_CID));
    }

    @Test
    public void aFidThatSpentEverythingStillCountsAsFunded() {
        OnboardingFacts facts = new OnboardingFacts(true, record(0, 0, GUIDE, null, null, 4_100_000L));
        assertEquals(OnboardingStatus.DONE, status(facts, OnboardingStep.FIRST_FCH));
        assertEquals(OnboardingStatus.coinDays(0, 1, null), status(facts, OnboardingStep.REGISTER_CID));
    }

    @Test
    public void cidAndHomeComeFromTheChain() {
        Map<String, String> home = new HashMap<>();
        home.put("DOCK@No1_NrC7", "(sid)abc");
        home.put("DISK@No1_NrC7", "");
        OnboardingFacts facts = new OnboardingFacts(true, record(100_000_000L, 5, GUIDE, "alice_VkUV", home, 4_100_000L));
        assertEquals(OnboardingStatus.DONE, status(facts, OnboardingStep.REGISTER_CID));
        assertEquals("a blank DISK is not a DISK", OnboardingStatus.OPEN, status(facts, OnboardingStep.SET_HOME));

        // Another client may have written a bare key; that FID is still reachable.
        home.remove("DISK@No1_NrC7");
        home.put("DISK", "https://disk.example");
        facts = new OnboardingFacts(true, record(100_000_000L, 5, GUIDE, "alice_VkUV", home, 4_100_000L));
        assertEquals(OnboardingStatus.DONE, status(facts, OnboardingStep.SET_HOME));
    }

    @Test
    public void addingTheGuideIsDoneByTheContact() {
        OnboardingFacts facts = new OnboardingFacts(true, funded());
        assertEquals(OnboardingStatus.OPEN, status(facts, OnboardingStep.ADD_GUIDE));
        facts.guideIsContact = true;
        assertEquals(OnboardingStatus.DONE, status(facts, OnboardingStep.ADD_GUIDE));
    }

    @Test
    public void noGuideOnRecordDropsTheStep() {
        Onboarding ob = new Onboarding(new OnboardingFacts(true, record(100_000_000L, 5, null, null, null, 4_100_000L)));
        assertNull(ob.statusOf(OnboardingStep.ADD_GUIDE));
        assertNull(ob.getGuide());
    }

    @Test
    public void aBroadcastCarveIsPendingNotDone() {
        OnboardingFacts facts = new OnboardingFacts(true, funded());
        facts.pending.put(OnboardingStep.REGISTER_CID, new OnboardingFacts.Pending("tx1", false));
        facts.pending.put(OnboardingStep.JOIN_SQUARE, new OnboardingFacts.Pending("tx2", false));
        Onboarding ob = new Onboarding(facts);
        assertEquals(OnboardingStatus.pending("tx1"), ob.statusOf(OnboardingStep.REGISTER_CID));
        assertEquals(OnboardingStatus.pending("tx2"), ob.statusOf(OnboardingStep.JOIN_SQUARE));
        assertEquals("nothing to do on a pending step but wait", OnboardingStep.SET_HOME, ob.current().step);
        assertFalse(ob.isComplete());
        assertTrue("the card must outlive a carve that has not landed", ob.hasRequiredStepOpen());
        assertFalse(ob.statusOf(OnboardingStep.REGISTER_CID).isActionable());

        facts.joinedSquare = true;
        assertEquals("the chain wins over the record",
                OnboardingStatus.DONE, new Onboarding(facts).statusOf(OnboardingStep.JOIN_SQUARE));
    }

    @Test
    public void anAskForFirstFchIsPendingUntilCoinsArrive() {
        LiveFidRecord fresh = LiveFidRecord.of("FNewcomer", null, 4_100_000L);
        OnboardingFacts facts = new OnboardingFacts(true, fresh);
        facts.pending.put(OnboardingStep.FIRST_FCH, new OnboardingFacts.Pending(null, false));
        assertEquals(OnboardingStatus.pending(null), status(facts, OnboardingStep.FIRST_FCH));

        facts.pending.put(OnboardingStep.FIRST_FCH, new OnboardingFacts.Pending(null, true));
        assertEquals("an ask a day old just reopens", OnboardingStatus.OPEN, status(facts, OnboardingStep.FIRST_FCH));

        facts.chain = funded();
        assertEquals(OnboardingStatus.DONE, status(facts, OnboardingStep.FIRST_FCH));
    }

    @Test
    public void aPendingCarveOverADayOldStallsAndReopens() {
        OnboardingFacts facts = new OnboardingFacts(true, funded());
        facts.pending.put(OnboardingStep.SET_HOME, new OnboardingFacts.Pending("tx3", true));
        OnboardingStatus status = new Onboarding(facts).statusOf(OnboardingStep.SET_HOME);
        assertEquals(OnboardingStatus.stalled("tx3"), status);
        assertTrue(status.isActionable());
    }

    @Test
    public void aPendingCarveIsReportedEvenWhileCoinsAge() {
        // The carve went out before the CD ran low; waiting on it matters more than the forecast.
        OnboardingFacts facts = new OnboardingFacts(true, record(100_000_000L, 0, GUIDE, null, null, 4_100_000L));
        facts.pending.put(OnboardingStep.REGISTER_CID, new OnboardingFacts.Pending("tx4", false));
        assertEquals(OnboardingStatus.pending("tx4"), status(facts, OnboardingStep.REGISTER_CID));
    }

    @Test
    public void skippedStepsSettle() {
        OnboardingFacts facts = new OnboardingFacts(true, funded());
        facts.skipped.add(OnboardingStep.ADD_GUIDE);
        facts.skipped.add(OnboardingStep.JOIN_SQUARE);
        assertEquals(OnboardingStatus.SKIPPED, status(facts, OnboardingStep.ADD_GUIDE));
        assertEquals(OnboardingStatus.SKIPPED, status(facts, OnboardingStep.JOIN_SQUARE));
    }

    // ---- showing the card ----

    @Test
    public void masterIsNotAStep() {
        // A beginner has no other FID to name; the master lives in its own screen.
        Onboarding ob = new Onboarding(new OnboardingFacts(true, funded()));
        for (Onboarding.Item item : ob.getItems()) {
            assertFalse(item.step.key.toLowerCase().contains("master"));
        }
    }

    private static LiveFidRecord setUp() {
        Map<String, String> home = new HashMap<>();
        home.put("DOCK", "sid1");
        home.put("DISK", "sid2");
        return record(100_000_000L, 5, GUIDE, "alice_VkUV", home, 4_100_000L);
    }

    @Test
    public void anIdentitySetUpBeforeTheChecklistNeverSeesIt() {
        Onboarding ob = new Onboarding(new OnboardingFacts(true, setUp()));
        assertFalse("the optional steps are still open", ob.isComplete());
        assertFalse(ob.shouldShow(false));
        assertTrue(ob.shouldShow(true));
    }

    @Test
    public void aRequiredStepAlwaysShowsIt() {
        Onboarding ob = new Onboarding(new OnboardingFacts(false, null));
        assertTrue(ob.shouldShow(false));
    }

    @Test
    public void completeHidesIt() {
        OnboardingFacts facts = new OnboardingFacts(true, setUp());
        facts.guideIsContact = true;
        facts.joinedSquare = true;
        Onboarding ob = new Onboarding(facts);
        assertTrue(ob.isComplete());
        assertFalse(ob.shouldShow(true));
    }

    // ---- persistence ----

    @Test
    public void skippedRoundTripsAndDropsUnknownNames() {
        assertEquals(EnumSet.noneOf(OnboardingStep.class), OnboardingStep.parseKeys(null));
        String joined = OnboardingStep.joinKeys(EnumSet.of(OnboardingStep.JOIN_SQUARE, OnboardingStep.ADD_GUIDE));
        assertEquals("addGuide,joinSquare", joined);
        assertEquals(EnumSet.of(OnboardingStep.ADD_GUIDE, OnboardingStep.JOIN_SQUARE), OnboardingStep.parseKeys(joined));

        Set<OnboardingStep> read = OnboardingStep.parseKeys("joinSquare,somethingNew");
        assertEquals(EnumSet.of(OnboardingStep.JOIN_SQUARE), read);
    }
}
