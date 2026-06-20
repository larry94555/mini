package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Window-prune horizon math, rolling-window daily series, and the overview sparkline rendering. */
class AlertWindowPruneSparklineTest {

    private static final long DAY = 86_400_000L;

    // ---- Feature 1: prune horizon (pure) ----

    @Test
    void windowFloorDayIsInclusiveLowerBound() {
        long today = 1000L;
        assertEquals(971L, AlertSink.windowFloorDay(today * DAY, 30)); // 1000 - 30 + 1
        assertEquals(1000L, AlertSink.windowFloorDay(today * DAY, 1)); // single-day window
        assertEquals(1000L, AlertSink.windowFloorDay(today * DAY, 0)); // clamps to >=1 day
    }

    @Test
    void pruneNoOpWithoutDatabase() {
        assertEquals(0, new AlertSink(null, null).pruneWindow()); // no DB -> nothing removed
    }

    // ---- Feature 3: rolling-window series (pure) ----

    @Test
    void seriesIsDayOrderedWithGaps() {
        AlertSink.RollingWindow w = new AlertSink.RollingWindow(5);
        long today = 100L;
        // record on day 100 (8/10) and day 98 (1/2); days 96/97/99 have no data
        for (int i = 0; i < 8; i++) w.record(today * DAY, true);
        for (int i = 0; i < 2; i++) w.record(today * DAY, false);
        w.record(98L * DAY, true);
        w.record(98L * DAY, false);
        List<Double> s = w.series(today * DAY);
        assertEquals(5, s.size());           // days 96..100
        assertEquals(-1.0, s.get(0));        // day 96 no data
        assertEquals(-1.0, s.get(1));        // day 97 no data
        assertEquals(0.5, s.get(2));         // day 98: 1/2
        assertEquals(-1.0, s.get(3));        // day 99 no data
        assertEquals(0.8, s.get(4));         // day 100: 8/10
    }

    @Test
    void statsExposesSeries() {
        Map<String, Object> stats = new AlertSink(null, null).stats();
        assertTrue(stats.containsKey("slo_window_series"));
        assertTrue(stats.get("slo_window_series") instanceof List);
    }

    // ---- Feature 3: sparkline rendering (pure) ----

    @Test
    void sparklinePointsSkipsGapsAndScales() {
        // ratios: 1.0, gap, 0.0 over width 100 height 20 -> points at x=0,y=0 and x=100,y=20
        String pts = AlertsOverview.sparklinePoints(List.of(1.0, -1.0, 0.0), 100, 20);
        assertEquals("0,0 100,20", pts);
    }

    @Test
    void sparklineNeedsTwoPoints() {
        assertEquals("", AlertsOverview.sparklinePoints(List.of(1.0), 100, 20));      // single point
        assertEquals("", AlertsOverview.sparklinePoints(List.of(-1.0, -1.0), 100, 20)); // all gaps
    }

    @Test
    void sparklineSvgPlaceholderWhenSparse() {
        assertTrue(AlertsOverview.sparklineSvg(List.of(1.0)).contains("collecting"));
        assertTrue(AlertsOverview.sparklineSvg(List.of(0.9, 0.95)).contains("<polyline"));
    }

    @Test
    void overviewRendersSparkline() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("slo_window_series", List.of(0.9, 0.95, 1.0));
        String html = AlertsOverview.render(stats, List.of());
        assertTrue(html.contains("id=\"sparkbox\""));
        assertTrue(html.contains("<polyline"));
        assertTrue(html.contains("window success ratio"));
    }
}
