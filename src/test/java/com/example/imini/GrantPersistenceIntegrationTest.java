package com.example.imini;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Track B — durable grants against a REAL temp SQLite database (not the in-memory double used by
 * {@link GrantPersistenceTest}). Boots a {@link Database} on a tempfile, runs its migrations so the
 * {@code workspace_grants} table exists, and drives {@link WorkspaceRoots} through a real
 * grant → reload → revoke → TTL-prune cycle, asserting the rows actually persist, reload, disappear on
 * revoke, and are pruned past the TTL via real SQL.
 *
 * <p>Self-skips cleanly when persistence can't initialize (e.g. no sqlite-jdbc driver on the classpath), so
 * it never fails an offline build — mirroring {@code PersistenceRoundTripTest} and the live golden traces.
 * Runs for real in CI where sqlite-jdbc is present. Cleans up its temp database.
 */
class GrantPersistenceIntegrationTest {

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

  /** A WorkspaceRoots backed by a real GrantStore over {@code db}, with the given enabled/ttl/clock. */
  private WorkspaceRoots roots(Database db, String def, long ttlSeconds, long nowMs) throws Exception {
    WorkspaceRoots w = new WorkspaceRoots();
    set(w, "workspaceRootCfg", def);
    set(w, "enabled", true);
    set(w, "rootsCfg", "");
    set(w, "grantTtlSeconds", ttlSeconds);
    set(w, "grants", new GrantStore(db));
    set(w, "nowOverride", nowMs);
    Method load = WorkspaceRoots.class.getDeclaredMethod("load");
    load.setAccessible(true);
    load.invoke(w);
    return w;
  }

  private long countRows(Database db, String sessionId) {
    List<Long> n = db.query(
        "SELECT COUNT(*) FROM workspace_grants WHERE session_id = ?",
        rs -> rs.getLong(1), sessionId);
    return n.isEmpty() ? -1 : n.get(0);
  }

  @Test
  void realGrantPersistsReloadsRevokesAndPrunes() throws Exception {
    Path dir = Files.createTempDirectory("imini-grants-it");
    Path dbFile = dir.resolve("test.db");
    Path def = Files.createDirectories(dir.resolve("default"));
    Path projRw = Files.createDirectories(dir.resolve("projRw"));
    Path projRo = Files.createDirectories(dir.resolve("projRo"));
    Database db = null;
    try {
      db = open(dbFile);
      if (!IntegrationGate.proceed("GrantPersistenceIntegrationTest", db.available())) {
        return; // no sqlite driver -> skip (or hard-fail when IMINI_REQUIRE_PERSISTENCE is set)
      }

      // --- grant in "process 1" (persists real rows) ---
      WorkspaceRoots wr1 = roots(db, def.toString(), 0, 1_000L);
      wr1.add("s1", projRw, WorkspaceRoots.Access.READ_WRITE);
      wr1.add("s2", projRo, WorkspaceRoots.Access.READ);
      assertTrue(wr1.canWrite("s1", projRw.resolve("f").toString()), "writable in process 1");
      assertEquals(1, countRows(db, "s1"), "s1 row persisted via real SQL");
      assertEquals(1, countRows(db, "s2"), "s2 row persisted via real SQL");

      // --- reload in "process 2" over the SAME database (fresh registry + fresh GrantStore) ---
      WorkspaceRoots wr2 = roots(db, def.toString(), 0, 2_000L);
      assertTrue(wr2.canWrite("s1", projRw.resolve("f").toString()), "grant reloaded from SQLite");
      assertTrue(wr2.canRead("s2", projRo.resolve("f").toString()), "read grant reloaded");
      assertFalse(wr2.canWrite("s2", projRo.resolve("f").toString()), "read grant stays read-only after reload");
      assertFalse(wr2.canWrite("s2", projRw.resolve("f").toString()), "cross-session isolation survives reload");

      // --- revoke removes the real row; a later reload does not bring it back ---
      assertTrue(wr2.remove("s1", projRw), "revoke succeeds");
      assertEquals(0, countRows(db, "s1"), "s1 row deleted via real SQL");
      WorkspaceRoots wr3 = roots(db, def.toString(), 0, 3_000L);
      assertFalse(wr3.canWrite("s1", projRw.resolve("f").toString()), "revoked grant does not reload");

      // --- TTL prune: an old grant is pruned from the table on reload via real SQL ---
      Path projTtl = Files.createDirectories(dir.resolve("projTtl"));
      WorkspaceRoots wrA = roots(db, def.toString(), 0, 1_000_000L);
      wrA.add("sttl", projTtl, WorkspaceRoots.Access.READ_WRITE);
      assertEquals(1, countRows(db, "sttl"), "ttl grant persisted");
      // New process with TTL=10s, clock 20s after the grant -> expired -> pruned on reload.
      WorkspaceRoots wrB = roots(db, def.toString(), 10, 1_000_000L + 20_000L);
      assertFalse(wrB.canWrite("sttl", projTtl.resolve("f").toString()), "expired grant not honored after reload");
      assertEquals(0, countRows(db, "sttl"), "expired row pruned from SQLite via real SQL");
    } finally {
      if (db != null) {
        db.close();
      }
      try {
        Files.walk(dir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(p -> {
              try {
                Files.deleteIfExists(p);
              } catch (Exception ignore) {
                // best effort
              }
            });
      } catch (Exception ignore) {
        // best effort
      }
    }
  }
}
