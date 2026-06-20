package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SLO report rows/CSV, worst-trend route sort, and per-route persistence no-op semantics. */
class AlertRoutePersistReportSortTest {

    private static final long DAY = 86_400_000L;

    // ---- Feature 1: persistence no-op without DB (DB path is CI-only) ----

    @Test
    void flushAndPruneNoOpWithoutDatabase() {
        AlertSink s = new AlertSink(null, null);
        s.flushWindow();                 // must not throw without a DB
        assertEquals(0, s.pruneWindow()); // nothing removed without a DB
    }

    // ---- Feature 3: SLO report rows + CSV (pure) ----

    @Test
    void reportRowsEmptyWithoutTraffic() {
        assertTrue(new AlertSink(null, null).sloReportRows().isEmpty());
    }

    @Test
    void reportCsvHeaderAndShape() {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("scope", "global"); row.put("route", ""); row.put("day", 20000L);
        row.put("date", "2024-10-04"); row.put("good", 95L); row.put("total", 100L); row.put("ratio", 0.95);
        String csv = AlertSink.sloReportCsv(List.of(row));
        assertTrue(csv.startsWith("scope,route,day,date,good,total,ratio\n"));
        assertTrue(csv.contains("global,,20000,2024-10-04,95,100,0.95"));
    }

    @Test
    void reportCsvQuotesRouteWithComma() {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("scope", "route"); row.put("route", "a,b"); row.put("day", 1L);
        row.put("date", "1970-01-02"); row.put("good", 1L); row.put("total", 1L); row.put("ratio", 1.0);
        String csv = AlertSink.sloReportCsv(List.of(row));
        assertTrue(csv.contains("route,\"a,b\",1,"));
    }

    @Test
    void reportCsvEmptyIsHeaderOnly() {
        assertEquals("scope,route,day,date,good,total,ratio\n", AlertSink.sloReportCsv(List.of()));
    }

    // ---- Feature 2: worst-trend sort (pure) ----

    @Test
    void routeTrendScoreUsesMostRecentDataDay() {
        assertEquals(0.8, AlertsOverview.routeTrendScore(List.of(0.9, -1.0, 0.8)));   // latest data day
        assertEquals(0.95, AlertsOverview.routeTrendScore(List.of(0.95)));
        assertEquals(2.0, AlertsOverview.routeTrendScore(List.of(-1.0, -1.0)));        // no data -> last
        assertEquals(2.0, AlertsOverview.routeTrendScore(null));
    }

    @Test
    void overviewSortsRoutesWorstFirst() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        Map<String, Map<String, Long>> byRoute = new java.util.LinkedHashMap<>();
        byRoute.put("good_route", Map.of("sent", 9L, "failed", 0L, "dead_lettered", 0L, "suppressed", 0L));
        byRoute.put("bad_route", Map.of("sent", 5L, "failed", 5L, "dead_lettered", 3L, "suppressed", 0L));
        stats.put("by_route", byRoute);
        Map<String, List<Double>> series = new java.util.LinkedHashMap<>();
        series.put("good_route", List.of(0.99, 1.0));
        series.put("bad_route", List.of(0.6, 0.5));
        stats.put("slo_window_series_by_route", series);
        stats.put("delivery_slo", Map.of("target", 0.99));
        String html = AlertsOverview.render(stats, List.of());
        // bad_route (0.5) should appear before good_route (1.0) in the rendered table body
        int bad = html.indexOf("bad_route");
        int good = html.indexOf("good_route");
        assertTrue(bad >= 0 && good >= 0 && bad < good, "worst route should sort first");
        assertTrue(html.contains("worst trend first"));
    }
}
