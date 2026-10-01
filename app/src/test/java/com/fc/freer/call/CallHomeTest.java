package com.fc.freer.call;

import static org.junit.Assert.assertEquals;

import com.fc.fc_ajdk.constants.Constants;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class CallHomeTest {

    private static final String SID = "ab".repeat(32);

    @Test
    public void settingCallKeepsTheRestAndDropsOtherSpellings() {
        Map<String, String> stored = new HashMap<>();
        stored.put(Constants.DOCK_NO1_NRC7, "(sid)dock");
        stored.put("call", "fudp://old:1");
        stored.put("CALLBACK", "keep");
        Map<String, String> home = CallHome.withCall(stored, SID);
        Map<String, String> want = new HashMap<>();
        want.put(Constants.DOCK_NO1_NRC7, "(sid)dock");
        want.put("CALLBACK", "keep");
        want.put(Constants.CALL_NO1_NRC7, "(sid)" + SID);
        assertEquals(want, home);
        assertEquals(SID, CallHome.display(home));
    }

    @Test
    public void blankRemovesCallAndAUrlIsKeptAsTyped() {
        Map<String, String> stored = new HashMap<>();
        stored.put(Constants.DOCK_NO1_NRC7, "(sid)dock");
        stored.put(Constants.CALL_NO1_NRC7, "(sid)" + SID);
        Map<String, String> onlyDock = new HashMap<>();
        onlyDock.put(Constants.DOCK_NO1_NRC7, "(sid)dock");
        assertEquals(onlyDock, CallHome.withCall(stored, "  "));
        assertEquals("fudp://relay:9000",
                CallHome.withCall(null, " fudp://relay:9000 ").get(Constants.CALL_NO1_NRC7));
        assertEquals("", CallHome.display(onlyDock));
    }
}
