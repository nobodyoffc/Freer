package com.fc.freer.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.Hex;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Private BASE and DISK entries and the home BASE check. Ported from the Mac build's
 * HomePrivacyTests and HomeBaseTests, plus a value the Mac app sealed: each app must open what
 * the other wrote.
 */
public class HomePrivacyTest {

    private static final String SID = repeat('a', 64);
    private static final String OTHER_SID = repeat('b', 64);
    private static final byte[] PRIKEY = filled((byte) 7);
    private static final String PUBKEY = Hex.toHex(KeyTools.prikeyToPubkey(PRIKEY));

    /** {@code HomePrivacy.seal(SID, pubkey of 0x07…07)} as the Mac app wrote it. */
    private static final String SEALED_BY_MAC = "{\"type\":\"AsyOneWay\",\"alg\":\"EccK1AesGcm256@No1_NrC7\","
            + "\"cipher\":\"hwxUwWkuxi4XpaxjtEonNufwWiENv9Y4bW/+Qauqym8bJ/5ixBGNdFgczrZAQqEJ\","
            + "\"pubkeyA\":\"036da4764dd060ca36065032b47b1cefe3f92321191011afbbcb5f46ab5a7d659e\","
            + "\"iv\":\"3b59f9f25cc5ec2943b57f5f\"}";

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static byte[] filled(byte b) {
        byte[] key = new byte[32];
        Arrays.fill(key, b);
        return key;
    }

    // ---- sealing ----

    @Test
    public void theMacFixtureIsForThisKey() {
        assertEquals("02989c0b76cb563971fdc9bef31ec06c3560f3249d6ee9e5d83c57625596e05f6f", PUBKEY);
    }

    @Test
    public void aValueTheMacSealedOpensHere() {
        assertTrue(HomePrivacy.isSealed(SEALED_BY_MAC));
        assertEquals("(sid)" + SID, HomePrivacy.open(SEALED_BY_MAC, PRIKEY));
        Map<String, String> home = new HashMap<>();
        home.put(DiskHomeManager.DISK_KEY, SEALED_BY_MAC);
        assertEquals(SID, DiskHomeManager.resolveSid(home, PRIKEY));
    }

    @Test
    public void sealedOpensForItsOwnerOnly() {
        String sealed = HomePrivacy.seal("(sid)" + SID, PUBKEY);
        assertTrue(HomePrivacy.isSealed(sealed));
        assertEquals("(sid)" + SID, HomePrivacy.open(sealed, PRIKEY));
        assertNull(HomePrivacy.open(sealed, filled((byte) 8)));
        assertNull(HomePrivacy.open(sealed, null));
        assertNotEquals("fresh randomness every time", sealed, HomePrivacy.seal(SID, PUBKEY));
    }

    @Test
    public void publicValuesOpenToThemselves() {
        assertEquals("(sid)" + SID, HomePrivacy.open("(sid)" + SID, null));
        assertEquals("fudp://d.example:8500", HomePrivacy.open(" fudp://d.example:8500 ", null));
        assertNull(HomePrivacy.open("  ", PRIKEY));
        Map<String, String> home = new HashMap<>();
        home.put(DiskHomeManager.DISK_KEY, SID);
        assertEquals("a public DISK is read as before", SID, DiskHomeManager.resolveSid(home, null));
    }

    @Test
    public void anAddressCannotBeSealed() {
        try {
            HomePrivacy.seal("fudp://d.example:8500", PUBKEY);
            fail("sealed an address");
        } catch (IllegalArgumentException expected) {
            // Android's reader expects the 32 bytes of a service id
        }
    }

    // ---- what a carve writes ----

    @Test
    public void resealingTheSameServiceIsNoChange() {
        String stored = HomePrivacy.seal(SID, PUBKEY);
        assertNull("a paid carve that says nothing new", HomePrivacy.pending(SID, true, stored, PRIKEY, PUBKEY));
        assertNull(HomePrivacy.pending("(sid)" + SID, false, SID, PRIKEY, PUBKEY));
    }

