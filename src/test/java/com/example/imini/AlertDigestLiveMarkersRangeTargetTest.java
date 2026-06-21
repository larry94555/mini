package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Marker-faithful live trend (JS function present), date-range picker controls, and window-ratio target line. */
class AlertDigestLiveMarkersRangeTargetTest {

    private static Map<String, Object> row(long ts, boolean posted, String mode, Double wr, Double ds, Double br) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("ts", ts); m.put("time", java.time.Instant.ofEpochMilli(ts).toString());
        m.put("posted", posted); m.put("mode", mode);
        if (wr != null) m.put("window_ratio", wr);
        if (ds != null) m.put("delivery_success", ds);
        if (br != null) m.put("budget_remaining", br);
        m.put("summary", "s");
        return m;
    }

    private static Map<String, Object> statsWithDigests() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        // give the overview an SLO target so the window-ratio trend can draw a target line
        stats.put("delivery_slo", Map.of("target", 0.99));
        stats.put("delivery_slo_window", Map.of("window_days", 30));
        stats.put("recent_digests", List.of(
                row(3000L, true, "probe", 0.985, 0.99, 0.5),
                row(2000L, false, "muted", 0.98, 0.98, 0.45),
                row(1000L, true, "probe", 0.99, 0.985, 0.6)));
        return stats;
    }

    // ---- Feature 1: marker-faithful live trend ----

    @Test
    void liveTrendSvgFunctionPresent() {
        // the auto-refresh build must define a marker-aware trendSVG and use it for the delivery-success box
        String html = AlertsOverview.render(statsWithDigests(), List.of(), 5); // autoRefresh on
        assertTrue(html.contains("function trendSVG("));
        assertTrue(html.contains("trendSVG(rd,'delivery_success',160,28)"));
        assertTrue(html.contains("catch-up after mute")); // marker logic embedded in the JS
    }

    @Test
    void serverTrendStillHasMarkers() {
        String html = AlertsOverview.render(statsWithDigests(), List.of());
        assertTrue(html.contains("<rect"));               // muted marker server-rendered
        assertTrue(html.contains("catch-up after mute"));  // catch-up marker server-rendered
    }

    // ---- Feature 2: date-range picker ----

    @Test
    void dateRangePickerControlsPresent() {
        String html = AlertsOverview.render(statsWithDigests(), List.of());
        assertTrue(html.contains("id=\"digest_from\""));
        assertTrue(html.contains("id=\"digest_to\""));
        assertTrue(html.contains("applyDigestRange()"));
        assertTrue(html.contains("resetDigestRange()"));
        assertTrue(html.contains("/admin/alerts/slo-digest/history"));
        assertTrue(html.contains("/admin/alerts/digest-audit"));
    }

    @Test
    void livePollGuardsRangeView() {
        // while a range is pinned, the poll must not overwrite the digest tables/trends
        String html = AlertsOverview.render(statsWithDigests(), List.of(), 5);
        assertTrue(html.contains("window.digestRangeActive"));
        assertTrue(html.contains("if(!window.digestRangeActive)"));
    }

    // ---- Feature 3: window-ratio target line ----

    @Test
    void windowRatioTrendHasTargetLine() {
        // with a target present, the window-ratio sparkline draws the dashed reference line (stroke-dasharray)
        String html = AlertsOverview.render(statsWithDigests(), List.of());
        assertTrue(html.contains("window SLO ratio"));
        assertTrue(html.contains("stroke-dasharray")); // target reference line present somewhere in the SVGs
        // live: the window-ratio metric trend keeps the target (true), budget does not (false)
        String live = AlertsOverview.render(statsWithDigests(), List.of(), 5);
        assertTrue(live.contains("metricTrend('digest_wtrendbox','window_ratio',true)"));
        assertTrue(live.contains("metricTrend('digest_btrendbox','budget_remaining',false)"));
    }
}
