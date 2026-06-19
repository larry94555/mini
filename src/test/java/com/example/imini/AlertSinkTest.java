package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertSinkTest {

    @Test
    void parsesActions() {
        Set<String> a = AlertSink.parseActions("capability_denied, spend_alert ,tool_rate_limited");
        assertEquals(3, a.size());
        assertTrue(a.contains("capability_denied"));
        assertTrue(a.contains("spend_alert"));
        assertTrue(a.contains("tool_rate_limited"));
        assertTrue(AlertSink.parseActions("").isEmpty());
        assertTrue(AlertSink.parseActions(null).isEmpty());
    }

    @Test
    void shouldForwardRespectsEnabledAndSet() throws Exception {
        AlertSink s = new AlertSink(null, null);
        set(s, "enabled", true);
        set(s, "actions", Set.of("spend_alert"));
        assertTrue(s.shouldForward("spend_alert"));
        assertFalse(s.shouldForward("login"));      // not in set
        assertFalse(s.shouldForward(null));
        set(s, "enabled", false);
        assertFalse(s.shouldForward("spend_alert")); // disabled
    }

    @Test
    void toJsonEscapesAndIncludesFields() {
        AuditLog.Entry e = new AuditLog.Entry("id", 1700000000000L, "2023-11-14T22:13:20Z",
                "alice", "capability_denied", "tool:run_command", "outside \"scope\"");
        String json = AlertSink.toJson(e);
        assertTrue(json.contains("\"action\":\"capability_denied\""));
        assertTrue(json.contains("\"user\":\"alice\""));
        assertTrue(json.contains("\"ts\":1700000000000"));
        assertTrue(json.contains("\\\"scope\\\""));   // quotes escaped
        assertTrue(json.startsWith("{") && json.endsWith("}"));
    }

    private static void set(Object o, String f, Object v) throws Exception {
        var fl = AlertSink.class.getDeclaredField(f);
        fl.setAccessible(true);
        fl.set(o, v);
    }
}
