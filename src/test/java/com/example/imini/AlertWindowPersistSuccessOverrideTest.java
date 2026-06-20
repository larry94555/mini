package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rolling-window bucket persistence (dump/load), per-route success-target overrides, overview SLO summary. */
class AlertWindowPersistSuccessOverrideTest {

    private static final long DAY = 86_400_000L;

    // ---- Feature 1: window dump/load round-trip (pure) ----

    @Test
    void dumpEmitsOnlyNonEmptyBuckets() {
        AlertSink.RollingWindow w = new AlertSink.RollingWindow(30);
        long t = 1000L * DAY;
        w.record(t, true);
        w.record(t, false);
        List<long[]> dump = w.dump();
        assertEquals(1, dump.size());
        assertEquals(1000L, dump.get(0)[0]); // day
        assertEquals(1L, dump.get(0)[1]);    // good
        assertEquals(2L, dump.get(0)[2]);    // total
    }

    @Test
    void loadRestoresBucketsIntoWindow() {
        AlertSink.RollingWindow w = new AlertSink.RollingWindow(30);
        long today = 2000L;
        w.load(today, 90, 100);
        w.load(today - 1, 5, 5);
        long[] s = w.snapshot(today * DAY);
        assertEquals(95L, s[0]);
        assertEquals(105L, s[1]);
    }

    @Test
    void dumpLoadRoundTripsAcrossWindows() {
        AlertSink.RollingWindow a = new AlertSink.RollingWindow(30);
        long t = 1234L * DAY;
        for (int i = 0; i < 7; i++) a.record(t, i % 2 == 0); // 4 good / 7 total
        AlertSink.RollingWindow b = new AlertSink.RollingWindow(30);
        for (long[] row : a.dump()) b.load(row[0], row[1], row[2]);
        assertEquals(a.snapshot(t)[0], b.snapshot(t)[0]);
        assertEquals(a.snapshot(t)[1], b.snapshot(t)[1]);
    }

    // ---- Feature 2: per-route success-target override ----

    @Test
    void parseRoutesReadsSuccessTarget() {
        Map<String, AlertSink.Route> r = AlertSink.parseRoutes("spend|https://a|tmpl|500|0.999|0.95");
        AlertSink.Route sa = r.get("spend");
        assertEquals(500L, sa.latencyMs());
        assertEquals(0.999, sa.target());
        assertEquals(0.95, sa.successTarget());
    }

    @Test
    void parseRoutesSuccessTargetInheritWhenAbsent() {
        AlertSink.Route r = AlertSink.parseRoutes("x|https://x|tmpl|500|0.99").get("x");
        assertEquals(0.0, r.successTarget()); // inherit
    }

    @Test
    void parseRatioBounds() {
        assertEquals(0.95, AlertSink.parseRatio("0.95"));
        assertEquals(0.0, AlertSink.parseRatio("1.5"));   // out of range
        assertEquals(0.0, AlertSink.parseRatio(""));      // blank inherits
        assertEquals(0.0, AlertSink.parseRatio("abc"));   // non-numeric inherits
    }

    @Test
    void successTargetForResolvesOverrideThenGlobal() {
        AlertSink s = new AlertSink(null, null);
        s.reload(null, "spend|https://a|||0|0.95", null, null, null); // success-target only
        assertEquals(0.95, s.successTargetFor("spend"));
        // an unmapped action inherits the global field (0.0 here: @Value defaults aren't injected in a unit test)
        assertEquals(0.0, s.successTargetFor("unmapped"));
    }

    // ---- Feature 3: overview SLO summary ----

    @Test
    void pctFormats() {
        assertEquals("99%", AlertsOverview.pct(0.99));
        assertEquals("\u2014", AlertsOverview.pct(Double.NaN));
        assertEquals("-50%", AlertsOverview.pct(-0.5));
    }

    @Test
    void overviewRendersSloSummary() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("delivery_slo", AlertSink.sloSnapshot(990, 1000, 0.99, 1000));
        stats.put("delivery_slo_window", AlertSink.sloSnapshot(280, 300, 0.99, 1000));
        stats.put("delivery_success_slo", AlertSink.sloSnapshot(98, 100, 0.99, 0));
        String html = AlertsOverview.render(stats, List.of());
        assertTrue(html.contains("<h2>SLO</h2>"));
        assertTrue(html.contains("id=\"s_slo_ratio\""));
        assertTrue(html.contains("id=\"s_slo_win_budget\""));
        assertTrue(html.contains("id=\"s_succ_ratio\""));
        assertTrue(html.contains("99%")); // latency success ratio
    }

    @Test
    void overviewLiveUpdatesSloCards() {
        String html = AlertsOverview.render(Map.of("sent", 1L), List.of(), 10);
        assertTrue(html.contains("setS('slo_ratio'"));
        assertTrue(html.contains("setS('succ_budget'"));
    }
}
