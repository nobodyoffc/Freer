package com.fc.freer.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

/** The send to array formats ImportParser.splitSendTo accepts. */
public class SendToArrayParseTest {
    private static final String A = "FEk41Kqjar45fLDriztUDTUkdki7mmcjWK";
    private static final String B = "F86zoAvNaQxEuYyvQssV5WxEzapNaiDtTW";

    private static void assertPairs(String input) {
        List<ImportParser.SendToEntry> entries = ImportParser.splitSendTo(input);
        assertEquals(input, 2, entries.size());
        assertEquals(A, entries.get(0).fid);
        assertEquals(1.5, ImportParser.parseAmount(entries.get(0).amount), 0);
        assertEquals(B, entries.get(1).fid);
        assertEquals(2, ImportParser.parseAmount(entries.get(1).amount), 0);
    }

    @Test
    public void flatText() {
        assertPairs(A + ",1.5," + B + ",2");
        assertPairs("[" + A + ", 1.5, " + B + ", 2]");
        assertPairs(A + "，1.5，" + B + "，2");
        assertPairs(A + ";1.5;" + B + ";2");
    }

    @Test
    public void linesFromSpreadsheetsAndNotes() {
        assertPairs(A + "\t1.5\n" + B + "\t2\n");
        assertPairs("fid,amount\r\n" + A + ",1.5\r\n" + B + ",2");
        assertPairs("# payroll\n" + A + ": 1.5 F\n" + B + " = 2FCH");
        assertPairs(A + "\n1.5\n" + B + "\n2");
        assertPairs("1.5 " + A + "\n2 " + B);
    }

    @Test
    public void json() {
        assertPairs("[\"" + A + "\",1.5,\"" + B + "\",\"2\"]");
        assertPairs("[[\"" + A + "\",1.5],[\"" + B + "\",2]]");
        assertPairs("{\"" + A + "\":1.5,\"" + B + "\":2}");
        assertPairs("[{\"fid\":\"" + A + "\",\"amount\":1.5},{\"owner\":\"" + B + "\",\"value\":200000000}]");
    }

    @Test
    public void jsonLockTime() {
        List<ImportParser.SendToEntry> entries = ImportParser.splitSendTo(
                "[{\"owner\":\"" + A + "\",\"amount\":1.5,\"lockTime\":3000000}]");
        assertEquals(Long.valueOf(3000000), entries.get(0).lockTime);
    }

    @Test
    public void unpairedIsRejected() {
        assertNull(ImportParser.splitSendTo(A + ",1.5," + B));
    }

    @Test
    public void amounts() {
        assertEquals(1.0, ImportParser.parseAmount("1."), 0);
        assertEquals(0.5, ImportParser.parseAmount(".5"), 0);
        assertEquals(0.0001, ImportParser.parseAmount("1e-4"), 0);
        assertNull(ImportParser.parseAmount("-1"));
        assertNull(ImportParser.parseAmount("abc"));
        assertNull(ImportParser.parseAmount("1,000"));
    }
}
