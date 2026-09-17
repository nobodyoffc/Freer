package com.fc.freer.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FEIP3 CID and FEIP9 Home: the parser's suffix rule run client-side, the home merge that
 * keeps a register from erasing entries it did not mean to touch, and the record that stops
 * a carve being offered twice. Ported from the Mac build's IdentityFeipTests.
 */
public class IdentityFeipTest {

    /** FEIP3's own example FID. The suffix is the FID's characters as they are: vkUV. */
    private static final String FID = "FPL44YJRwPdd2ipziFvqq6y2tw4VnVvkUV";
    private static final String DOCK = "DOCK@No1_NrC7";
    private static final String DISK = "DISK@No1_NrC7";

    // ---- CID ----

    @Test
    public void badNamesAreRefused() {
        for (String bad : new String[]{"", "Al ice", "a@b", "a#b", "a/b", "tab\tname", "nbsp name", null}) {
            assertFalse(String.valueOf(bad), CidFeip.isGoodName(bad));
            try {
                CidFeip.preview(bad, FID, null, cid -> null);
                fail("preview accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // refused before anything is asked
            } catch (Exception e) {
                fail("unexpected " + e);
            }
        }
        assertTrue(CidFeip.isGoodName("Alice_1"));
    }

    @Test
    public void previewTakesTheLastFourCharacters() throws Exception {
        CidFeip.Preview preview = CidFeip.preview("Alice", FID, Collections.emptyList(), cid -> null);
        assertEquals(new CidFeip.Preview(CidFeip.Preview.Kind.NEW, "Alice_vkUV"), preview);
    }

    @Test
    public void previewExtendsTheSuffixPastOtherFidsCollisions() throws Exception {
        Map<String, String> taken = new HashMap<>();
        taken.put("Alice_vkUV", "FSomebodyElse");
        taken.put("Alice_VvkUV", "FAnotherOne");
        CidFeip.Preview preview = CidFeip.preview("Alice", FID, Collections.emptyList(), taken::get);
        assertEquals(new CidFeip.Preview(CidFeip.Preview.Kind.NEW, "Alice_nVvkUV"), preview);
    }

    @Test
    public void aCidThisFidAlreadyOwnsIsNotACollision() throws Exception {
        // The index can report our own FID as the owner; that must not lengthen the suffix.
        CidFeip.Preview preview = CidFeip.preview("Alice", FID, Collections.emptyList(), cid -> FID);
        assertEquals(new CidFeip.Preview(CidFeip.Preview.Kind.NEW, "Alice_vkUV"), preview);
    }

    @Test
    public void ownEarlierCidReactivatesEvenAtTheLimit() throws Exception {
        List<String> used = Arrays.asList("Alice_vkUV", "Bob_vkUV", "Carol_vkUV", "Dave_vkUV");
        CidFeip.Preview again = CidFeip.preview("Bob", FID, used, cid -> null);
        assertEquals(new CidFeip.Preview(CidFeip.Preview.Kind.REACTIVATE, "Bob_vkUV"), again);
        assertTrue(again.isCarvable());

        CidFeip.Preview fifth = CidFeip.preview("Eve", FID, used, cid -> null);
        assertEquals(new CidFeip.Preview(CidFeip.Preview.Kind.LIMIT_REACHED, "Eve_vkUV"), fifth);
        assertFalse(fifth.isCarvable());
    }

    @Test
    public void ownEarlierCidWithALongerSuffixReactivatesPastACollision() throws Exception {
        List<String> used = Collections.singletonList("Alice_VvkUV");
        CidFeip.Preview preview = CidFeip.preview("Alice", FID, used,
                cid -> cid.equals("Alice_vkUV") ? "FSomebodyElse" : FID);
        assertEquals(new CidFeip.Preview(CidFeip.Preview.Kind.REACTIVATE, "Alice_VvkUV"), preview);
    }

    @Test
    public void everySuffixTakenIsUnavailable() throws Exception {
        CidFeip.Preview preview = CidFeip.preview("Alice", FID, Collections.emptyList(), cid -> "FSomebodyElse");
        assertEquals(CidFeip.Preview.Kind.UNAVAILABLE, preview.kind);
        assertNull(preview.cid);
    }

    // ---- Home ----

    @Test
    public void mergeKeepsEntriesItDidNotMeanToTouch() {
        Map<String, String> stored = new HashMap<>();
        stored.put("blog", "https://me.example");
        stored.put(DOCK, "(sid)old");
        Map<String, String> changes = new HashMap<>();
        changes.put(DOCK, "newsid");
        changes.put(DISK, null);

        Map<String, String> expected = new HashMap<>();
        expected.put("blog", "https://me.example");
        expected.put(DOCK, "newsid");
        assertEquals("register replaces the whole map, so everything else has to be carried",
                expected, HomeFeip.merged(stored, changes));
    }

    @Test
    public void mergeThatChangesNothingIsNull() {
        Map<String, String> stored = Collections.singletonMap(DISK, "sid");
        Map<String, String> changes = new HashMap<>();
        changes.put(DOCK, "");
        changes.put(DISK, "sid");
        assertNull(HomeFeip.merged(stored, changes));
        assertNull("nothing at all is not a carve", HomeFeip.merged(null, Collections.emptyMap()));
        assertNotNull(HomeFeip.merged(null, Collections.singletonMap(DOCK, "sid")));
    }

    @Test
    public void declaresMatchesByPrefixAndIgnoresBlankValues() {
        assertTrue(HomeFeip.declares("DOCK", Collections.singletonMap(DOCK, "x")));
        assertTrue(HomeFeip.declares("DOCK", Collections.singletonMap("dock", "x")));
        assertFalse(HomeFeip.declares("DOCK", Collections.singletonMap(DOCK, "  ")));
        assertFalse(HomeFeip.declares("DISK", Collections.singletonMap(DOCK, "x")));
        assertFalse(HomeFeip.declares("DISK", null));
    }

    // ---- pending carves ----

    private static LiveFidRecord info(String cid, String master, Map<String, String> home) {
        return new LiveFidRecord(FID, true, 0L, 0L, cid, null, null, master, home, null);
    }

    @Test
    public void cidLandsOnlyAsTheCarvedNameWithAFidSuffix() {
        PendingIdentityCarve pending = PendingIdentityCarve.cid(FID, "Al", "t", 0);
        assertTrue(pending.isLanded(info("Al_vkUV", null, null)));
        assertTrue("a collision lengthens the suffix", pending.isLanded(info("Al_VvkUV", null, null)));
        assertFalse("another name that starts the same", pending.isLanded(info("Al_x_vkUV", null, null)));
        assertFalse("shorter than any suffix the parser makes", pending.isLanded(info("Al_kUV", null, null)));
        assertFalse(pending.isLanded(info(null, null, null)));
    }

    @Test
    public void homeLandsWhenEveryCarvedEntryIsOnTheChain() {
        Map<String, String> carved = new HashMap<>();
        carved.put(DOCK, "(sid)a");
        carved.put(DISK, "(sid)b");
        PendingIdentityCarve pending = PendingIdentityCarve.home(FID, carved, "t", 0);
        assertFalse(pending.isLanded(info(null, null, Collections.singletonMap(DOCK, "(sid)a"))));
        Map<String, String> onChain = new HashMap<>(carved);
        onChain.put("blog", "x");
        assertTrue(pending.isLanded(info(null, null, onChain)));
    }

    @Test
    public void masterLandsOnAnyMasterBecauseItIsWriteOnce() {
        PendingIdentityCarve pending = PendingIdentityCarve.master(FID, "FMine", "t", 0);
        assertFalse(pending.isLanded(info(null, null, null)));
        assertTrue("nothing more can come of this carve once the chain has a master",
                pending.isLanded(info(null, "FSomebodyElse", null)));
    }

    @Test
    public void overdueAfterADay() {
        PendingIdentityCarve pending = PendingIdentityCarve.cid(FID, "Al", "t", 0);
        assertFalse(pending.isOverdue(PendingIdentityCarve.OVERDUE_MS - 1));
        assertTrue(pending.isOverdue(PendingIdentityCarve.OVERDUE_MS));
    }

    @Test
    public void refreshClearsWhatTheChainShowsAndNewerReplacesOlder() {
        Map<String, String> kv = new HashMap<>();
        PendingIdentityCarves carves = new PendingIdentityCarves(new PendingIdentityCarves.Store() {
            @Override public String get(String key) { return kv.get(key); }
            @Override public void put(String key, String value) { kv.put(key, value); }
            @Override public void remove(String key) { kv.remove(key); }
        });

        carves.record(PendingIdentityCarve.cid(FID, "Old", "t0", 0));
        carves.record(PendingIdentityCarve.cid(FID, "Al", "t1", 0));
        carves.record(PendingIdentityCarve.home(FID, Collections.singletonMap(DOCK, "(sid)a"), "t2", 0));
        assertEquals("one record per fid and kind", "t1", carves.get(FID, PendingIdentityCarve.Kind.CID).txid);

        int cleared = carves.reconcile(info("Al_" + FID.substring(FID.length() - 4), null, null));

        assertEquals(1, cleared);
        assertNull("landed, so cleared", carves.get(FID, PendingIdentityCarve.Kind.CID));
        assertNotNull("not on the chain yet", carves.get(FID, PendingIdentityCarve.Kind.HOME));
        assertNull("overdue is not in flight", carves.getInFlight(FID, PendingIdentityCarve.Kind.HOME,
                PendingIdentityCarve.OVERDUE_MS));
    }
}
