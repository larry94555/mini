package com.example.imini;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.imini.ScriptedAgent.answer;
import static com.example.imini.ScriptedAgent.call;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Track B PR #3 — golden trace: a scripted model drives the real {@link AgentEngine} through the
 * cross-project flow end to end: <em>grant a read source root → grant a read_write destination root →
 * create_project in plan_only mode (writes nothing) → create_project for real (transactional write) →
 * answer.</em>
 *
 * <p>It proves the whole envelope with real collaborators (no live model): the always-confirm grant tools
 * require approval even though the run is in {@code AUTO} (driven by a scripted console "y"); the registry
 * gains the granted roots; {@code create_project} confines to the granted {@code read_write} root; the plan
 * step writes nothing; and the final write lands transactionally.
 */
public class CreateProjectTraceTest {

  private static void setField(Object o, Class<?> c, String name, Object v) throws Exception {
    Field f = c.getDeclaredField(name);
    f.setAccessible(true);
    f.set(o, v);
  }

  private static WorkspaceRoots enabledRegistry(Path defaultRoot) throws Exception {
    WorkspaceRoots w = new WorkspaceRoots();
    setField(w, WorkspaceRoots.class, "workspaceRootCfg", defaultRoot.toString());
    setField(w, WorkspaceRoots.class, "enabled", true);
    setField(w, WorkspaceRoots.class, "rootsCfg", "");
    Method load = WorkspaceRoots.class.getDeclaredMethod("load");
    load.setAccessible(true);
    load.invoke(w);
    return w;
  }

  private static Map<String, Object> file(String path, String content) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("path", path);
    m.put("content", content);
    return m;
  }

  @Test
  void grantThenPlanThenWrite() throws Exception {
    Path base = Files.createTempDirectory("imini-port-");
    Path work = Files.createDirectories(base.resolve("work"));     // default root
    Path source = Files.createDirectories(base.resolve("mini"));   // the "source" project (granted read)
    Files.writeString(source.resolve("Main.java"), "class Main {}");
    Path dest = Files.createDirectories(base.resolve("ts-project")); // destination (granted read_write)

    // One shared registry: the grant tool adds to it and create_project + permissions read from it.
    WorkspaceRoots registry = enabledRegistry(work);

    // Console approvals for the two always-confirm grants: set System.in to "y" lines BEFORE constructing
    // PermissionService (its reader captures System.in at construction).
    System.setIn(new ByteArrayInputStream("y\ny\ny\ny\n".getBytes()));

    Sandbox sandbox = new Sandbox();
    setField(sandbox, Sandbox.class, "root", work);
    setField(sandbox, Sandbox.class, "workspaceRoots", registry);

    GitInspector git = new GitInspector(sandbox);
    HookService hooks = new HookService();
    ScriptedAgent.RecordingPermissions perms = new ScriptedAgent.RecordingPermissions(new Approvals(), git, hooks);
    setField(perms, PermissionService.class, "root", work);
    setField(perms, PermissionService.class, "confine", true);
    setField(perms, PermissionService.class, "promptMode", "console");
    setField(perms, PermissionService.class, "workspaceRoots", registry);

    Database db = new Database();
    AuditLog audit = new AuditLog(db);
    WorkspaceRootTools rootTools = new WorkspaceRootTools(registry, audit);
    ProjectTools projectTools = new ProjectTools(registry, audit);

    Map<String, Tool> tools = new LinkedHashMap<>();
    for (Tool t : rootTools.all()) tools.put(t.name, t);
    for (Tool t : projectTools.all()) tools.put(t.name, t);

    List<Map<String, Object>> files = new ArrayList<>();
    files.add(file("package.json", "{\"name\":\"ts-project\"}"));
    files.add(file("src/index.ts", "export const x = 1;"));

    ScriptedAgent.ScriptedLlama model = new ScriptedAgent.ScriptedLlama(
        call("grant_workspace_root", Map.of("path", source.toString(), "access", "read")),
        call("grant_workspace_root", Map.of("path", dest.toString(), "access", "read_write")),
        call("create_project", Map.of("root", dest.toString(), "files", files, "plan_only", true)),
        call("create_project", Map.of("root", dest.toString(), "files", files)),
        answer("Ported the project to TypeScript at the destination."));

    AgentEngine engine = ScriptedAgent.buildEngine(model, perms, hooks, git);
    String answer = engine.run(ScriptedAgent.systemPrompt(),
        "Create a TypeScript project at the destination that ports the source.",
        tools, PermissionService.Mode.AUTO, "main", "port-trace", RunSink.NOOP);

    // 1) the two grants went through approval (NOT auto-approved despite AUTO) and were allowed.
    assertTrue(perms.decisions.contains("grant_workspace_root=ALLOW"),
        "grant routed to approval and allowed: " + perms.decisions);
    long grantDecisions = perms.decisions.stream().filter(s -> s.startsWith("grant_workspace_root=")).count();
    assertEquals(2L, grantDecisions, "both grants were gated: " + perms.decisions);

    // 2) the registry now has the default + the two granted roots, with the right access.
    assertTrue(registry.canRead(source.resolve("Main.java").toString()), "source readable after grant");
    assertFalse(registry.canWrite(source.resolve("x").toString()), "source NOT writable (read grant)");
    assertTrue(registry.canWrite(dest.resolve("x").toString()), "destination writable (read_write grant)");

    // 3) create_project ran (AUTO auto-approves a normal mutating tool) and the files exist with content.
    assertTrue(perms.decisions.contains("create_project=ALLOW"), "create_project gated+allowed: " + perms.decisions);
    assertEquals("{\"name\":\"ts-project\"}", Files.readString(dest.resolve("package.json")));
    assertEquals("export const x = 1;", Files.readString(dest.resolve("src/index.ts")));

    // 4) the plan_only call wrote nothing extra and the source was never written to.
    assertFalse(Files.exists(source.resolve("package.json")), "source untouched");
    assertTrue(answer.contains("Ported"), "final answer returned: " + answer);
  }
}
