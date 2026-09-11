package com.fc.freer.nobody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.utils.BytesUtils;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.freer.im.NobodyBoard;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The registry is what every nobody mark and confirmation reads, so these pin its
 * rules: a published key is remembered for good, a "not a nobody" answer only for
 * a while, and a failed check never pretends to be an answer.
 */
public class NobodyRegistryTest {

    private static final String ALICE = "FEk41Kqjar45fLDriztUDTUkdki7mmcjWK";
    private static final String BOB = "FTqiqAyXHnK7uDTXzMap3acvqADK4ZGzts";

    private long now;
    private NobodyRegistry.MemoryStore store;
    private NobodyRegistry registry;

    @Before
    public void setUp() {
        now = 1_000_000L;
        store = new NobodyRegistry.MemoryStore();
        registry = new NobodyRegistry(store, () -> now, Runnable::run);
    }

    @Test
    public void defaultBoardIsAlwaysANobody() {
        assertTrue(registry.isNobody(NobodyBoard.DEFAULT_NOBODY_FID));
    }

    @Test
    public void positivesArePersistedAndNeverExpire() {
        registry.mark(ALICE);
        now += 365L * 24 * 60 * 60 * 1000;
        assertTrue(registry.isNobody(ALICE));

        NobodyRegistry reloaded = new NobodyRegistry(store, () -> now, Runnable::run);
        assertTrue(reloaded.isNobody(ALICE));
    }

    @Test
    public void negativesExpire() {
        registry.markNotNobodies(Collections.singletonList(BOB));
        assertTrue(registry.isKnownNotNobody(BOB));
        assertTrue(registry.unknownOf(Collections.singletonList(BOB)).isEmpty());

        now += NobodyRegistry.NEGATIVE_TTL_MS + 1;
        assertFalse(registry.isKnownNotNobody(BOB));
        assertEquals(Collections.singletonList(BOB), registry.unknownOf(Collections.singletonList(BOB)));
    }

    @Test
    public void aPublishedKeyOverridesAnEarlierNegative() {
        registry.markNotNobodies(Collections.singletonList(ALICE));
        registry.mark(ALICE);
        assertTrue(registry.isNobody(ALICE));
        assertFalse(registry.isKnownNotNobody(ALICE));

        registry.markNotNobodies(Collections.singletonList(ALICE));
        assertTrue(registry.isNobody(ALICE));
    }

    @Test
    public void nobodiesOfKeepsOrderAndDropsDuplicates() {
        registry.mark(BOB);
        registry.mark(ALICE);
        List<String> found = registry.nobodiesOf(Arrays.asList(BOB, "x", ALICE, BOB, null));
        assertEquals(Arrays.asList(BOB, ALICE), found);
    }

    @Test
    public void resolveMarksFoundAndAbsentFids() {
        boolean ok = registry.resolve(Arrays.asList(ALICE, BOB), fids -> {
            Map<String, Object> found = new HashMap<>();
            found.put(ALICE, new Object());
            return found;
        }, false);
        assertTrue(ok);
        assertTrue(registry.isNobody(ALICE));
        assertTrue(registry.isKnownNotNobody(BOB));
    }

    @Test
    public void aFailedCheckIsNotAnAnswer() {
        boolean ok = registry.resolve(Collections.singletonList(ALICE), fids -> null, false);
        assertFalse(ok);
        assertFalse(registry.isNobody(ALICE));
        assertFalse(registry.isKnownNotNobody(ALICE));
    }

    @Test
    public void aFailedCheckBacksOffForListsButNotForConfirmations() {
        registry.resolve(Collections.singletonList(ALICE), fids -> null, false);

        List<List<String>> calls = new ArrayList<>();
        NobodyRegistry.Resolver counting = fids -> {
            calls.add(new ArrayList<>(fids));
            return new HashMap<>();
        };
        assertTrue(registry.resolve(Collections.singletonList(ALICE), counting, false));
        assertTrue(calls.isEmpty());

        assertTrue(registry.resolve(Collections.singletonList(ALICE), counting, true));
        assertEquals(1, calls.size());
        assertTrue(registry.isKnownNotNobody(ALICE));
    }

    @Test
    public void knownFidsAreNotCheckedAgain() {
        registry.mark(ALICE);
        registry.markNotNobodies(Collections.singletonList(BOB));
        List<String> asked = new ArrayList<>();
        registry.resolve(Arrays.asList(ALICE, BOB), fids -> {
            asked.addAll(fids);
            return new HashMap<>();
        }, true);
        assertTrue(asked.isEmpty());
    }

    @Test
    public void listenersHearOnlyNewlyLearnedNobodies() {
        List<Set<String>> heard = new ArrayList<>();
        registry.addListener(heard::add);
        registry.markNobodies(Arrays.asList(ALICE, BOB));
        registry.markNobodies(Arrays.asList(ALICE));
        assertEquals(1, heard.size());
        assertEquals(2, heard.get(0).size());
    }

    @Test
    public void pubkeyMapsToItsFid() {
        byte[] prikey = BytesUtils.hexToByteArray(NobodyBoard.DEFAULT_NOBODY_PRIKEY);
        String pubkey = Hex.toHex(KeyTools.prikeyToPubkey(prikey));
        assertEquals(NobodyBoard.DEFAULT_NOBODY_FID, NobodyRegistry.fidOfPubkey(pubkey));
        assertTrue(registry.isNobodyPubkey(pubkey));
        assertNull(NobodyRegistry.fidOfPubkey("not a pubkey"));
    }

    @Test
    public void ownKeyAlertIsClaimedOnce() {
        assertFalse(registry.claimOwnKeyAlert(ALICE));
        registry.mark(ALICE);
        assertTrue(registry.claimOwnKeyAlert(ALICE));
        assertFalse(registry.claimOwnKeyAlert(ALICE));
        assertFalse(new NobodyRegistry(store, () -> now, Runnable::run).claimOwnKeyAlert(ALICE));
    }
}
