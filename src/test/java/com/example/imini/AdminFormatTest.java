package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure admin-dashboard formatting: uptime, top-N tallies, success rate. */
class AdminFormatTest {

    @Test
    void humanizeUptimeBuildsFromLargestUnit() {
        assertEquals("1d 1h 1m 1s", AdminFormat.humanizeUptime(90_061_000L));
        assertEquals("1m 5s", AdminFormat.humanizeUptime(65_000L));
        assertEquals("5s", AdminFormat.humanizeUptime(5_000L));
        assertEquals("0s", AdminFormat.humanizeUptime(0L));
        assertEquals("0s", AdminFormat.humanizeUptime(-1L)); // clamps negative
    }

    @Test
    void topNSortsByCountThenName() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("grep", 5L);
        m.put("read_file", 10L);
        m.put("glob", 10L);   // tie with read_file -> "glob" first (name order)
        m.put("edit", 1L);
        List<Map<String, Object>> top = AdminFormat.topN(m, 2);
        assertEquals(2, top.size());
        assertEquals("glob", top.get(0).get("name"));
        assertEquals(10L, top.get(0).get("count"));
        assertEquals("read_file", top.get(1).get("name"));
    }

    @Test
    void topNHandlesNullAndZeroLimit() {
        assertEquals(0, AdminFormat.topN(null, 5).size());
        assertEquals(0, AdminFormat.topN(Map.of("a", 1L), 0).size());
    }

    @Test
    void successRateIsWholePercentAndZeroWhenNoRuns() {
        assertEquals(75, AdminFormat.successRate(3, 1));
        assertEquals(100, AdminFormat.successRate(5, 0));
        assertEquals(0, AdminFormat.successRate(0, 0));
        assertEquals(0, AdminFormat.successRate(0, 4));
    }
}
