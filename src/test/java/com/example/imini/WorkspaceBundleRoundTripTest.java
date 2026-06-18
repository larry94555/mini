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
 * Round-trips a signed workspace bundle through real signing + verification: export (settings + durable
 * memory, HMAC-signed), then import, asserting the signature verifies and memory/settings are restored.
 * Uses a real temp SQLite DB; self-skips if persistence can't initialize.
 */
class WorkspaceBundleRoundTripTest {

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void signedBundleExportsAndImports() throws Exception {
        Path dir = Files.createTempDirectory("imini-bundle-it");
        Path dbFile = dir.resolve("test.db");
        Database db = null;
        try {
            db = new Database();
            set(db, "enabled", true);
            set(db, "dbPath", dbFile.toString());
            db.init();
            if (!db.available()) return; // skip when persistence is unavailable

            SigningService signing = new SigningService();
            set(signing, "signingSecret", "test-shared-secret-1234567890"); // HMAC signing enabled
            PluginService plugins = new PluginService(signing);
            // keep plugin export hermetic: point it at the (empty) temp workspace, not the test's cwd
            set(plugins, "root", dir);
            set(plugins, "skillsDir", "skills");
            set(plugins, "agentsDir", "agents");
            set(plugins, "commandsDir", "commands");
            SettingsStore settings = new SettingsStore(db);
            RetrievalService retrieval = new RetrievalService(db);
            MemoryStore memory = new MemoryStore(db, retrieval, null);

            WorkspaceService ws = new WorkspaceService(plugins, settings, signing, memory);

            // seed state to export
            settings.setString("agent.summary-model", "qwen2.5-3b");
            memory.setNote(MemoryStore.DEFAULT_OWNER, "the build uses Java 17 release on JDK 21");
            memory.addPin(MemoryStore.DEFAULT_OWNER, "always run ./mvnw test before shipping", "manual");

            String bundle = ws.exportJson("my-workspace", "round-trip test");
            assertTrue(bundle.contains("\"signature\""), "bundle should be signed");
            assertTrue(bundle.contains("the build uses Java 17"), "bundle carries durable memory");

            // wipe local state, then import the bundle back
            memory.clear(MemoryStore.DEFAULT_OWNER);
            memory.removePin(MemoryStore.DEFAULT_OWNER, "always run ./mvnw test before shipping");

            Map<String, Object> result = ws.importBundle(bundle, true);
            assertEquals("verified", result.get("signature"), "HMAC signature must verify");
            assertTrue(result.containsKey("memoryRestored"));

            // memory restored
            assertTrue(memory.get(MemoryStore.DEFAULT_OWNER).contains("Java 17"));
            assertTrue(memory.pinned(MemoryStore.DEFAULT_OWNER).contains("./mvnw test"));
            // settings restored
            assertEquals("qwen2.5-3b", settings.getString("agent.summary-model", ""));
        } finally {
            if (db != null) db.close();
            try { Files.deleteIfExists(dbFile); Files.deleteIfExists(dir); } catch (Exception ignore) { }
        }
    }
}
