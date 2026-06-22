package com.example.imini;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Track B — durable grant persistence. Fully offline: a {@link GrantStore} double backed by an in-memory
 * list (no live SQLite), injected into {@link WorkspaceRoots}. Proves grants survive a simulated reload, an
 * expired grant is not reloaded, a revoke removes the persisted row, and disabled mode never touches the
 * store.
 */
public class GrantPersistenceTest {

  /** In-memory stand-in for the SQLite-backed GrantStore. */
  static final class FakeGrantStore extends GrantStore {
    final List<GrantRow> rows = new ArrayList<>();
    boolean active = true;
    int saves = 0;
    int deletes = 0;

    FakeGrantStore() {
      super(null);
    }

    @Override
    public boolean active() {
      return active;
    }

    @Override
    public void save(String sessionId, String path, String access, long grantedAt) {
      if (!active) return;
      saves++;
      rows.removeIf(r -> r.sessionId().equals(sessionId) && r.path().equals(path));
      rows.add(new GrantRow(sessionId, path, access, grantedAt));
    }

    @Override
    public void delete(String sessionId, String path) {
      if (!active) return;
      deletes++;
      rows.removeIf(r -> r.sessionId().equals(sessionId) && r.path().equals(path));
    }

    @Override
    public void deleteOlderThan(long cutoffMillis) {
      if (!active) return;
      rows.removeIf(r -> r.grantedAt() < cutoffMillis);
    }

    @Override
    public List<GrantRow> loadAll() {
      return active ? new ArrayList<>(rows) : List.of();
    }
  }

  private static void set(Object o, String field, Object value) throws Exception {
    Field f = o.getClass().getDeclaredField(field);
    f.setAccessible(true);
    f.set(o, value);
  }

  /** Build a WorkspaceRoots wired to the given store, with the given enabled/ttl, and run load(). */
  private static WorkspaceRoots build(boolean enabled, String def, long ttlSeconds, GrantStore store, long nowMs)
      throws Exception {
    WorkspaceRoots w = new WorkspaceRoots();
    set(w, "workspaceRootCfg", def);
    set(w, "enabled", enabled);
    set(w, "rootsCfg", "");
    set(w, "grantTtlSeconds", ttlSeconds);
    set(w, "grants", store);
    set(w, "nowOverride", nowMs);
    Method load = WorkspaceRoots.class.getDeclaredMethod("load");
    load.setAccessible(true);
    load.invoke(w);
    return w;
  }

  @Test
  void grantSurvivesAReload() throws Exception {
    Path base = Files.createTempDirectory("persist");
    Path def = Files.createDirectories(base.resolve("def"));
    Path proj = Files.createDirectories(base.resolve("proj"));
    FakeGrantStore store = new FakeGrantStore();

    // First "process": grant a root (persists to the store).
    WorkspaceRoots first = build(true, def.toString(), 0, store, 1_000L);
    first.add("s1", proj, WorkspaceRoots.Access.READ_WRITE);
    assertTrue(store.saves >= 1, "grant was persisted");
    assertTrue(first.canWrite("s1", proj.resolve("f").toString()), "writable in first process");

    // Second "process": fresh registry, same store, reload on load().
    WorkspaceRoots second = build(true, def.toString(), 0, store, 2_000L);
    assertTrue(second.canWrite("s1", proj.resolve("f").toString()), "grant reloaded after restart");
    assertFalse(second.canWrite("other", proj.resolve("f").toString()), "still scoped to s1 after reload");
  }

  @Test
  void expiredGrantIsNotReloadedAndIsPruned() throws Exception {
    Path base = Files.createTempDirectory("persist-ttl");
    Path def = Files.createDirectories(base.resolve("def"));
    Path proj = Files.createDirectories(base.resolve("proj"));
    FakeGrantStore store = new FakeGrantStore();

    // A grant made long ago (granted_at = 1000), TTL = 10s. "Now" at reload = 1000 + 20_000 -> expired.
    store.rows.add(new GrantStore.GrantRow("s1", proj.toAbsolutePath().normalize().toString(), "read_write", 1_000L));

    WorkspaceRoots w = build(true, def.toString(), 10, store, 21_000L);
    assertFalse(w.canWrite("s1", proj.resolve("f").toString()), "expired grant is not honored after reload");
    assertTrue(store.rows.isEmpty(), "expired row was pruned from the store");
  }

