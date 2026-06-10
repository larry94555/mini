package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Metrics snapshot math, with no RunService attached (gauges omitted). */
class MetricsTest {

    @Test
    @SuppressWarnings("unchecked")
    void countersAndLatencyAggregate() {
        Metrics m = new Metrics(null);
        m.inc("requests");
        m.inc("requests");
        m.incTool("read_file");
        m.recordRun(100, true);
        m.recordRun(300, false);

        Map<String, Object> snap = m.snapshot();
        Map<String, Long> counters = (Map<String, Long>) snap.get("counters");
        assertEquals(2L, counters.get("requests"));
        assertEquals(1L, counters.get("runs_ok"));
        assertEquals(1L, counters.get("runs_failed"));

        Map<String, Long> tools = (Map<String, Long>) snap.get("tool_calls_by_name");
        assertEquals(1L, tools.get("read_file"));

        Map<String, Object> lat = (Map<String, Object>) snap.get("run_latency");
        assertEquals(2L, lat.get("count"));
        assertEquals(200L, lat.get("avg_ms"));
        assertEquals(300L, lat.get("max_ms"));
        assertTrue(snap.containsKey("uptime_ms"));
    }
}
