package com.fc.freer.im;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * FIMP4V3 §5.1/§5.2 and FIMP2V3 §5.2/§5.3, merged: <b>one</b> request shape naming zero, one or
 * many versions, read by one parser.
 *
 * <p>Two regressions are pinned here. The first: the version was parsed off the request and then
 * discarded, so the responder always answered with its current key — a member holding v2..v6 and
 * missing v1 was handed v6, the key it already had, however often it asked. The second: the batch
 * form had a parser of its own, so the two could drift apart while both kept passing.
 */
public class SymkeyRequestTest {

    private static final String TEAM =
            "0a55726009ce3a31ede1a474442be6792de7e8cfbf0d1d45b866a0b1310202d4";

    @Test
    public void bareIdNamesNoVersion() {
        SymkeyStore.Asked asked = SymkeyStore.parseRequest(TEAM);
        assertNotNull(asked);
        assertEquals(TEAM, asked.entityId);
        assertTrue("a bare id asks for whatever the responder holds now", asked.wantsCurrent());
        assertTrue(asked.versions.isEmpty());
    }

    @Test
    public void suffixedIdNamesItsVersion() {
        SymkeyStore.Asked asked = SymkeyStore.parseRequest(TEAM + ":1");
        assertNotNull(asked);
        assertEquals(TEAM, asked.entityId);
        assertEquals(Collections.singletonList(1L), asked.versions);
        assertFalse(asked.wantsCurrent());

        assertEquals(Collections.singletonList(42L),
                SymkeyStore.parseRequest(TEAM + ":42").versions);
    }

    /** A timestamp version is nothing special to the parser -- it is just a larger number. */
    @Test
    public void aTimestampVersionParsesLikeAnyOther() {
        long minted = 1_789_813_689L;
        SymkeyStore.Asked asked = SymkeyStore.parseRequest(TEAM + ":" + minted);
        assertNotNull(asked);
        assertEquals(Collections.singletonList(minted), asked.versions);
        assertEquals(TEAM + ":" + minted, SymkeyStore.requestContent(TEAM, minted));
    }

    /**
     * Anything unreadable after the colon means "no version named" rather than a bad request.
     * This client sent the literal ":latest" for years, and answering those with the current key
     * is what the protocol did before a version could be named — so it stays the compatible
     * reading.
     */
    @Test
    public void unreadableVersionFallsBackToCurrent() {
        String[] tails = {"latest", "", "0", "-3", "1.5", "v2", "9999999999999999999999"};
        for (String tail : tails) {
            SymkeyStore.Asked asked = SymkeyStore.parseRequest(TEAM + ":" + tail);
            assertNotNull("':" + tail + "' still carries an entity", asked);
            assertEquals(TEAM, asked.entityId);
            assertTrue("':" + tail + "' is not a version", asked.wantsCurrent());
        }
    }

    @Test
    public void noEntityIsNoRequest() {
        assertNull(SymkeyStore.parseRequest(":1"));
        assertNull(SymkeyStore.parseRequest(null));
        assertNull(SymkeyStore.parseRequest(""));
    }

    @Test
    public void requestContentRoundTrips() {
        assertEquals(TEAM, SymkeyStore.requestContent(TEAM, (Long) null));
        assertEquals(TEAM + ":7", SymkeyStore.requestContent(TEAM, 7L));
        // Below the minimum is not a version, so it names none.
        assertEquals(TEAM, SymkeyStore.requestContent(TEAM, 0L));

        Long[] versions = {null, 1L, 6L, 1_789_813_689L};
        for (Long version : versions) {
            SymkeyStore.Asked asked =
                    SymkeyStore.parseRequest(SymkeyStore.requestContent(TEAM, version));
            assertNotNull(asked);
            assertEquals(TEAM, asked.entityId);
            assertEquals(version == null ? Collections.<Long>emptyList()
                            : Collections.singletonList(version),
                    asked.versions);
        }
    }

    // ===== the batch form is the same form, read by the same parser =====

