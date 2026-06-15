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
        assertTrue(SessionBundle.supports("imini-session/1")); // legacy still importable
        assertTrue(SessionBundle.supports("imini-session/2")); // legacy still importable
        assertTrue(SessionBundle.supports("imini-session/3")); // current
        assertFalse(SessionBundle.supports("imini-session/9"));
        assertFalse(SessionBundle.supports(null));
    }

    @Test
    void contentForHashExcludesVolatileFieldsAndIntegrity() {
        Map<String, Object> b = SessionBundle.build("s", "o", 999L,
                List.of(Map.of("role", "user", "content", "hi")), List.of(), List.of());
        b.put("integrity", "deadbeef");
        Map<String, Object> c = SessionBundle.contentForHash(b);
        assertEquals(List.of("version", "sessionId", "messages", "plans", "todos", "skillOverrides", "readers"),
                new java.util.ArrayList<>(c.keySet())); // v3 hashes overrides + readers
        assertFalse(c.containsKey("exportedAt"));
        assertFalse(c.containsKey("integrity"));

        // a legacy v1 bundle is hashed WITHOUT skillOverrides/readers, so old integrity values still verify
        Map<String, Object> v1 = new java.util.LinkedHashMap<>();
        v1.put("version", "imini-session/1");
        v1.put("sessionId", "s");
        v1.put("messages", List.of());
        v1.put("todos", List.of());
        assertFalse(SessionBundle.contentForHash(v1).containsKey("skillOverrides"));
        assertFalse(SessionBundle.contentForHash(v1).containsKey("readers"));

        // a v2 bundle hashes skillOverrides but NOT readers (so v2 integrity values still verify)
        Map<String, Object> v2 = new java.util.LinkedHashMap<>();
        v2.put("version", "imini-session/2");
        v2.put("sessionId", "s");
        v2.put("messages", List.of());
        v2.put("todos", List.of());
        v2.put("skillOverrides", List.of());
        assertTrue(SessionBundle.contentForHash(v2).containsKey("skillOverrides"));
        assertFalse(SessionBundle.contentForHash(v2).containsKey("readers"));
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

    @Test
    void bundleCarriesOverridesAndReadersAndOlderVersionsMigrateToCurrent() {
        List<Map<String, Object>> ov = List.of(Map.of("name", "commit-message", "enabled", false));
        Map<String, Object> b = SessionBundle.build("s", "o", 1L, List.of(), List.of(), List.of(), ov,
                List.of("bob", "cara"));
        assertEquals("imini-session/3", b.get("version"));
        assertEquals(1, SessionBundle.skillOverrides(b).size());
        assertEquals(List.of("bob", "cara"), SessionBundle.readers(b));

        // a v1 bundle upconverts to current with empty overrides + readers
        Map<String, Object> v1 = new java.util.LinkedHashMap<>();
        v1.put("version", "imini-session/1");
        v1.put("messages", List.of());
        Map<String, Object> m1 = SessionBundle.migrate(v1);
        assertEquals(SessionBundle.VERSION, m1.get("version"));
        assertTrue(SessionBundle.skillOverrides(m1).isEmpty());
        assertTrue(SessionBundle.readers(m1).isEmpty());

        // a v2 bundle (has overrides) upconverts to current, gaining empty readers
        Map<String, Object> v2 = new java.util.LinkedHashMap<>();
        v2.put("version", "imini-session/2");
        v2.put("messages", List.of());
        v2.put("skillOverrides", ov);
        Map<String, Object> m2 = SessionBundle.migrate(v2);
        assertEquals(SessionBundle.VERSION, m2.get("version"));
        assertEquals(1, SessionBundle.skillOverrides(m2).size());
        assertTrue(SessionBundle.readers(m2).isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void previewProjectsEachModeWithoutApplying() {
        // new: destination is empty; after == incoming
        Map<String, Object> nw = SessionBundle.preview("new", 0, 0, 0, 5, 2, 1);
        assertEquals(5, ((Map<String, Object>) nw.get("messages")).get("after"));

        // replace: messages overwritten; plans appended; todos set
        Map<String, Object> rp = SessionBundle.preview("replace", 10, 3, 4, 5, 2, 1);
        assertEquals(5, ((Map<String, Object>) rp.get("messages")).get("after"));
        assertEquals(5, ((Map<String, Object>) rp.get("plans")).get("after"));   // 4 + 1
        assertEquals(2, ((Map<String, Object>) rp.get("todos")).get("after"));

        // merge: messages appended
        Map<String, Object> mg = SessionBundle.preview("merge", 10, 3, 4, 5, 2, 1);
        assertEquals(15, ((Map<String, Object>) mg.get("messages")).get("after")); // 10 + 5
        assertEquals("merge", mg.get("mode"));
    }
}
