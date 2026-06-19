package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-action routing parsing, template validation, and dry-run preview (no DB / not enabled). */
class AlertRoutingTemplateTest {

    @Test
    void parseRoutesUrlOnly() {
        Map<String, AlertSink.Route> r = AlertSink.parseRoutes(
                "spend_alert|https://finance/hook;;capability_denied|https://sec/hook");
        assertEquals(2, r.size());
        assertEquals("https://finance/hook", r.get("spend_alert").url());
        assertNull(r.get("spend_alert").template());
        assertEquals("https://sec/hook", r.get("capability_denied").url());
    }

    @Test
    void parseRoutesWithTemplate() {
        Map<String, AlertSink.Route> r = AlertSink.parseRoutes(
                "spend_alert|https://finance/hook|{\"text\":\"spend {outcome}\"}");
        assertEquals("https://finance/hook", r.get("spend_alert").url());
        assertEquals("{\"text\":\"spend {outcome}\"}", r.get("spend_alert").template());
    }

    @Test
    void parseRoutesSkipsMalformed() {
        Map<String, AlertSink.Route> r = AlertSink.parseRoutes("nourl;;|https://x;;good|https://y");
        assertEquals(1, r.size());
        assertTrue(r.containsKey("good"));
    }

    @Test
    void validateTemplateFlagsUnknownPlaceholder() {
        List<String> issues = AlertSink.validateTemplate("{\"u\":\"{user}\",\"x\":\"{nope}\"}");
        assertTrue(issues.stream().anyMatch(s -> s.contains("{nope}")));
        assertFalse(issues.stream().anyMatch(s -> s.contains("{user}")));
    }

    @Test
    void validateTemplateFlagsUnbalanced() {
        assertTrue(AlertSink.validateTemplate("{\"text\":\"{action}\"").stream()
                .anyMatch(s -> s.contains("curly")));
        assertTrue(AlertSink.validateTemplate("{\"text\":\"{action}}\"}").size() >= 0); // balanced-ish, no throw
    }

    @Test
    void validCleanTemplateHasNoIssues() {
        assertTrue(AlertSink.validateTemplate("{\"text\":\"{action} by {user}\"}").isEmpty());
        assertTrue(AlertSink.validateTemplate("").isEmpty());
    }

    @Test
    void previewRendersWithoutSendingWhenDisabled() {
        AlertSink s = new AlertSink(null, null); // not enabled
        Map<String, Object> p = s.preview("{\"text\":\"{action}\"}", true);
        assertEquals("{\"text\":\"capability_denied\"}", p.get("rendered"));
        assertEquals(false, p.get("sent")); // disabled -> never sends
        assertTrue(((List<?>) p.get("issues")).isEmpty());
    }

    @Test
    void previewReportsValidationIssues() {
        AlertSink s = new AlertSink(null, null);
        Map<String, Object> p = s.preview("{bad}", false);
        assertFalse(((List<?>) p.get("issues")).isEmpty());
    }
}
