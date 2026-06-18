package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Durable SLO aggregation from persisted records (pure). */
class WindowStatsTest {
    @Test
    void aggregatesSuccessRateAndPercentiles() {
        List<RunHistory.Record> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        long[] ms = {100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
        for (int i = 0; i < 10; i++) {
            rows.add(new RunHistory.Record(now - i * 1000L, "/chat", "s", "auto",
                    ms[i], i != 3 && i != 7 /* two failures */, 0, 0, 0, List.of()));
        }
        Map<String, Object> st = RunHistoryStore.windowStatsFrom(rows, now - 3_600_000L);
        assertEquals(10L, st.get("runs"));
        assertEquals(8L, st.get("ok"));
        assertEquals(2L, st.get("failed"));
        assertEquals(80.0, st.get("success_rate"));
        assertEquals(500L, st.get("p50_ms"));   // nearest-rank p50 of 100..1000
        assertEquals(1000L, st.get("p95_ms"));
        assertEquals(550L, st.get("avg_ms"));
        assertEquals(1000L, st.get("max_ms"));
    }

    @Test
    void emptyIsHundredPercent() {
        Map<String, Object> st = RunHistoryStore.windowStatsFrom(new ArrayList<>(), 0);
        assertEquals(0L, st.get("runs"));
        assertEquals(100.0, st.get("success_rate"));
        assertEquals(0L, st.get("p95_ms"));
    }
}
