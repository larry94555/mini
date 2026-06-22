package com.example.imini;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Track B PR #1 — the {@link WorkspaceRoots} registry and its wiring into {@link Sandbox} and
 * {@link PermissionService}. Pure path logic, fully offline (no Spring, no model): {@code @Value} fields are
 * set by reflection and {@code load()}/{@code writesOutsideRoot} invoked directly.
 *
 * <p>The central guarantee is that with multi-root <em>disabled</em> the registry holds exactly one
 * {@code READ_WRITE} root (the default) and {@code canRead}/{@code canWrite} reduce to
 * {@code isWithin(defaultRoot, ...)} — byte-for-byte the pre-registry behavior — and that the optional-field
 * null-fallback in {@code Sandbox}/{@code PermissionService} matches the old single-root logic exactly.
 */
public class WorkspaceRootsTest {

  private static void set(Object o, String field, Object value) throws Exception {
    Field f = o.getClass().getDeclaredField(field);
    f.setAccessible(true);
    f.set(o, value);
  }

  private static WorkspaceRoots roots(boolean enabled, String defRoot, String seeds) throws Exception {
    WorkspaceRoots w = new WorkspaceRoots();
    set(w, "workspaceRootCfg", defRoot);
    set(w, "enabled", enabled);
    set(w, "rootsCfg", seeds == null ? "" : seeds);
    Method load = WorkspaceRoots.class.getDeclaredMethod("load");
    load.setAccessible(true);
    load.invoke(w);
    return w;
  }

  @Test
  void disabledIsByteIdenticalSingleRoot() throws Exception {
    Path base = Files.createTempDirectory("wr-disabled");
    Path def = Files.createDirectories(base.resolve("default"));
    Path other = Files.createDirectories(base.resolve("other"));

    // Seeds are provided but must be IGNORED while disabled.
    WorkspaceRoots w = roots(false, def.toString(), other.toString() + "|read");

    assertEquals(1, w.roots().size(), "disabled: exactly one root");
    assertEquals(WorkspaceRoots.Access.READ_WRITE, w.roots().get(0).access(), "disabled: default is READ_WRITE");
    assertEquals(def.toAbsolutePath().normalize(), w.defaultRoot(), "disabled: default path");
    assertTrue(w.canRead(def.resolve("f.txt").toString()), "disabled: read inside default");
    assertTrue(w.canWrite(def.resolve("f.txt").toString()), "disabled: write inside default");
    assertFalse(w.canRead(other.resolve("f.txt").toString()), "disabled: read outside default denied");
    assertFalse(w.canWrite(other.resolve("f.txt").toString()), "disabled: write outside default denied");

    // Reduces exactly to isWithin(defaultRoot, ...).
    String inside = def.resolve("a/b.txt").toString();
    String outside = other.resolve("a/b.txt").toString();
    assertEquals(PermissionService.isWithin(def, inside), w.canWrite(inside), "canWrite==isWithin inside");
    assertEquals(PermissionService.isWithin(def, outside), w.canWrite(outside), "canWrite==isWithin outside");

    // add() is a no-op while disabled (cannot widen access when the feature is off).
    assertNull(w.add(other, WorkspaceRoots.Access.READ_WRITE), "disabled: add() no-op");
    assertEquals(1, w.roots().size(), "disabled: still one root after add()");
  }

  @Test
  void enabledReadRootPermitsReadNotWrite() throws Exception {
    Path base = Files.createTempDirectory("wr-enabled");
    Path def = Files.createDirectories(base.resolve("default"));
    Path src = Files.createDirectories(base.resolve("source"));
    Path out = Files.createDirectories(base.resolve("out"));

    WorkspaceRoots w = roots(true, def.toString(), src.toString() + "|read, " + out.toString() + "|read_write");
    assertEquals(3, w.roots().size(), "enabled: default + 2 seeded roots");

    // READ root: reads allowed, writes denied.
    assertTrue(w.canRead(src.resolve("Foo.java").toString()), "READ root: read allowed");
    assertFalse(w.canWrite(src.resolve("Foo.java").toString()), "READ root: write denied");

    // READ_WRITE root and default: both allowed.
    assertTrue(w.canWrite(out.resolve("Bar.ts").toString()), "RW root: write allowed");
    assertTrue(w.canWrite(def.resolve("Baz.java").toString()), "default RW: write allowed");

    // Outside every root: both denied.
    Path elsewhere = base.resolve("elsewhere");
    assertFalse(w.canRead(elsewhere.resolve("x").toString()), "outside all: read denied");
    assertFalse(w.canWrite(elsewhere.resolve("x").toString()), "outside all: write denied");

    // remove(): default protected, added removable.
    assertFalse(w.remove(def), "remove(default) refused");
    assertTrue(w.remove(out), "remove(added) works");
    assertFalse(w.canWrite(out.resolve("x").toString()), "after remove: write denied");
  }

