package com.example.imini;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-SQLite test that pruning a session cascades to ALL child tables, plus the orphan sweep. */
class SessionCascadePruneTest {

    private static void set(Object o, String f, Object v) throws Exception {
        Field fl = o.getClass().getDeclaredField(f); fl.setAccessible(true); fl.set(o, v);
    }

    private long count(Database db, String table, String id) {
        List<Long> rows = db.query("SELECT COUNT(*) FROM " + table + " WHERE session_id=?",
                rs -> rs.getLong(1), id);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    @Test
    void pruneCascadesToAllChildTables() throws Exception {
        Path dir = Files.createTempDirectory("imini-cascade-it");
        Path dbFile = dir.resolve("test.db");
        Database db = null;
        try {
            db = new Database();
            set(db, "enabled", true);
            set(db, "dbPath", dbFile.toString());
            db.init();
            if (!db.available()) return;

            SessionStore sessions = new SessionStore(db);
            sessions.save("doomed", List.of(Map.of("role", "user", "content", "hi")));

            // Insert a dependent row into every child table for "doomed".
            db.update("INSERT INTO session_owners(session_id, owner) VALUES(?,?)", "doomed", "alice");
            db.update("INSERT INTO session_shares(session_id, grantee, created_at) VALUES(?,?,?)", "doomed", "bob", 1L);
            db.update("INSERT INTO session_titles(session_id, title) VALUES(?,?)", "doomed", "T");
            db.update("INSERT INTO checkpoints(id, session_id, path, existed, created_at) VALUES(?,?,?,?,?)",
                    "ck1", "doomed", "f.txt", 1, 1L);
            db.update("INSERT INTO plans(session_id, goal, steps, updated_at) VALUES(?,?,?,?)", "doomed", "g", "[]", 1L);
            db.update("INSERT INTO plan_steps(session_id, step_index, tools, updated_at) VALUES(?,?,?,?)", "doomed", 0, "[]", 1L);
            db.update("INSERT INTO plan_history(session_id, seq, goal, created_at) VALUES(?,?,?,?)", "doomed", 0, "g", 1L);
            db.update("INSERT INTO session_skill_state(session_id, name, enabled) VALUES(?,?,?)", "doomed", "s", 1);
            db.update("INSERT INTO session_settings(session_id, key, value) VALUES(?,?,?)", "doomed", "k", "v");
            db.update("INSERT INTO scheduled_tasks(id, session_id, prompt, kind, interval_seconds, one_shot, next_run, enabled, runs, created_at) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?)", "t1", "doomed", "p", "once", 0, 1, 0L, 1, 0, 1L);

            // backdate so it's expired, then prune
            db.update("UPDATE sessions SET updated_at=? WHERE session_id=?",
                    System.currentTimeMillis() - 10L * 86_400_000L, "doomed");
            int pruned = sessions.pruneExpired(7L * 86_400_000L, System.currentTimeMillis());
            assertEquals(1, pruned);

            // every child table should now have zero rows for "doomed"
            for (String t : List.of("session_owners", "session_shares", "session_titles", "checkpoints",
                    "plans", "plan_steps", "plan_history", "session_skill_state", "session_settings",
                    "scheduled_tasks")) {
                assertEquals(0, count(db, t, "doomed"), t + " should have no rows after cascade");
            }
        } finally {
            if (db != null) db.close();
            try { Files.deleteIfExists(dbFile); Files.deleteIfExists(dir); } catch (Exception ignore) {}
        }
    }

    @Test
    void sweepOrphansRemovesRowsWithNoParentSession() throws Exception {
        Path dir = Files.createTempDirectory("imini-orphan-it");
        Path dbFile = dir.resolve("test.db");
        Database db = null;
        try {
            db = new Database();
            set(db, "enabled", true);
            set(db, "dbPath", dbFile.toString());
            db.init();
            if (!db.available()) return;

            SessionStore sessions = new SessionStore(db);
            sessions.save("live", List.of(Map.of("role", "user", "content", "hi")));

            // Orphan rows: a checkpoint + plan + title for a session that does NOT exist.
            db.update("INSERT INTO checkpoints(id, session_id, path, existed, created_at) VALUES(?,?,?,?,?)",
                    "ck-orphan", "ghost", "f.txt", 1, 1L);
            db.update("INSERT INTO plans(session_id, goal, steps, updated_at) VALUES(?,?,?,?)", "ghost", "g", "[]", 1L);
            db.update("INSERT INTO session_titles(session_id, title) VALUES(?,?)", "ghost", "T");
            // A legit row for "live" that must survive.
            db.update("INSERT INTO session_titles(session_id, title) VALUES(?,?)", "live", "Keep");

            int removed = sessions.sweepOrphans();
            assertTrue(removed >= 3, "expected at least 3 orphan rows removed, got " + removed);

            // the "live" title must still be there
            List<Long> liveTitles = db.query("SELECT COUNT(*) FROM session_titles WHERE session_id=?",
                    rs -> rs.getLong(1), "live");
            assertEquals(1L, liveTitles.get(0));
        } finally {
            if (db != null) db.close();
            try { Files.deleteIfExists(dbFile); Files.deleteIfExists(dir); } catch (Exception ignore) {}
        }
    }
}