    @Test
    public void flippingPrivacyIsAChange() {
        String sealed = HomePrivacy.seal(SID, PUBKEY);
        assertEquals("(sid)" + SID, HomePrivacy.pending(SID, false, sealed, PRIKEY, PUBKEY));
        String madePrivate = HomePrivacy.pending(SID, true, "(sid)" + SID, PRIKEY, PUBKEY);
        assertTrue(HomePrivacy.isSealed(madePrivate));
        assertEquals("(sid)" + SID, HomePrivacy.open(madePrivate, PRIKEY));
    }

    @Test
    public void anotherServiceIsAChange() {
        String stored = HomePrivacy.seal(SID, PUBKEY);
        String written = HomePrivacy.pending(OTHER_SID, true, stored, PRIKEY, PUBKEY);
        assertEquals("(sid)" + OTHER_SID, HomePrivacy.open(written, PRIKEY));
        assertNull("blank says nothing", HomePrivacy.pending("  ", true, stored, PRIKEY, PUBKEY));
    }

    @Test
    public void valueOfReadsAnySpellingOfTheKind() {
        Map<String, String> home = new HashMap<>();
        home.put("base", " (sid)" + SID + " ");
        home.put("BASEMENT", "x");
        assertEquals("(sid)" + SID, HomePrivacy.valueOf(home, "BASE"));
        home.put("BASE@No1_NrC7", OTHER_SID);
        assertEquals("this app's key wins", OTHER_SID, HomePrivacy.valueOf(home, "BASE"));
        assertNull(HomePrivacy.valueOf(new HashMap<>(), "BASE"));
    }

    // ---- the home BASE ----

    @Test
    public void entrySaysWhatKindOfBaseTheHomeNames() {
        Map<String, String> home = new HashMap<>();
        assertEquals(HomeBaseManager.Kind.NONE, HomeBaseManager.entry(home, PRIKEY).kind);
        home.put("BASE@No1_NrC7", "(sid)" + SID);
        assertEquals(HomeBaseManager.Kind.SERVICE_ID, HomeBaseManager.entry(home, null).kind);
        assertEquals(SID, HomeBaseManager.entry(home, null).value);
        home.put("BASE@No1_NrC7", SEALED_BY_MAC);
        assertEquals(SID, HomeBaseManager.entry(home, PRIKEY).value);
        assertEquals(HomeBaseManager.Kind.UNREADABLE, HomeBaseManager.entry(home, filled((byte) 8)).kind);
        home.put("BASE@No1_NrC7", "fudp://b.example:8500");
        assertEquals(HomeBaseManager.Kind.ADDRESS, HomeBaseManager.entry(home, null).kind);
    }

    @Test
    public void theHelloKeyMustBeTheRecordsDealerPubkey() {
        byte[] key = KeyTools.prikeyToPubkey(PRIKEY);
        Service record = new Service();
        record.setDealerPubkey(PUBKEY.toUpperCase());
        assertEquals(HomeBaseManager.Verdict.MATCHES, HomeBaseManager.verify(key, record));
        record.setDealerPubkey(Hex.toHex(KeyTools.prikeyToPubkey(filled((byte) 8))));
        assertEquals(HomeBaseManager.Verdict.MISMATCH, HomeBaseManager.verify(key, record));
    }

    @Test
    public void theDealerFidIsCheckedWhenTheRecordHasNoPubkey() {
        byte[] key = KeyTools.prikeyToPubkey(PRIKEY);
        Service record = new Service();
        record.setDealer(KeyTools.pubkeyToFchAddr(key));
        assertEquals(HomeBaseManager.Verdict.MATCHES, HomeBaseManager.verify(key, record));
        record.setDealer(KeyTools.pubkeyToFchAddr(KeyTools.prikeyToPubkey(filled((byte) 8))));
        assertEquals(HomeBaseManager.Verdict.MISMATCH, HomeBaseManager.verify(key, record));
        assertEquals(HomeBaseManager.Verdict.UNVERIFIABLE, HomeBaseManager.verify(key, new Service()));
        assertEquals(HomeBaseManager.Verdict.UNVERIFIABLE, HomeBaseManager.verify(key, null));
    }
}
