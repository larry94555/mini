package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditDashboardTest {

    private static AuditLog.Entry e(String user, String action, String target, String outcome) {
        return new AuditLog.Entry("id", 1700000000000L, "2023-11-14T22:13:20Z", user, action, target, outcome);
    }

    @Test
    void rendersEntriesAndHighlightsSecurityActions() {
        String html = AuditDashboard.render(List.of(
                e("alice", "capability_denied", "tool:run_command", "outside scope"),
                e("bob", "spend_alert", "threshold:1000", "crossed"),
                e("carol", "tool_rate_limited", "tool:web_fetch", "exceeded")), "", "", "", 200);
        assertTrue(html.contains("<title>imini — audit log</title>"));
        assertTrue(html.contains("capability_denied"));
        assertTrue(html.contains("spend_alert"));
        assertTrue(html.contains("tool_rate_limited"));
        assertTrue(html.contains("class=\"denied\"")); // denial + rate-limit highlighted
        assertTrue(html.contains("class=\"alert\""));   // spend alert highlighted
    }

    @Test
    void escapesUserContent() {
        String html = AuditDashboard.render(List.of(
                e("<script>x</script>", "login", "t", "ok")), "", "", "", 200);
        assertFalse(html.contains("<script>x</script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void emptyShowsPlaceholderAndReflectsFilters() {
        String html = AuditDashboard.render(List.of(), "alice", "spend_alert", "", 50);
        assertTrue(html.contains("No matching audit entries."));
        assertTrue(html.contains("value=\"alice\""));     // filter round-trips
        assertTrue(html.contains("value=\"spend_alert\""));
        assertTrue(html.contains("value=\"50\""));
    }
}
