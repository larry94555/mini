package com.example.imini;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end persistence test for the durable-memory subsystem against a REAL SQLite database (temp file):
 * note + pins (provenance), relevance-ranked seeding, usage analytics, the recall path, and the hygiene
 * decay pass. Runs in CI where sqlite-jdbc is on the classpath; if persistence can't initialize (e.g. no
 * driver in a stripped-down environment) it skips cleanly rather than failing.
 *
 * Named *Test (not *IT) so Surefire actually executes it -- the project has no Failsafe plugin.
 */
class MemoryStorePersistenceTest {

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Database openDb(Path dbFile) throws Exception {
        Database db = new Database();
        set(db, "enabled", true);
        set(db, "dbPath", dbFile.toString());
        db.init();
        return db;
    }

    private MemoryStore newStore(Database db) throws Exception {
        RetrievalService retrieval = new RetrievalService(db); // embeddings off -> lexical ranking
        MemoryStore m = new MemoryStore(db, retrieval, null);   // rerank off below, so llama is unused
        set(m, "injectMax", 2);
        set(m, "recallK", 2);
        set(m, "recallShortlist", 4);
        set(m, "rerank", false);
        set(m, "decayDays", 0); // so a never-used fact is immediately eligible for hygiene
        return m;
    }

    @Test
    void durableMemoryRoundTripsThroughSqlite() throws Exception {
        Path dir = Files.createTempDirectory("imini-mem-it");
        Path dbFile = dir.resolve("test.db");
        Database db = null;
        try {
            db = openDb(dbFile);
            if (!IntegrationGate.proceed("MemoryStorePersistenceTest", db.available())) return; // skip / hard-fail per IMINI_REQUIRE_PERSISTENCE

            MemoryStore m = newStore(db);
            String owner = "local";

            // 1) note + pin (with provenance)
            m.setNote(owner, "a fact about alpha\na fact about beta\na fact about gamma");
            m.addPin(owner, "always keep this pinned fact", "manual");
            assertTrue(m.pinned(owner).contains("always keep this pinned fact"));
            List<Map<String, Object>> pins = m.pinsDetailed(owner);
            assertEquals(1, pins.size());
            assertEquals("manual", pins.get(0).get("source"));

            // 2) relevance-ranked seeding: pin always + top auto fact for the query (injectMax=2 -> room=1)
            String seed = m.relevantSeed(owner, "tell me about alpha");
            assertTrue(seed.contains("always keep this pinned fact"), "pin must always seed");
            assertTrue(seed.contains("a fact about alpha"), "most relevant auto fact must seed");
            assertFalse(seed.contains("a fact about gamma"), "gamma is off-topic and beyond the cap");

            // 3) recall bumps the recalled counter for the matched fact
            String recalled = m.recall(owner, "what about beta", 2);
            assertTrue(recalled.contains("a fact about beta"));

            // 4) analytics reflect injection + recall
            Map<String, Integer> inj = new java.util.HashMap<>();
            Map<String, Integer> rec = new java.util.HashMap<>();
            for (Map<String, Object> f : m.analytics(owner)) {
                inj.put(String.valueOf(f.get("fact")), ((Number) f.get("injected")).intValue());
                rec.put(String.valueOf(f.get("fact")), ((Number) f.get("recalled")).intValue());
            }
            assertTrue(inj.getOrDefault("a fact about alpha", 0) >= 1, "alpha was injected");
            assertTrue(rec.getOrDefault("a fact about beta", 0) >= 1, "beta was recalled");

            // 5) hygiene prunes the never-used, aged-out fact (gamma) but keeps used ones and never pins
            Map<String, Object> report = m.hygiene(owner);
            @SuppressWarnings("unchecked")
            List<String> pruned = (List<String>) report.get("pruned");
            assertTrue(pruned.contains("a fact about gamma"), "unused gamma should be pruned, got " + pruned);
            String noteAfter = m.get(owner);
            assertFalse(noteAfter.contains("a fact about gamma"), "gamma removed from note");
            assertTrue(noteAfter.contains("a fact about alpha"), "used alpha retained");
            assertTrue(m.pinned(owner).contains("always keep this pinned fact"), "pins never pruned");
        } finally {
            if (db != null) db.close();
            try { Files.deleteIfExists(dbFile); Files.deleteIfExists(dir); } catch (Exception ignore) { }
        }
    }
}
