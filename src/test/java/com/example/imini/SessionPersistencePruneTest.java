package com.example.imini;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-SQLite test of session pruning + summary; self-skips if persistence is unavailable. */
class SessionPersistencePruneTest {

    private static void set(Object o, String f, Object v) throws Exception {
        Field fl = o.getClass().getDeclaredField(f); fl.setAccessible(true); fl.set(o, v);
    }

    @Test
    void prunesIdleSessionsAndKeepsFresh() throws Exception {
        Path dir = Files.createTempDirectory("imini-prune-it");
        Path dbFile = dir.resolve("test.db");
        Database db = null;
        try {
            db = new Database();
            set(db, "enabled", true);
            set(db, "dbPath", dbFile.toString());
            db.init();
            if (!db.available()) return;

            SessionStore sessions = new SessionStore(db);
            sessions.save("old", List.of(Map.of("role", "user", "content", "old session")));
            sessions.save("fresh", List.of(Map.of("role", "user", "content", "fresh session")));

            // backdate "old" by 10 days
            long tenDaysAgo = System.currentTimeMillis() - 10L * 86_400_000L;
            db.update("UPDATE sessions SET updated_at=? WHERE session_id=?", tenDaysAgo, "old");

            long ttl = 7L * 86_400_000L; // 7 days
            int pruned = sessions.pruneExpired(ttl, System.currentTimeMillis());
            assertEquals(1, pruned, "only the 10-day-old session should be pruned");

            List<String> remaining = sessions.list();
            assertTrue(remaining.contains("fresh"));
            assertTrue(!remaining.contains("old"));

            Map<String, Object> summary = sessions.summary(System.currentTimeMillis());
            assertEquals(1L, summary.get("total"));
        } finally {
            if (db != null) db.close();
            try { Files.deleteIfExists(dbFile); Files.deleteIfExists(dir); } catch (Exception ignore) {}
        }
    }
}
