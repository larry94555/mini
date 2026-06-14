package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure assembly / validation / extraction of a portable session bundle. */
class SessionBundleTest {

    @Test
    void buildIncludesVersionAndAllSections() {
        Map<String, Object> b = SessionBundle.build("s1", "bob", 123L,
                List.of(Map.of("role", "user", "content", "hi")),
                List.of(Map.of("seq", 1, "goal", "g")),
                List.of(new TodoStore.Item("task", "completed")));
        assertEquals(SessionBundle.VERSION, b.get("version"));
        assertEquals("s1", b.get("sessionId"));
        assertEquals("bob", b.get("owner"));
        assertEquals(123L, b.get("exportedAt"));
        assertEquals(1, SessionBundle.messages(b).size());
        assertEquals(1, SessionBundle.plans(b).size());
    }

    @Test
    void validateFlagsEmptyAndMissingVersion() {
        assertFalse(SessionBundle.validate(Map.of()).isEmpty());
        assertFalse(SessionBundle.validate(Map.of("messages", List.of())).isEmpty()); // no version
        Map<String, Object> ok = SessionBundle.build("s", "o", 0L, List.of(), List.of(), List.of());
        assertTrue(SessionBundle.validate(ok).isEmpty());
    }

    @Test
    void todosRoundTripThroughPayloadAndBack() {
        List<TodoStore.Item> todos = List.of(new TodoStore.Item("a", "completed"),
                new TodoStore.Item("b", "pending"));
        Map<String, Object> b = SessionBundle.build("s", "o", 0L, List.of(), List.of(), todos);
        List<TodoStore.Item> back = SessionBundle.todos(b);
        assertEquals(2, back.size());
        assertEquals("a", back.get(0).content());
        assertEquals("pending", back.get(1).status());
    }

    @Test
    void extractorsTolerateMissingSections() {
        assertTrue(SessionBundle.messages(Map.of()).isEmpty());
        assertTrue(SessionBundle.plans(Map.of()).isEmpty());
        assertTrue(SessionBundle.todos(Map.of()).isEmpty());
    }
}
