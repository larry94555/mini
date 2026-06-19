package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageDashboardTest {

    @Test
    void rendersTenantRowsAndEscapes() {
        Map<String, Object> summary = Map.of(
                "enabled", true,
                "monthlyTokenQuota", 1000L,
                "tenants", List.of(Map.of(
                        "tenant", "alice<script>", "inputTokens", 100L, "outputTokens", 50L,
                        "totalTokens", 150L, "usd", 0.0015, "runs", 3L)),
                "alerts", List.of());
        String html = UsageDashboard.render(summary);
        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("alice&lt;script&gt;"), "tenant name must be HTML-escaped");
        assertFalse(html.contains("alice<script>"), "raw tenant name must not appear");
        assertTrue(html.contains("150"));      // total tokens
        assertTrue(html.contains("0.0015"));   // usd formatted
    }

    @Test
    void rendersEmptyAndDisabledStates() {
        String html = UsageDashboard.render(Map.of(
                "enabled", false, "monthlyTokenQuota", 0L,
                "tenants", List.of(), "alerts", List.of()));
        assertTrue(html.contains("disabled"));
        assertTrue(html.contains("No usage recorded"));
        assertTrue(html.contains("unlimited"));
    }

    @Test
    void escHandlesAllSpecials() {
        assertTrue(UsageDashboard.esc("a&b<c>\"d'e").equals("a&amp;b&lt;c&gt;&quot;d&#39;e"));
    }
}
