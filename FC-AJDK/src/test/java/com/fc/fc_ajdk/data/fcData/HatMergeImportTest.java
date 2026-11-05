package com.fc.fc_ajdk.data.fcData;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Hat.mergeImport. Same cases as FreerForMac HatMergeImportTests. */
public class HatMergeImportTest {

    static final String ID = "5d".repeat(32);

    private static Hat hat(String name) {
        Hat h = new Hat();
        h.setId(ID);
        h.setName(name);
        return h;
    }

    private static List<String> list(String... s) {
        return new ArrayList<>(Arrays.asList(s));
    }

    @Test
    public void heldFieldsWinAndListsUnion() {
        Hat held = hat("my.pdf");
        held.setSize(10L);
        held.setLast(100L);
        held.setKey("k");
        held.setLeaked(false);
        held.setCipherIds(list("c1"));
        held.setLocas(list("local:///mine", "(sid)a"));

        Hat incoming = hat("other.pdf");
        incoming.setSize(10L);
        incoming.setLast(200L);
        incoming.setDesc("d");
        incoming.setTypes(list("application/pdf"));
        incoming.setPids(list("p"));
        incoming.setKey("k2");
        incoming.setLeaked(true);
        incoming.setCipherIds(list("c2", "c1"));
        incoming.setLocas(list("local:///theirs", "(sid)b", "(sid)a"));

        Hat m = Hat.mergeImport(held, incoming);
        assertSame(held, m);
        assertEquals("my.pdf", m.getName());
        assertEquals("k", m.getKey());
        assertEquals(List.of("c1", "c2"), m.getCipherIds());
        assertEquals(List.of("local:///mine", "(sid)a", "(sid)b"), m.getLocas());
        assertEquals("d", m.getDesc());
        assertEquals(List.of("application/pdf"), m.getTypes());
        assertEquals(List.of("p"), m.getPids());
        assertEquals(Boolean.TRUE, m.getLeaked());
        assertEquals(Long.valueOf(200), m.getLast());
        assertEquals(Long.valueOf(10), m.getSize());
    }

    @Test
    public void anImportFillsWhatIsMissingAndReplacesAPlaceholderName() {
        Hat held = hat(ID.substring(0, 8));
        Hat incoming = hat("Report.pdf");
        incoming.setKey("k");
        incoming.setCipherIds(list("c"));
        Hat m = Hat.mergeImport(held, incoming);
        assertEquals("Report.pdf", m.getName());
        assertEquals("k", m.getKey());
        assertEquals(List.of("c"), m.getCipherIds());
    }

    @Test
    public void aNewRecordLosesOnlyItsForeignLocalPaths() {
        Hat incoming = hat("x");
        incoming.setLocas(list("local:///theirs", "(sid)a"));
        assertEquals(List.of("(sid)a"), Hat.mergeImport(null, incoming).getLocas());
        Hat only = hat(null);
        only.setLocas(list("local:///only"));
        assertNull(Hat.mergeImport(null, only).getLocas());
    }
}
