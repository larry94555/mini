package com.example.imini;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips the core durable stores through a REAL temp SQLite database: sessions (history + ownership +
 * sharing), run history (incl. context counts and the persisted event timeline), and plans. Complements
 * MemoryStorePersistenceTest so the whole persistence layer has CI coverage, not just memory. Self-skips if
 * persistence can't initialize (e.g. no sqlite driver), so it never fails spuriously.
 */
class PersistenceRoundTripTest {

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Database open(Path dbFile) throws Exception {
        Database db = new Database();
        set(db, "enabled", true);
        set(db, "dbPath", dbFile.toString());
        db.init();
        return db;
    }

    @Test
    void coreStoresRoundTripThroughSqlite() throws Exception {
        Path dir = Files.createTempDirectory("imini-persist-it");
        Path dbFile = dir.resolve("test.db");
        Database db = null;
        try {
            db = open(dbFile);
            if (!db.available()) return; // skip when persistence is unavailable

            // --- SessionStore: history + ownership + sharing ---
            SessionStore sessions = new SessionStore(db);
            String sid = "sess-1";
            List<Map<String, Object>> history = List.of(
                    Map.of("role", "system", "content", "you are imini"),
                    Map.of("role", "user", "content", "hello"));
            sessions.save(sid, history);
            List<Map<String, Object>> loaded = sessions.get(sid);
            assertEquals(2, loaded.size());
            assertEquals("hello", loaded.get(1).get("content"));
            sessions.claim(sid, "alice");
            assertEquals("alice", sessions.owner(sid));
            sessions.share(sid, "bob");
            assertTrue(sessions.readers(sid).contains("bob"));

            // --- RunHistoryStore: context counts + event timeline survive a reload ---
            RunHistoryStore runs = new RunHistoryStore(db);
            RunHistory.Record rec = new RunHistory.Record(
                    System.currentTimeMillis(), "/chat", sid, "auto", 42, true,
                    2, 1, 3, List.of("[fold] 90000 -> 120 chars", "[compact] folded 6 messages"));
            runs.append(rec);
            List<RunHistory.Record> back = runs.loadRecent(5);
            assertTrue(back.size() >= 1);
            RunHistory.Record got = back.get(0);
            assertEquals("/chat", got.endpoint());
            assertEquals(2, got.folds());
            assertEquals(1, got.compactions());
            assertEquals(3, got.trims());
            assertEquals(2, got.events().size());
            assertTrue(got.events().get(0).startsWith("[fold]"));

            // --- PlanStore: goal + items ---
            PlanStore plans = new PlanStore(db);
            List<TodoStore.Item> items = List.of(
                    new TodoStore.Item("write the test", "done"),
                    new TodoStore.Item("ship the PR", "pending"));
            plans.save(sid, "land the feature", items);
            PlanStore.Saved saved = plans.load(sid);
            assertEquals("land the feature", saved.goal());
            assertEquals(2, saved.items().size());
            assertEquals("write the test", saved.items().get(0).content());
            assertEquals("pending", saved.items().get(1).status());
        } finally {
            if (db != null) db.close();
            try { Files.deleteIfExists(dbFile); Files.deleteIfExists(dir); } catch (Exception ignore) { }
        }
    }
}
