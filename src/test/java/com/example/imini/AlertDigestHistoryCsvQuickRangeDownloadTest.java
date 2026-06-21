package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Digest-history CSV export, quick-range buttons, and the CSV download links on the overview. */
class AlertDigestHistoryCsvQuickRangeDownloadTest {

    private static Map<String, Object> hrow(String time, boolean posted, String mode,
                                            Double wr, Double ds, Double br, String summary) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("time", time); m.put("posted", posted); m.put("mode", mode);
        if (wr != null) m.put("window_ratio", wr);
        if (ds != null) m.put("delivery_success", ds);
        if (br != null) m.put("budget_remaining", br);
        m.put("summary", summary);
        return m;
    }

    // ---- Feature 1: history CSV ----

    @Test
    void historyCsvHeaderAndRow() {
        String csv = AlertSink.digestHistoryCsv(List.of(
                hrow("2023-11-14T22:13:20Z", true, "probe", 0.985, 0.997, 0.5, "imini SLO digest: window 98%")));
        assertTrue(csv.startsWith("time,posted,mode,window_ratio,delivery_success,budget_remaining,summary\n"));
        assertTrue(csv.contains("2023-11-14T22:13:20Z,true,probe,0.985,0.997,0.5,"));
        assertTrue(csv.contains("imini SLO digest: window 98%"));
    }

    @Test
    void historyCsvQuotesCommasAndEmptyHeaderOnly() {
        assertEquals("time,posted,mode,window_ratio,delivery_success,budget_remaining,summary\n",
                AlertSink.digestHistoryCsv(List.of()));
        String csv = AlertSink.digestHistoryCsv(List.of(
                hrow("t", false, "muted", null, null, null, "worst latency a, b @ 90%")));
        assertTrue(csv.contains("\"worst latency a, b @ 90%\"")); // comma forces quoting
    }

    // ---- Feature 2 + 3: quick-range buttons + CSV download links on the overview ----

    private static Map<String, Object> statsWithDigests() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("recent_digests", List.of(hrow("t2", true, "probe", 0.98, 0.99, 0.5, "b")));
        return stats;
    }

    @Test
    void overviewHasQuickRangeButtons() {
        String html = AlertsOverview.render(statsWithDigests(), List.of());
        assertTrue(html.contains("quickRange(1)"));
        assertTrue(html.contains("quickRange(7)"));
        assertTrue(html.contains("quickRange(30)"));
        assertTrue(html.contains("function quickRange("));
    }

    @Test
    void overviewHasCsvDownloadLinks() {
        String html = AlertsOverview.render(statsWithDigests(), List.of());
        assertTrue(html.contains("downloadDigestCsv('history')"));
        assertTrue(html.contains("downloadDigestCsv('audit')"));
        assertTrue(html.contains("function downloadDigestCsv("));
        assertTrue(html.contains("format=csv")); // export URL carries the CSV format
    }
}
