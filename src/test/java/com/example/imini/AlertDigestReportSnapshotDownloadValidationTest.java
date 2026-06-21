package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Snapshot in the report bundle, the Download-report-bundle link, and picker validation feedback. */
class AlertDigestReportSnapshotDownloadValidationTest {

    private static Map<String, Object> hrow(String time, boolean posted, String mode, Double ds, String summary) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("time", time); m.put("posted", posted); m.put("mode", mode);
        if (ds != null) m.put("delivery_success", ds);
        m.put("summary", summary);
        return m;
    }

    // ---- Feature 3: snapshot in the report CSV ----

    @Test
    void reportCsvIncludesSnapshotSection() {
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("window_success_ratio", 0.985);
        snap.put("delivery_success_ratio", 0.997);
        snap.put("worst_route", "spend_alert");
        Map<String, Object> mute = Map.of("muted", false, "muted_until", 0L, "muted_reason", "");
        String csv = AlertSink.digestReportCsv(mute, snap,
                List.of(hrow("t1", true, "probe", 0.99, "window 99%")), List.of());
        assertTrue(csv.contains("# snapshot\nkey,value\n"));
        assertTrue(csv.contains("window_success_ratio,0.985"));
        assertTrue(csv.contains("worst_route,spend_alert"));
        // sections still present and ordered after snapshot
        assertTrue(csv.indexOf("# snapshot") < csv.indexOf("# mute"));
        assertTrue(csv.indexOf("# mute") < csv.indexOf("# history"));
        assertTrue(csv.indexOf("# history") < csv.indexOf("# audit"));
    }

    @Test
    void threeArgReportCsvOmitsSnapshot() {
        // back-compat: the 3-arg form (no snapshot) starts at the mute section
        String csv = AlertSink.digestReportCsv(Map.of("muted", false, "muted_until", 0L, "muted_reason", ""),
                List.of(), List.of());
        assertTrue(csv.startsWith("# mute\n"));
        assertTrue(!csv.contains("# snapshot"));
    }

    // ---- Feature 1 + 2: overview controls ----

    private static Map<String, Object> statsWithDigests() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("recent_digests", List.of(hrow("t", true, "probe", 0.99, "b")));
        return stats;
    }

    @Test
    void overviewHasDownloadReportBundleLink() {
        String html = AlertsOverview.render(statsWithDigests(), List.of());
        assertTrue(html.contains("Download report"));
        assertTrue(html.contains("downloadDigestReport("));
        // the report download resolves to the digest-report endpoint in the JS
        assertTrue(html.contains("/admin/alerts/digest-report"));
    }

    @Test
    void applyDigestRangeSurfacesValidationError() {
        String html = AlertsOverview.render(statsWithDigests(), List.of());
        // applyDigestRange checks the response and throws the server error message on a non-ok status
        assertTrue(html.contains("if(!x.ok)"));
        assertTrue(html.contains("(e&&e.error)||'invalid range'"));
        assertTrue(html.contains("window.digestRangeActive=false;if(n)n.textContent="));
    }
}
