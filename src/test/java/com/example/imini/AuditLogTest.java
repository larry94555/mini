package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure filtering of audit entries by user (exact), action (substring), target (substring), with paging. */
class AuditLogTest {

    private static List<AuditLog.Entry> sample() {
        List<AuditLog.Entry> es = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            es.add(new AuditLog.Entry("id" + i, i, "t" + i,
                    i % 2 == 0 ? "alice" : "bob",
                    i < 3 ? "import" : "skill-toggle",
                    "session:s" + i, "ok"));
        }
        return es;
    }

    @Test
    void filtersByActionUserTargetAndPages() {
        List<AuditLog.Entry> es = sample();
        assertEquals(3, AuditLog.filter(es, null, "import", null, 0, 10).size());
        assertEquals(3, AuditLog.filter(es, "alice", null, null, 0, 10).size());
        assertEquals(1, AuditLog.filter(es, null, null, "session:s2", 0, 10).size());
        // paging: offset 1, limit 2 -> 2 rows
        assertEquals(2, AuditLog.filter(es, null, null, null, 1, 2).size());
        // offset past the end -> empty
        assertEquals(0, AuditLog.filter(es, null, null, null, 99, 10).size());
    }

    @Test
    void actionMatchIsCaseInsensitiveSubstring() {
        assertEquals(2, AuditLog.filter(sample(), null, "TOGGLE", null, 0, 10).size());
    }

    @Test
    void filterRangeAppliesTimeWindow() {
        List<AuditLog.Entry> es = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            es.add(new AuditLog.Entry("id" + i, i * 100L, "t" + i, "alice", "import", "session:s", "ok"));
        }
        // ts are 0,100,200,300,400; window [150,350] -> 200,300
        assertEquals(2, AuditLog.filterRange(es, null, null, null, 150, 350, 100).size());
        // 0/0 = unbounded
        assertEquals(5, AuditLog.filterRange(es, null, null, null, 0, 0, 100).size());
        // limit caps
        assertEquals(2, AuditLog.filterRange(es, null, null, null, 0, 0, 2).size());
    }

    @Test
    void toCsvWritesHeaderAndEscapesFields() {
        String csv = AuditLog.toCsv(List.of(
                new AuditLog.Entry("x1", 1L, "T", "al,ice", "imp\"ort", "session:s", "ok")));
        assertTrue(csv.startsWith("id,ts,time,user,action,target,outcome\n"));
        assertTrue(csv.contains("\"al,ice\""));       // comma -> quoted
        assertTrue(csv.contains("\"imp\"\"ort\""));   // quote -> doubled + quoted
    }
}
