package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Combined digest report bundle (JSON/CSV), range validation, and the overview copy-link button. */
class AlertDigestReportBundleRangeValidationTest {

    private static Map<String, Object> hrow(String time, boolean posted, String mode, Double ds, String summary) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("time", time); m.put("posted", posted); m.put("mode", mode);
        if (ds != null) m.put("delivery_success", ds);
        m.put("summary", summary);
        return m;
    }

    private static Map<String, Object> arow(String time, String user, String action, String outcome) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("time", time); m.put("user", user); m.put("action", action);
        m.put("target", ""); m.put("outcome", outcome);
        return m;
    }

    // ---- Feature 3: range validation (pure) ----

    @Test
    void rangeErrorValidatesDatesAndOrder() {
        assertNull(AlertSink.rangeError("", "", 0));               // empty = no range = ok
        assertNull(AlertSink.rangeError("2026-01-01", "2026-02-01", 0));
        assertNull(AlertSink.rangeError("bad", "x", 7));           // days>0 ignores from/to
        assertEquals("invalid 'from' date (expected YYYY-MM-DD)", AlertSink.rangeError("nope", "", 0));
        assertEquals("invalid 'to' date (expected YYYY-MM-DD)", AlertSink.rangeError("", "2026-13-99", 0));
        assertEquals("'from' must not be after 'to'", AlertSink.rangeError("2026-02-01", "2026-01-01", 0));
    }

    // ---- Feature 1: report bundle CSV (pure) ----

    @Test
    void reportCsvHasAllThreeSections() {
        Map<String, Object> mute = Map.of("muted", true, "muted_until", 1700000000000L, "muted_reason", "deploy");
        String csv = AlertSink.digestReportCsv(mute,
                List.of(hrow("t1", true, "probe", 0.99, "window 99%")),
                List.of(arow("t2", "alice", "alert_digest_mute", "muted: deploy")));
        assertTrue(csv.contains("# mute\nmuted,muted_until,muted_reason\ntrue,1700000000000,deploy"));
        assertTrue(csv.contains("# history\ntime,posted,mode,window_ratio,delivery_success,budget_remaining,summary"));
        assertTrue(csv.contains("# audit\ntime,user,action,target,outcome"));
        assertTrue(csv.contains("alice") && csv.contains("alert_digest_mute"));
    }

    @Test
    void muteStateMapShape() {
        AlertSink s = new AlertSink(null, null);
        Map<String, Object> st = s.digestMuteState();
        assertEquals(false, st.get("muted"));
        assertEquals(0L, st.get("muted_until"));
        assertEquals("", st.get("muted_reason"));
    }

    // ---- Feature 2: copy-link button on the overview ----

    @Test
    void overviewHasCopyLinkButton() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("recent_digests", List.of(hrow("t", true, "probe", 0.99, "b")));
        String html = AlertsOverview.render(stats, List.of());
        assertTrue(html.contains("copyDigestLink()"));
        assertTrue(html.contains("function copyDigestLink("));
        assertTrue(html.contains("/admin/alerts/digest-report")); // copies the bundle URL
    }
}
