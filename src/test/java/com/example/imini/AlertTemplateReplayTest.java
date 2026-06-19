package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structured alert templates + in-memory dead-letter fallback (no DB) behavior. */
class AlertTemplateReplayTest {

    private static AuditLog.Entry e() {
        return new AuditLog.Entry("id", 1700000000000L, "2023-11-14T22:13:20Z",
                "alice", "capability_denied", "tool:run_command", "outside scope");
    }

    @Test
    void templateSubstitutesPlaceholders() {
        String tmpl = "{\"text\":\"{action} by {user} on {target} at {ts}\"}";
        String out = AlertSink.applyTemplate(tmpl, e());
        assertEquals("{\"text\":\"capability_denied by alice on tool:run_command at 1700000000000\"}", out);
    }

    @Test
    void templateJsonEscapesStringFields() {
        AuditLog.Entry tricky = new AuditLog.Entry("id", 1L, "t", "a\"b", "act", "tar\nget", "ok");
        String out = AlertSink.applyTemplate("{\"u\":\"{user}\",\"x\":\"{target}\"}", tricky);
        assertTrue(out.contains("a\\\"b"));     // quote escaped
        assertTrue(out.contains("tar\\nget"));  // newline escaped
    }

    @Test
    void unknownPlaceholderLeftIntact() {
        assertTrue(AlertSink.applyTemplate("{foo} {action}", e()).startsWith("{foo} capability_denied"));
    }

    @Test
    void deadLetterEntriesEmptyOnFreshSink() {
        AlertSink s = new AlertSink(null, null); // no DB -> in-memory path
        assertTrue(s.deadLetterEntries().isEmpty());
        assertTrue(s.deadLetters().isEmpty());
    }

    @Test
    void replayNoOpWhenDisabled() {
        AlertSink s = new AlertSink(null, null); // not enabled, no webhook
        assertEquals(0, s.replay(null));
    }

    @Test
    void statsReportsDeadLetterPersistenceFlag() {
        AlertSink s = new AlertSink(null, null);
        assertFalse((Boolean) s.stats().get("dead_letter_persistent")); // no DB -> not persistent
        assertTrue(s.stats().containsKey("dead_letter_size"));
    }
}
