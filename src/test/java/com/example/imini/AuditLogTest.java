package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
