package com.example.imini;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure-mode coverage: with persistence DISABLED (no DB) and the llama-server treated as unreachable, the
 * stores must no-op safely (no exceptions) and the readiness roll-up must report a non-"ok" status. Runs
 * fully offline -- persistence is disabled, so no sqlite driver is needed.
 */
class GracefulDegradationTest {

    private static void set(Object t, String f, Object v) throws Exception {
        Field fl = t.getClass().getDeclaredField(f);
        fl.setAccessible(true);
        fl.set(t, v);
    }

    private Database disabledDb() throws Exception {
        Database db = new Database();
        set(db, "enabled", false); // persistence off -> available() == false, no driver needed
        db.init();
        return db;
    }

    @Test
    void databaseUnavailableIsReportedAndStoresNoOpSafely() throws Exception {
        Database db = disabledDb();
        assertFalse(db.available(), "persistence disabled -> unavailable");

        // RunHistoryStore: append is a no-op, loadRecent is empty -- no throw
        RunHistoryStore runs = new RunHistoryStore(db);
        runs.append(new RunHistory.Record(1, "/chat", "s", "auto", 1, true, 0, 0, 0, List.of()));
        assertTrue(runs.loadRecent(5).isEmpty());

        // MemoryStore: every accessor degrades to empty/null without throwing
        RetrievalService retrieval = new RetrievalService(db);
        MemoryStore mem = new MemoryStore(db, retrieval, null);
        set(mem, "injectMax", 12);
        set(mem, "recallShortlist", 12);
        set(mem, "recallK", 6);
        set(mem, "rerank", false);
        mem.setNote("local", "x");                       // ignored (no DB)
        mem.addPin("local", "y", "manual");              // ignored
        assertEquals("", mem.relevantSeed("local", "q"));
        assertTrue(mem.recall("local", "q", 3).startsWith("(no durable memory"));
        assertTrue(mem.analytics("local").isEmpty());
        @SuppressWarnings("unchecked")
        List<String> pruned = (List<String>) mem.hygiene("local").get("pruned");
        assertTrue(pruned.isEmpty());
    }

    @Test
    void sessionStoreFallsBackToInMemoryWhenDbUnavailable() throws Exception {
        Database db = disabledDb();
        SessionStore sessions = new SessionStore(db);
        sessions.save("s1", List.of(Map.of("role", "user", "content", "hi")));
        // SessionStore keeps an in-memory map so a running conversation still works without persistence
        List<Map<String, Object>> got = sessions.get("s1");
        assertNotNull(got);
        assertEquals(1, got.size());
    }

    @Test
    void readinessReflectsDownDependencies() {
        // llama unreachable (serverContext()==0 -> false) while db ok -> degraded
        assertEquals("degraded", AgentController.readinessStatus(true, false));
        // both down -> down
        assertEquals("down", AgentController.readinessStatus(false, false));
    }
}