  @Test
  void sandboxNullFallbackMatchesSingleRoot() throws Exception {
    Path base = Files.createTempDirectory("wr-sandbox");
    Path def = Files.createDirectories(base.resolve("default"));
    Path other = Files.createDirectories(base.resolve("other"));

    Sandbox sb = new Sandbox(); // workspaceRoots left null -> single-root fallback
    set(sb, "root", def.toAbsolutePath().normalize());
    set(sb, "confineWrites", true);
    set(sb, "confineReads", true);

    assertNull(sb.enforcePath("write_file", def.resolve("ok.txt").toString(), true), "fallback: write inside OK");
    assertNotNull(sb.enforcePath("write_file", other.resolve("no.txt").toString(), true), "fallback: write outside denied");
  }

  @Test
  void sandboxWithRegistryHonorsAccessLevels() throws Exception {
    Path base = Files.createTempDirectory("wr-sandbox-reg");
    Path def = Files.createDirectories(base.resolve("default"));
    Path src = Files.createDirectories(base.resolve("source"));
    Path out = Files.createDirectories(base.resolve("out"));
    WorkspaceRoots w = roots(true, def.toString(), src.toString() + "|read, " + out.toString() + "|read_write");

    Sandbox sb = new Sandbox();
    set(sb, "root", def.toAbsolutePath().normalize());
    set(sb, "confineWrites", true);
    set(sb, "confineReads", true);
    set(sb, "workspaceRoots", w);

    assertNull(sb.enforcePath("read_file", src.resolve("a.java").toString(), false), "reg: read under READ root OK");
    assertNotNull(sb.enforcePath("write_file", src.resolve("a.java").toString(), true), "reg: write under READ root denied");
    assertNull(sb.enforcePath("write_file", out.resolve("a.ts").toString(), true), "reg: write under RW root OK");
  }

  @Test
  @SuppressWarnings("unchecked")
  void permissionWritesOutsideGrantedRoots() throws Exception {
    Path base = Files.createTempDirectory("wr-perm");
    Path def = Files.createDirectories(base.resolve("default"));
    Path src = Files.createDirectories(base.resolve("source"));
    Path out = Files.createDirectories(base.resolve("out"));

    PermissionService ps = new PermissionService(new Approvals(), null, null);
    set(ps, "root", def.toAbsolutePath().normalize());
    set(ps, "confine", true);
    Method wor = PermissionService.class.getDeclaredMethod("writesOutsideRoot", String.class, Map.class);
    wor.setAccessible(true);

    // Null fallback: outside the single default root => outside.
    assertTrue((boolean) wor.invoke(ps, "write_file", Map.of("path", src.resolve("x").toString())),
        "fallback: write outside default is outsideRoot");
    assertFalse((boolean) wor.invoke(ps, "write_file", Map.of("path", def.resolve("x").toString())),
        "fallback: write inside default is not outsideRoot");

    // Registry: only outside when not within any READ_WRITE root. Inside a granted RW root still proceeds
    // to the normal approval path (writesOutsideRoot == false), it is NOT auto-allowed by the registry.
    WorkspaceRoots w = roots(true, def.toString(), src.toString() + "|read, " + out.toString() + "|read_write");
    set(ps, "workspaceRoots", w);
    assertTrue((boolean) wor.invoke(ps, "write_file", Map.of("path", src.resolve("x").toString())),
        "reg: write under READ root is outsideGrantedRoots");
    assertFalse((boolean) wor.invoke(ps, "write_file", Map.of("path", out.resolve("x").toString())),
        "reg: write under RW root is inside (proceeds to approval)");
  }

  @Test
  void accessTokenParsing() {
    assertEquals(WorkspaceRoots.Access.READ_WRITE, WorkspaceRoots.parseAccess("read_write"));
    assertEquals(WorkspaceRoots.Access.READ_WRITE, WorkspaceRoots.parseAccess("RW"));
    assertEquals(WorkspaceRoots.Access.READ_WRITE, WorkspaceRoots.parseAccess("read-write"));
    assertEquals(WorkspaceRoots.Access.READ, WorkspaceRoots.parseAccess("read"));
    assertEquals(WorkspaceRoots.Access.READ, WorkspaceRoots.parseAccess("bogus"));
    assertEquals(WorkspaceRoots.Access.READ, WorkspaceRoots.parseAccess(null));
  }
}