    @Test
    public void batchRequestNamesEveryVersionItWants() {
        assertEquals("de-duplicated and sorted, so one set is one request",
                TEAM + ":1,2,3",
                SymkeyStore.requestContent(TEAM, Arrays.asList(3L, 1L, 2L)));

        SymkeyStore.Asked asked = SymkeyStore.parseRequest(TEAM + ":1,2,3");
        assertNotNull(asked);
        assertEquals(TEAM, asked.entityId);
        assertEquals(Arrays.asList(1L, 2L, 3L), asked.versions);
        assertFalse(asked.wantsCurrent());
    }

    @Test
    public void batchRequestDeduplicates() {
        assertEquals(TEAM + ":1,2", SymkeyStore.requestContent(TEAM, Arrays.asList(2L, 2L, 1L)));
        assertEquals(Arrays.asList(1L, 2L), SymkeyStore.parseRequest(TEAM + ":2,2,1").versions);
    }

    /** One unreadable entry does not sink the request: the others are still keys somebody needs. */
    @Test
    public void batchRequestSkipsWhatItCannotRead() {
        SymkeyStore.Asked asked = SymkeyStore.parseRequest(TEAM + ":1,nonsense,,3, 4 ,0,-2");
        assertNotNull(asked);
        assertEquals(Arrays.asList(1L, 3L, 4L), asked.versions);
    }

    /**
     * A batch naming nothing readable is not a refusal -- it is a request naming no version,
     * which the responder answers with its current key. That is the one behaviour the merge
     * changes, and it is the compatible reading rather than the stricter one.
     */
    @Test
    public void unreadableBatchAsksForTheCurrentKey() {
        SymkeyStore.Asked asked = SymkeyStore.parseRequest(TEAM + ":nonsense,0,-1");
        assertNotNull(asked);
        assertEquals(TEAM, asked.entityId);
        assertTrue(asked.wantsCurrent());

        assertEquals(TEAM, SymkeyStore.requestContent(TEAM, Collections.<Long>emptyList()));
        assertEquals(TEAM, SymkeyStore.requestContent(TEAM, Arrays.asList(0L, -1L)));
        assertNull(SymkeyStore.requestContent(null, Arrays.asList(1L)));
    }

    /**
     * {@code SYMKEY_HISTORY} is an accepted alias, so what it used to carry still parses. Its
     * ordinal stays reserved; nothing sends it any more.
     */
    @Test
    public void theDeprecatedHistoryFormStillParses() {
        SymkeyStore.Asked asked = SymkeyStore.parseRequest(TEAM + ":1,2,3");
        assertNotNull(asked);
        assertEquals(Arrays.asList(1L, 2L, 3L), asked.versions);
    }

    /**
     * The cap is a security property: every version <em>answered</em> costs the responder a seal
     * and a message it pays to send. Counting on the answer rather than the request is what stops
     * a list padded with versions the responder lacks buying a larger reply.
     */
    @Test
    public void aRequestNamesAtMostTheCap() {
        List<Long> many = new ArrayList<>();
        for (long v = 1; v <= 500; v++) many.add(v);

        SymkeyStore.Asked built =
                SymkeyStore.parseRequest(SymkeyStore.requestContent(TEAM, many));
        assertNotNull(built);
        assertEquals(SymkeyStore.MAX_VERSIONS_PER_REQUEST, built.versions.size());
        assertEquals("the oldest are the ones worth keeping", Long.valueOf(1L), built.versions.get(0));
    }

    /** The form the Mac emits is the form this client reads, and vice versa. */
    @Test
    public void macAndAndroidAgreeOnTheForm() {
        assertEquals(TEAM + ":1", SymkeyStore.requestContent(TEAM, 1L));
        assertEquals(TEAM + ":1,2,3", SymkeyStore.requestContent(TEAM, Arrays.asList(1L, 2L, 3L)));
        assertEquals(Collections.singletonList(1L), SymkeyStore.parseRequest(TEAM + ":1").versions);
        assertEquals(Arrays.asList(1L, 2L, 3L), SymkeyStore.parseRequest(TEAM + ":1,2,3").versions);
    }
}
