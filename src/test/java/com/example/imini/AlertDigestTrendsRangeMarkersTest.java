package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full budget/window trend charts, date-range filtering, and mute/catch-up trend markers. */
class AlertDigestTrendsRangeMarkersTest {

    private static Map<String, Object> row(long ts, boolean posted, String mode, Double wr, Double ds, Double br) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("ts", ts);
        m.put("time", java.time.Instant.ofEpochMilli(ts).toString());
        m.put("posted", posted);
        m.put("mode", mode);
        if (wr != null) m.put("window_ratio", wr);
        if (ds != null) m.put("delivery_success", ds);
        if (br != null) m.put("budget_remaining", br);
        m.put("summary", "s");
        return m;
    }

    // ---- Feature 2: date-range filter (pure) ----

    @Test
    void withinRangeBoundary() {
        assertTrue(AlertSink.withinRange(100, 100, 200));
        assertTrue(AlertSink.withinRange(200, 100, 200));
        assertFalse(AlertSink.withinRange(99, 100, 200));
        assertFalse(AlertSink.withinRange(201, 100, 200));
        assertTrue(AlertSink.withinRange(150, Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    void rangedReadersEmptyWithoutDb() {
        AlertSink s = new AlertSink(new AuditLog(null), null);
        assertTrue(s.sloDigestHistory(20, 0, Long.MAX_VALUE).isEmpty());
        assertTrue(s.digestAuditTrail(20, 0, Long.MAX_VALUE).isEmpty());
    }

    // ---- Feature 1: full trend charts ----

    @Test
    void overviewRendersAllThreeTrends() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("recent_digests", List.of(
                row(2000L, true, "probe", 0.98, 0.99, 0.5),
                row(1000L, true, "probe", 0.97, 0.98, 0.4)));
        String html = AlertsOverview.render(stats, List.of());
        assertTrue(html.contains("delivery-success"));
        assertTrue(html.contains("window SLO ratio"));
        assertTrue(html.contains("window budget remaining"));
        assertTrue(html.contains("digest_wtrendbox"));
        assertTrue(html.contains("digest_btrendbox"));
    }

    // ---- Feature 3: mute/catch-up markers ----

    @Test
    void trendSvgMarksMutedAndCatchup() {
        // oldest -> newest: normal, muted, then a posted (catch-up) row
        List<Map<String, Object>> rows = List.of(
                row(1000L, true, "probe", 0.99, 0.99, 0.6),
                row(2000L, false, "muted", 0.98, 0.98, 0.55),
                row(3000L, true, "probe", 0.985, 0.985, 0.5));
        String svg = AlertsOverview.digestTrendSvg(rows, "delivery_success", 160, 28);
        assertTrue(svg.contains("<rect"));                 // muted marker
        assertTrue(svg.contains("muted:"));
        assertTrue(svg.contains("catch-up after mute"));   // catch-up marker after the muted run
    }

    @Test
    void trendSvgPlainWhenNoMuteOrCatchup() {
        List<Map<String, Object>> rows = List.of(
                row(1000L, true, "probe", null, 0.99, null),
                row(2000L, true, "probe", null, 0.98, null));
        String svg = AlertsOverview.digestTrendSvg(rows, "delivery_success", 160, 28);
        assertFalse(svg.contains("<rect"));                // no muted markers
        assertFalse(svg.contains("catch-up after mute"));  // no catch-up
        assertTrue(svg.contains("<polyline"));
    }

    @Test
    void trendSvgCollectingWhenInsufficient() {
        String svg = AlertsOverview.digestTrendSvg(List.of(), "delivery_success", 160, 28);
        assertTrue(svg.contains("collecting"));
    }
}
