package com.fc.freer.im;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * FIMP4V3 §5.1 / FIMP2V3 §5.2: a symkey request names its entity, and may name
 * the version wanted.
 *
 * <p>The regression these pin: the version was parsed off the request and then
 * discarded, so the responder always answered with its current key. A member
 * holding v2..v6 and missing v1 was handed v6 — the key it already had — however
 * often it asked, and the messages sealed under v1 could never be recovered.
 */
public class SymkeyRequestTest {

    private static final String TEAM =
            "0a55726009ce3a31ede1a474442be6792de7e8cfbf0d1d45b866a0b1310202d4";

    @Test
    public void bareIdNamesNoVersion() {
        SymkeyStore.Asked asked = SymkeyStore.parseRequest(TEAM);
        assertNotNull(asked);
        assertEquals(TEAM, asked.entityId);
        assertNull("a bare id asks for whatever the responder holds now", asked.version);
    }

    @Test
    public void suffixedIdNamesItsVersion() {
        SymkeyStore.Asked asked = SymkeyStore.parseRequest(TEAM + ":1");
        assertNotNull(asked);
        assertEquals(TEAM, asked.entityId);
        assertEquals(Long.valueOf(1L), asked.version);

        assertEquals(Long.valueOf(42L), SymkeyStore.parseRequest(TEAM + ":42").version);
    }

    /**
     * Anything unreadable after the colon means "no version named" rather than a
     * bad request. This client sent the literal ":latest" for years, and
     * answering those with the current key is what the protocol did before a
     * version could be named — so it stays the compatible reading.
     */
    @Test
    public void unreadableVersionFallsBackToCurrent() {
        String[] tails = {"latest", "", "0", "-3", "1.5", "v2", "9999999999999999999999"};
        for (String tail : tails) {
            SymkeyStore.Asked asked = SymkeyStore.parseRequest(TEAM + ":" + tail);
            assertNotNull("':" + tail + "' still carries an entity", asked);
            assertEquals(TEAM, asked.entityId);
            assertNull("':" + tail + "' is not a version", asked.version);
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
        assertEquals(TEAM, SymkeyStore.requestContent(TEAM, null));
        assertEquals(TEAM + ":7", SymkeyStore.requestContent(TEAM, 7L));
        // Below the minimum is not a version, so it names none.
        assertEquals(TEAM, SymkeyStore.requestContent(TEAM, 0L));

        Long[] versions = {null, 1L, 6L};
        for (Long version : versions) {
            SymkeyStore.Asked asked =
                    SymkeyStore.parseRequest(SymkeyStore.requestContent(TEAM, version));
            assertNotNull(asked);
            assertEquals(TEAM, asked.entityId);
            assertEquals(version, asked.version);
        }
    }

    /** The form the Mac emits is the form this client reads, and vice versa. */
    @Test
    public void macAndAndroidAgreeOnTheForm() {
        // What FCDomain's SymkeyShare.request(entityId:version:) produces.
        assertEquals(TEAM + ":1", SymkeyStore.requestContent(TEAM, 1L));
        SymkeyStore.Asked asked = SymkeyStore.parseRequest(TEAM + ":1");
        assertNotNull(asked);
        assertEquals(Long.valueOf(1L), asked.version);
    }

    // ===== SYMKEY_HISTORY: FIMP4V3 §5.2, FIMP2V3 §5.3 =====

    @Test
    public void historyRequestNamesEveryVersionItWants() {
        assertEquals("de-duplicated and sorted, so one set is one request",
                TEAM + ":1,2,3",
                SymkeyStore.historyRequestContent(TEAM, Arrays.asList(3L, 1L, 2L)));

        SymkeyStore.AskedHistory asked = SymkeyStore.parseHistoryRequest(TEAM + ":1,2,3");
        assertNotNull(asked);
        assertEquals(TEAM, asked.entityId);
        assertEquals(Arrays.asList(1L, 2L, 3L), asked.versions);
    }

    @Test
    public void historyRequestDeduplicates() {
        assertEquals(TEAM + ":1,2",
                SymkeyStore.historyRequestContent(TEAM, Arrays.asList(2L, 2L, 1L)));
        assertEquals(Arrays.asList(1L, 2L),
                SymkeyStore.parseHistoryRequest(TEAM + ":2,2,1").versions);
    }

    /**
     * One unreadable entry does not sink the request: the others are still keys
     * somebody needs.
     */
    @Test
    public void historyRequestSkipsWhatItCannotRead() {
        SymkeyStore.AskedHistory asked =
                SymkeyStore.parseHistoryRequest(TEAM + ":1,nonsense,,3, 4 ,0,-2");
        assertNotNull(asked);
        assertEquals(Arrays.asList(1L, 3L, 4L), asked.versions);
    }

    @Test
    public void emptyHistoryRequestIsNull() {
        assertNull(SymkeyStore.parseHistoryRequest(TEAM + ":nonsense,0,-1"));
        assertNull("a batch has to name versions", SymkeyStore.parseHistoryRequest(TEAM));
        assertNull(SymkeyStore.parseHistoryRequest(":1,2"));
        assertNull(SymkeyStore.parseHistoryRequest(null));
        assertNull(SymkeyStore.historyRequestContent(TEAM, Collections.<Long>emptyList()));
        assertNull(SymkeyStore.historyRequestContent(TEAM, Arrays.asList(0L, -1L)));
    }

    /**
     * The cap is a security property: every version named costs the responder a
     * seal and a message it pays to send, so an unbounded list is an amplifier.
     */
    @Test
    public void historyRequestIsCapped() {
        List<Long> many = new ArrayList<>();
        for (long v = 1; v <= 500; v++) many.add(v);

        SymkeyStore.AskedHistory built =
                SymkeyStore.parseHistoryRequest(SymkeyStore.historyRequestContent(TEAM, many));
        assertNotNull(built);
        assertEquals(SymkeyStore.MAX_HISTORY_VERSIONS, built.versions.size());
        assertEquals("the oldest are the ones worth keeping", Long.valueOf(1L), built.versions.get(0));

        // And a request built elsewhere is capped on the way in too.
        StringBuilder overlong = new StringBuilder(TEAM).append(':');
        for (int i = 0; i < many.size(); i++) {
            if (i > 0) overlong.append(',');
            overlong.append(many.get(i));
        }
        SymkeyStore.AskedHistory inbound = SymkeyStore.parseHistoryRequest(overlong.toString());
        assertNotNull(inbound);
        assertEquals("a responder caps what it will answer, whoever built the request",
                SymkeyStore.MAX_HISTORY_VERSIONS, inbound.versions.size());
    }

    /** The batch form the Mac emits is the batch form this client reads. */
    @Test
    public void macAndAndroidAgreeOnTheBatchForm() {
        // What FCDomain's SymkeyShare.historyRequest(entityId:versions:) produces.
        assertEquals(TEAM + ":1,2,3",
                SymkeyStore.historyRequestContent(TEAM, Arrays.asList(1L, 2L, 3L)));
        SymkeyStore.AskedHistory asked = SymkeyStore.parseHistoryRequest(TEAM + ":1,2,3");
        assertNotNull(asked);
        assertEquals(Arrays.asList(1L, 2L, 3L), asked.versions);
    }
}