  @Test
  void nonExpiredGrantWithinTtlIsReloaded() throws Exception {
    Path base = Files.createTempDirectory("persist-ttl2");
    Path def = Files.createDirectories(base.resolve("def"));
    Path proj = Files.createDirectories(base.resolve("proj"));
    FakeGrantStore store = new FakeGrantStore();
    store.rows.add(new GrantStore.GrantRow("s1", proj.toAbsolutePath().normalize().toString(), "read", 1_000L));

    // TTL 100s, now = 1000 + 50_000 -> still valid.
    WorkspaceRoots w = build(true, def.toString(), 100, store, 51_000L);
    assertTrue(w.canRead("s1", proj.resolve("f").toString()), "valid grant reloaded");
    assertFalse(w.canWrite("s1", proj.resolve("f").toString()), "read grant stays read-only after reload");
  }

  @Test
  void inMemoryGrantExpiresAtAccessTime() throws Exception {
    Path base = Files.createTempDirectory("persist-lazy");
    Path def = Files.createDirectories(base.resolve("def"));
    Path proj = Files.createDirectories(base.resolve("proj"));
    FakeGrantStore store = new FakeGrantStore();

    WorkspaceRoots w = build(true, def.toString(), 10, store, 1_000L); // ttl 10s, granted at t=1000
    w.add("s1", proj, WorkspaceRoots.Access.READ_WRITE);
    assertTrue(w.canWrite("s1", proj.resolve("f").toString()), "writable right after grant");

    set(w, "nowOverride", 1_000L + 11_000L); // advance past the TTL
    assertFalse(w.canWrite("s1", proj.resolve("f").toString()), "grant ignored once TTL passes");
  }

  @Test
  void revokeRemovesThePersistedRow() throws Exception {
    Path base = Files.createTempDirectory("persist-revoke");
    Path def = Files.createDirectories(base.resolve("def"));
    Path proj = Files.createDirectories(base.resolve("proj"));
    FakeGrantStore store = new FakeGrantStore();

    WorkspaceRoots w = build(true, def.toString(), 0, store, 1_000L);
    w.add("s1", proj, WorkspaceRoots.Access.READ_WRITE);
    assertEquals(1, store.rows.size(), "one persisted grant");
    assertTrue(w.remove("s1", proj), "revoke succeeds");
    assertTrue(store.rows.isEmpty(), "persisted row removed on revoke");
    assertTrue(store.deletes >= 1, "delete was called");
  }

  @Test
  void disabledModeNeverTouchesTheStore() throws Exception {
    Path base = Files.createTempDirectory("persist-off");
    Path def = Files.createDirectories(base.resolve("def"));
    Path proj = Files.createDirectories(base.resolve("proj"));
    FakeGrantStore store = new FakeGrantStore();
    // A row exists in the store, but multi-root is disabled: load() must not read it.
    store.rows.add(new GrantStore.GrantRow("s1", proj.toString(), "read_write", 1_000L));

    WorkspaceRoots w = build(false, def.toString(), 0, store, 2_000L);
    assertFalse(w.canWrite("s1", proj.resolve("f").toString()), "disabled: persisted grant ignored");
    assertEquals(0, store.saves, "disabled: no saves");
    assertEquals(0, store.deletes, "disabled: no deletes");
    // add() is a no-op when disabled -> still no writes to the store.
    assertEquals(null, w.add("s1", proj, WorkspaceRoots.Access.READ_WRITE));
    assertEquals(0, store.saves, "disabled: add did not persist");
  }
}
