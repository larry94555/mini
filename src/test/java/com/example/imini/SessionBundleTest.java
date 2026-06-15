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

    @Test
    void supportsRecognizesTheMajorVersion() {
        assertTrue(SessionBundle.supports("imini-session/1"));
        assertFalse(SessionBundle.supports("imini-session/2"));
        assertFalse(SessionBundle.supports(null));
    }

    @Test
    void contentForHashExcludesVolatileFieldsAndIntegrity() {
        Map<String, Object> b = SessionBundle.build("s", "o", 999L,
                List.of(Map.of("role", "user", "content", "hi")), List.of(), List.of());
        b.put("integrity", "deadbeef");
        Map<String, Object> c = SessionBundle.contentForHash(b);
        assertEquals(List.of("version", "sessionId", "messages", "plans", "todos"),
                new java.util.ArrayList<>(c.keySet()));
        assertFalse(c.containsKey("exportedAt"));
        assertFalse(c.containsKey("integrity"));
    }

    @Test
    void integrityGetterReadsOrDefaultsToEmpty() {
        assertEquals("abc", SessionBundle.integrity(Map.of("integrity", "abc")));
        assertEquals("", SessionBundle.integrity(Map.of("version", "x")));
    }

    @Test
    void migrateWrapsStringTodosAndStampsVersion() {
        java.util.Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("version", "imini-session/1");
        b.put("messages", List.of());
        b.put("todos", List.of("buy milk", "walk dog"));
        Map<String, Object> m = SessionBundle.migrate(b);
        List<TodoStore.Item> todos = SessionBundle.todos(m);
        assertEquals(2, todos.size());
        assertEquals("buy milk", todos.get(0).content());
        assertEquals("pending", todos.get(0).status());
    }

    @Test
    void migrateRenamesHistoryAndStampsMissingVersion() {
        java.util.Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("history", List.of(Map.of("role", "user", "content", "hi")));
        Map<String, Object> m = SessionBundle.migrate(b);
        assertEquals(SessionBundle.VERSION, m.get("version"));
        assertEquals(1, SessionBundle.messages(m).size());
        assertFalse(m.containsKey("history"));
        assertTrue(SessionBundle.supports(String.valueOf(m.get("version"))));
    }

    @Test
    void migrateUpconvertsLegacyVersionAndLeavesCurrentAlone() {
        assertEquals(SessionBundle.VERSION,
                SessionBundle.migrate(Map.of("version", "imini-session/0", "messages", List.of())).get("version"));
        Map<String, Object> cur = SessionBundle.build("s", "o", 1L, List.of(), List.of(),
                List.of(new TodoStore.Item("t", "completed")));
        Map<String, Object> m = SessionBundle.migrate(cur);
        assertEquals(SessionBundle.VERSION, m.get("version"));
        assertEquals("completed", SessionBundle.todos(m).get(0).status());
    }
}
