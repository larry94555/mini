package com.example.imini;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Track B PR #3 — {@code create_project}. Fully offline: a real {@link WorkspaceRoots} (with a granted
 * read_write root) and a real {@link AuditLog}, exercising the tool executor and the pure transactional
 * helper directly. No model, no Spring.
 */
public class ProjectToolsTest {

  private static void set(Object o, String field, Object value) throws Exception {
    Field f = o.getClass().getDeclaredField(field);
    f.setAccessible(true);
    f.set(o, value);
  }

  private static WorkspaceRoots roots(boolean enabled, String def, String seeds) throws Exception {
    WorkspaceRoots w = new WorkspaceRoots();
    set(w, "workspaceRootCfg", def);
    set(w, "enabled", enabled);
    set(w, "rootsCfg", seeds == null ? "" : seeds);
    Method load = WorkspaceRoots.class.getDeclaredMethod("load");
    load.setAccessible(true);
    load.invoke(w);
    return w;
  }

  private static List<Map<String, Object>> manifest(String... pathContent) {
    List<Map<String, Object>> files = new ArrayList<>();
    for (int i = 0; i < pathContent.length; i += 2) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("path", pathContent[i]);
      m.put("content", pathContent[i + 1]);
      files.add(m);
    }
    return files;
  }

  @Test
  void planOnlyWritesNothing() throws Exception {
    Path def = Files.createTempDirectory("def").toAbsolutePath().normalize();
    Path out = Files.createTempDirectory("out").toAbsolutePath().normalize();
    WorkspaceRoots wr = roots(true, def.toString(), out.toString() + "|read_write");
    ProjectTools tools = new ProjectTools(wr, new AuditLog(new Database()));

    Map<String, Object> args = new LinkedHashMap<>();
    args.put("root", out.toString());
    args.put("files", manifest("package.json", "{}", "src/index.ts", "export {}"));
    args.put("plan_only", true);

    String res = tools.execute(args);
    assertTrue(res.startsWith("[plan]"), "plan-only returns a plan: " + res);
    assertTrue(res.contains("files: 2"), "plan lists file count");
    assertTrue(res.contains("src/index.ts"), "plan lists the tree");
    assertFalse(Files.exists(out.resolve("package.json")), "plan-only wrote nothing");
    assertFalse(Files.exists(out.resolve("src/index.ts")), "plan-only wrote nothing (nested)");
  }

  @Test
  void successfulTransactionalScaffold() throws Exception {
    Path def = Files.createTempDirectory("def").toAbsolutePath().normalize();
    Path out = Files.createTempDirectory("out").toAbsolutePath().normalize();
    WorkspaceRoots wr = roots(true, def.toString(), out.toString() + "|read_write");
    ProjectTools tools = new ProjectTools(wr, new AuditLog(new Database()));

    Map<String, Object> args = new LinkedHashMap<>();
    args.put("root", out.toString());
    args.put("files", manifest(
        "package.json", "{\"name\":\"x\"}",
        "tsconfig.json", "{}",
        "src/index.ts", "export const x = 1;"));

    String res = tools.execute(args);
    assertTrue(res.startsWith("Created 3 file(s)"), "reports creation: " + res);
    assertEquals("{\"name\":\"x\"}", Files.readString(out.resolve("package.json")));
    assertEquals("{}", Files.readString(out.resolve("tsconfig.json")));
    assertEquals("export const x = 1;", Files.readString(out.resolve("src/index.ts")));
    // refuses to overwrite without overwrite=true
    String again = tools.execute(args);
    assertTrue(again.contains("already exists"), "refuses overwrite by default: " + again);
  }

  @Test
  void rollbackWhenAMoveFails() throws Exception {
    // Force a move-phase failure deterministically: the second file's parent ("sub") already exists as a
    // regular FILE, so createDirectories("sub") throws after the first file ("a.txt") was already placed.
    // The earlier file must be rolled back.
    Path out = Files.createTempDirectory("out").toAbsolutePath().normalize();
    Path root = out;
    Files.writeString(root.resolve("sub"), "i am a file, not a dir");

    Path good = root.resolve("a.txt").normalize();
    Path blocked = root.resolve("sub/b.txt").normalize();

    List<ProjectTools.Entry> entries = List.of(
        new ProjectTools.Entry("a.txt", "AAA"),
        new ProjectTools.Entry("sub/b.txt", "BBB"));
    List<Path> targets = List.of(good, blocked);

    boolean threw = false;
    try {
      ProjectTools.writeTransactionally(root, entries, targets);
    } catch (IOException expected) {
      threw = true;
    }
    assertTrue(threw, "creating a dir where a file exists should fail the move phase");
    assertFalse(Files.exists(good), "the earlier file must be rolled back, not left behind");
  }

  @Test
  void deniedWhenDestinationOutsideGrantedReadWriteRoot() throws Exception {
    Path def = Files.createTempDirectory("def").toAbsolutePath().normalize();
    Path readOnly = Files.createTempDirectory("ro").toAbsolutePath().normalize();
    Path ungranted = Files.createTempDirectory("ungranted").toAbsolutePath().normalize();
    // readOnly granted as READ (not writable); ungranted not in the registry at all.
    WorkspaceRoots wr = roots(true, def.toString(), readOnly.toString() + "|read");
    ProjectTools tools = new ProjectTools(wr, new AuditLog(new Database()));

    Map<String, Object> intoReadOnly = new LinkedHashMap<>();
    intoReadOnly.put("root", readOnly.toString());
    intoReadOnly.put("files", manifest("x.ts", "1"));
    String r1 = tools.execute(intoReadOnly);
    assertTrue(r1.startsWith("DENIED"), "write into a READ root is denied: " + r1);
    assertFalse(Files.exists(readOnly.resolve("x.ts")), "nothing written to READ root");

    Map<String, Object> intoUngranted = new LinkedHashMap<>();
    intoUngranted.put("root", ungranted.toString());
    intoUngranted.put("files", manifest("x.ts", "1"));
    String r2 = tools.execute(intoUngranted);
    assertTrue(r2.startsWith("DENIED"), "write into an ungranted root is denied: " + r2);

    // Default root IS read_write, so writing under it succeeds even with multi-root enabled.
    Map<String, Object> intoDefault = new LinkedHashMap<>();
    intoDefault.put("root", def.toString());
    intoDefault.put("files", manifest("ok.ts", "1"));
    String r3 = tools.execute(intoDefault);
    assertTrue(r3.startsWith("Created"), "write under the default RW root succeeds: " + r3);
  }

  @Test
  void pathEscapeIsRejected() throws Exception {
    Path def = Files.createTempDirectory("def").toAbsolutePath().normalize();
    Path out = Files.createTempDirectory("out").toAbsolutePath().normalize();
    WorkspaceRoots wr = roots(true, def.toString(), out.toString() + "|read_write");
    ProjectTools tools = new ProjectTools(wr, new AuditLog(new Database()));

    Map<String, Object> args = new LinkedHashMap<>();
    args.put("root", out.toString());
    args.put("files", manifest("../escape.txt", "nope"));
    String res = tools.execute(args);
    assertTrue(res.contains("escapes") || res.contains("relative"), "path escape rejected: " + res);
  }

  @Test
  void manifestParsingAndSummary() {
    List<Map<String, Object>> files = manifest("a", "12345", "b/c", "x");
    List<ProjectTools.Entry> entries = ProjectTools.parseManifest(files);
    assertEquals(2, entries.size());
    assertEquals(5, entries.get(0).bytes());

    Map<String, Object> args = new LinkedHashMap<>();
    args.put("root", "/dest");
    args.put("files", files);
    Map<String, Object> sum = ProjectTools.summarize(args);
    assertEquals(2, sum.get("fileCount"));
    assertEquals(6, sum.get("totalBytes"));
    assertTrue(sum.get("tree") instanceof List, "tree is a list");
  }
}
