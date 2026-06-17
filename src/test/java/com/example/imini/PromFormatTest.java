package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure Metrics-snapshot -> Prometheus text rendering. */
class PromFormatTest {

    private static Map<String, Object> sampleSnapshot() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("uptime_ms", 90_061_000L);
        Map<String, Long> c = new TreeMap<>();
        c.put("runs_ok", 5L);
        c.put("runs_failed", 1L);
        snap.put("counters", c);
        Map<String, Long> t = new TreeMap<>();
        t.put("read_file", 3L);
        t.put("grep", 2L);
        snap.put("tool_calls_by_name", t);
        Map<String, Object> lat = new LinkedHashMap<>();
        lat.put("count", 6L);
        lat.put("avg_ms", 120L);
        lat.put("max_ms", 300L);
        snap.put("run_latency", lat);
        Map<String, Object> conc = new LinkedHashMap<>();
        conc.put("limit", 2);
        conc.put("active", 1);
        conc.put("queued", 0);
        snap.put("concurrency", conc);
        return snap;
    }

    @Test
    void rendersTypesLabelsAndValues() {
        String out = PromFormat.render(sampleSnapshot());
        assertTrue(out.contains("# TYPE imini_counter counter"));
        assertTrue(out.contains("imini_counter{name=\"runs_ok\"} 5"));
        assertTrue(out.contains("imini_counter{name=\"runs_failed\"} 1"));
        assertTrue(out.contains("imini_tool_calls{tool=\"read_file\"} 3"));
        assertTrue(out.contains("imini_uptime_seconds 90061"));
        assertTrue(out.contains("imini_run_latency_max_ms 300"));
        assertTrue(out.contains("imini_concurrency_active 1"));
    }

    @Test
    void labelsAreSortedForStableOutput() {
        String out = PromFormat.render(sampleSnapshot());
        // runs_failed sorts before runs_ok; grep before read_file
        assertTrue(out.indexOf("runs_failed") < out.indexOf("runs_ok"));
        assertTrue(out.indexOf("tool=\"grep\"") < out.indexOf("tool=\"read_file\""));
    }

    @Test
    void handlesNullAndEmpty() {
        assertEquals("", PromFormat.render(null));
        assertEquals("", PromFormat.render(new LinkedHashMap<>()));
    }

    @Test
    void escapesLabelQuotes() {
        Map<String, Object> snap = new LinkedHashMap<>();
        Map<String, Long> keys = new LinkedHashMap<>();
        keys.put("we\"ird", 1L);
        snap.put("requests_by_key", keys);
        String out = PromFormat.render(snap);
        assertTrue(out.contains("key=\"we\\\"ird\""));
    }
}
