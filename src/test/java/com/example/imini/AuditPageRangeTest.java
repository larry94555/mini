package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditPageRangeTest {

    private static AuditLog.Entry at(long ts, String action) {
        return new AuditLog.Entry("id" + ts, ts, java.time.Instant.ofEpochMilli(ts).toString(),
                "alice", action, "t", "ok");
    }

    private static List<AuditLog.Entry> sample() {
        List<AuditLog.Entry> l = new ArrayList<>();
        for (long i = 10; i >= 1; i--) l.add(at(i * 1000, i % 2 == 0 ? "spend_alert" : "login")); // newest first
        return l;
    }

    @Test
    void pagingSlicesByOffsetAndLimit() {
        List<AuditLog.Entry> page1 = AuditLog.filterRangePaged(sample(), null, null, null, 0, 0, 0, 3);
        assertEquals(3, page1.size());
        assertEquals(10_000, page1.get(0).ts()); // newest
        List<AuditLog.Entry> page2 = AuditLog.filterRangePaged(sample(), null, null, null, 0, 0, 3, 3);
        assertEquals(3, page2.size());
        assertEquals(7_000, page2.get(0).ts()); // 4th overall
    }

    @Test
    void timeWindowBounds() {
        // [since, until] inclusive on ts; keep 4000..7000
        List<AuditLog.Entry> win = AuditLog.filterRangePaged(sample(), null, null, null, 4000, 7000, 0, 100);
        assertEquals(4, win.size());
        for (AuditLog.Entry e : win) assertTrue(e.ts() >= 4000 && e.ts() <= 7000);
    }

    @Test
    void actionFilterCombinesWithRange() {
        List<AuditLog.Entry> hits = AuditLog.filterRangePaged(sample(), null, "spend_alert", null, 0, 0, 0, 100);
        assertEquals(5, hits.size()); // even ts -> spend_alert
        for (AuditLog.Entry e : hits) assertEquals("spend_alert", e.action());
    }
}
