package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sparkline target line + per-day tooltips + window-length label, and per-route daily series/sparklines. */
class AlertSparklineTargetRouteTest {

    private static final long DAY = 86_400_000L;

    // ---- Feature 1 + 3: target line, tooltips, window label ----

    @Test
    void sparklineDrawsTargetLine() {
        String svg = AlertsOverview.sparklineSvg(List.of(0.9, 0.95, 1.0), 0.99, 30, 160, 28);
        assertTrue(svg.contains("stroke-dasharray")); // dashed target line present
        assertTrue(svg.contains("target 99%"));        // target line tooltip
    }

    @Test
    void sparklineNoTargetLineWhenUnset() {
        String svg = AlertsOverview.sparklineSvg(List.of(0.9, 0.95), Double.NaN, 0, 160, 28);
        assertFalse(svg.contains("stroke-dasharray"));
    }

    @Test
    void sparklineHasPerDayTooltips() {
        String svg = AlertsOverview.sparklineSvg(List.of(0.95, 1.0), 0.99, 7, 160, 28);
        assertTrue(svg.contains("<circle"));
        assertTrue(svg.contains("today: 100%"));   // newest point
        assertTrue(svg.contains("1d ago: 95%"));   // previous point
    }

    @Test
    void sparklineSvgTitleCarriesWindowLength() {
        String svg = AlertsOverview.sparklineSvg(List.of(0.9, 0.95), 0.99, 30, 160, 28);
        assertTrue(svg.contains("<title>30-day daily success ratio</title>"));
    }

    @Test
    void sparklinePlaceholderWhenSparse() {
        assertTrue(AlertsOverview.sparklineSvg(List.of(1.0), 0.99, 30, 160, 28).contains("collecting"));
    }

    @Test
    void backwardCompatibleSparklineStillWorks() {
        assertTrue(AlertsOverview.sparklineSvg(List.of(0.9, 0.95)).contains("<polyline"));
    }

    // ---- Feature 2: per-route daily series ----

    @Test
    void perRouteSeriesEmptyWithoutTraffic() {
        AlertSink s = new AlertSink(null, null);
        assertTrue(s.sloWindowSeriesByRoute().isEmpty());
        assertTrue(s.stats().containsKey("slo_window_series_by_route"));
    }

    @Test
    void perRouteWindowRecordsViaRollingWindow() {
        // exercise the same RollingWindow the per-route map uses, to prove day-series semantics
        AlertSink.RollingWindow w = new AlertSink.RollingWindow(7);
        long today = 300L;
        for (int i = 0; i < 9; i++) w.record(today * DAY, true);
        w.record(today * DAY, false); // 9/10 today
        List<Double> s = w.series(today * DAY);
        assertEquals(7, s.size());
        assertEquals(0.9, s.get(6)); // today
        assertEquals(-1.0, s.get(0)); // older empty day
    }

    @Test
    void overviewRendersPerRouteTrendColumn() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        Map<String, Map<String, Long>> byRoute = new java.util.LinkedHashMap<>();
        byRoute.put("spend_alert", Map.of("sent", 5L, "failed", 0L, "dead_lettered", 0L, "suppressed", 0L));
        stats.put("by_route", byRoute);
        Map<String, List<Double>> series = new java.util.LinkedHashMap<>();
        series.put("spend_alert", List.of(0.9, 0.95, 1.0));
        stats.put("slo_window_series_by_route", series);
        stats.put("delivery_slo", Map.of("target", 0.99));
        String html = AlertsOverview.render(stats, List.of());
        assertTrue(html.contains("<th>trend</th>"));
        assertTrue(html.contains("<polyline"));        // route sparkline rendered
    }
}
