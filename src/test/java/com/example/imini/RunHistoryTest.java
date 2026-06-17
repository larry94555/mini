package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure bounded run-history ring buffer. */
class RunHistoryTest {

    private static RunHistory.Record rec(int i) {
        return new RunHistory.Record(i, "/ask", "s" + i, "auto", i * 10L, true);
    }

    @Test
    void capacityDropsOldest() {
        RunHistory h = new RunHistory(3);
        for (int i = 1; i <= 5; i++) h.add(rec(i));
        assertEquals(3, h.size());
        List<RunHistory.Record> r = h.recent(10);
        assertEquals("s5", r.get(0).session()); // newest first
        assertEquals("s4", r.get(1).session());
        assertEquals("s3", r.get(2).session());
    }

    @Test
    void recentRespectsLimitAndIsNewestFirst() {
        RunHistory h = new RunHistory(10);
        for (int i = 1; i <= 4; i++) h.add(rec(i));
        assertEquals(2, h.recent(2).size());
        assertEquals("s4", h.recent(2).get(0).session());
        assertEquals(4, h.recent(99).size()); // limit larger than size
        assertEquals(0, h.recent(0).size());
    }

    @Test
    void recentMapsExposeFields() {
        RunHistory h = new RunHistory(5);
        h.add(rec(7));
        Map<String, Object> m = h.recentMaps(1).get(0);
        assertEquals("/ask", m.get("endpoint"));
        assertEquals("s7", m.get("session"));
        assertEquals("auto", m.get("mode"));
        assertEquals(70L, m.get("ms"));
        assertEquals(true, m.get("ok"));
    }

    @Test
    void handlesEmptyAndNull() {
        RunHistory h = new RunHistory(2);
        assertEquals(0, h.recent(3).size());
        h.add(null);
        assertEquals(0, h.size());
        assertTrue(new RunHistory(0).size() >= 0); // capacity floored, no crash
    }
}
