package com.fc.freer.im;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.db.LocalDB;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** FIMP0V3 §3.5 steps 3 and 4: addressed to us, and not taken in before. */
public class InboundGuardTest {

    private static final String ME = "FEk41Kqjar45fLDriztUDTUkdki7mmcjWK";
    private static final String BOB = "F86zoAvNaQxEuYyvQssV5WxEzapNaiDtTW";
    private static final String CAROL = "F6vqNGkbAqZQ1YkPWLcXfNfwvJXCTGmzUM";
    private static final String SQUARE = "8e7d6c5b00000000000000000000000000000000000000000000000000000001";

    /** Just the named-map calls the guard makes, backed by a HashMap. */
    @SuppressWarnings("unchecked")
    private static LocalDB<Object> memoryDb() {
        Map<String, Object> map = new HashMap<>();
        return (LocalDB<Object>) Proxy.newProxyInstance(
                LocalDB.class.getClassLoader(), new Class<?>[] {LocalDB.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "putInMap": map.put((String) args[1], args[2]); return null;
                        case "getFromMap": return map.get((String) args[1]);
                        case "getAllFromMap": return new HashMap<>(map);
                        case "removeFromMap":
                            for (Object k : (List<?>) args[1]) map.remove(k);
                            return null;
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private static ImMessage text(ImType type, String from, String to, String id) {
        ImMessage m = ImMessage.createText(type, from, to, "hi");
        m.setId(id);
        return m;
    }

    @Test
    public void p2pForSomeoneElseIsMisaddressed() {
        InboundGuard guard = new InboundGuard(ME, memoryDb());
        assertNull(guard.misaddressed(text(ImType.P2P, BOB, ME, "0000000000000001"), null));
        assertNotNull(guard.misaddressed(text(ImType.P2P, BOB, CAROL, "0000000000000001"), null));
    }

    /** A message for one recipient, stored by the DOCK for another. */
    @Test
    public void targetMustBeAmongTheItemsRecipients() {
        InboundGuard guard = new InboundGuard(ME, memoryDb());
        ImMessage post = text(ImType.SQUARE, BOB, SQUARE, "0000000000000002");
        assertNull(guard.misaddressed(post, Collections.singletonList(SQUARE)));
        assertNotNull(guard.misaddressed(post, Arrays.asList("some-other-square")));
    }

    @Test
    public void aReplayIsAdmittedOnce() {
        InboundGuard guard = new InboundGuard(ME, memoryDb());
        ImMessage m = text(ImType.P2P, BOB, ME, "0000000000000003");
        assertTrue(guard.admit(m, null));
        assertFalse(guard.admit(text(ImType.P2P, BOB, ME, "0000000000000003"), null));
    }

    /** Ids are unique per sender, so the same id from another sender is new. */
    @Test
    public void theSameIdFromAnotherSenderIsNotAReplay() {
        InboundGuard guard = new InboundGuard(ME, memoryDb());
        assertTrue(guard.admit(text(ImType.SQUARE, BOB, SQUARE, "0000000000000004"), null));
        assertTrue(guard.admit(text(ImType.SQUARE, CAROL, SQUARE, "0000000000000004"), null));
    }

    @Test
    public void oldRecordsArePruned() {
        LocalDB<Object> db = memoryDb();
        InboundGuard guard = new InboundGuard(ME, db);
        db.putInMap("fimp_seen_messages", BOB + "|old", System.currentTimeMillis() - InboundGuard.RETENTION_MS - 1);
        guard.markSeen(text(ImType.P2P, BOB, ME, "0000000000000005"));
        assertEquals(1, guard.prune());
        assertFalse(guard.isReplay(text(ImType.P2P, BOB, ME, "old")));
        assertTrue(guard.isReplay(text(ImType.P2P, BOB, ME, "0000000000000005")));
    }
}
