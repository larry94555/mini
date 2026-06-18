package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Static [MEMORY]-note helpers used for cross-session durable memory seeding. */
class ContextMemoryTest {

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    @Test
    void extractsMemoryNoteWhenPresent() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", "you are a helper"));
        messages.add(ContextManager.memoryMessageFor("user prefers metric units; project is imini"));
        messages.add(msg("user", "hello"));
        assertEquals("user prefers metric units; project is imini",
                ContextManager.extractMemoryNote(messages));
    }

    @Test
    void roundTripsThroughMemoryMessage() {
        Map<String, Object> mem = ContextManager.memoryMessageFor("durable fact A");
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(mem);
        assertEquals("durable fact A", ContextManager.extractMemoryNote(messages));
    }

    @Test
    void returnsNullWhenNoMemoryNote() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", "s"));
        messages.add(msg("user", "just a question"));
        assertNull(ContextManager.extractMemoryNote(messages));
        assertNull(ContextManager.extractMemoryNote(null));
    }
}
