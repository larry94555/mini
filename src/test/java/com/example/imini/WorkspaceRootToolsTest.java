package com.example.imini;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Track B PR #2 — {@code grant_workspace_root}/{@code revoke_workspace_root} tools and the always-confirm
 * routing in {@link PermissionService#decide}. Fully offline: {@code @Value} fields are set by reflection,
 * and {@code System.in} is pointed at EOF <em>before</em> constructing {@link PermissionService} so the
 * console approval path returns a (non-ALLOW) DENY instead of blocking on input.
 *
 * <p>The central guarantee: granting/revoking a root is <strong>never</strong> auto-approved (not in
 * {@code auto} mode, not via {@code autoApprove}), while ordinary mutating tools still auto-approve in
 * {@code auto} — so existing golden traces (which rely on auto-approving {@code write_marker},
 * {@code git_commit}, …) are unaffected.
 */
public class WorkspaceRootToolsTest {

  private static void set(Object o, String field, Object value) throws Exception {
    Field f = o.getClass().getDeclaredField(field);
    f.setAccessible(true);
    f.set(o, value);
  }

  private static PermissionService permissions() throws Exception {
    System.setIn(new ByteArrayInputStream(new byte[0])); // EOF -> promptConsole returns DENY, never blocks
    PermissionService ps = new PermissionService(new Approvals(), null, null);
    set(ps, "root", Files.createTempDirectory("perm").toAbsolutePath().normalize());
    set(ps, "confine", true);
    set(ps, "promptMode", "console");
    return ps;
  }

  private static WorkspaceRoots roots(boolean enabled, String def) throws Exception {
    WorkspaceRoots w = new WorkspaceRoots();
    set(w, "workspaceRootCfg", def);
    set(w, "enabled", enabled);
    set(w, "rootsCfg", "");
    Method load = WorkspaceRoots.class.getDeclaredMethod("load");
    load.setAccessible(true);
    load.invoke(w);
    return w;
  }

  @Test
  void grantIsNeverAutoApprovedButOrdinaryToolsAre() throws Exception {
    PermissionService ps = permissions();

    // Ordinary mutating tools still auto-approve in AUTO (golden traces depend on this).
    assertEquals(PermissionService.Kind.ALLOW,
        ps.decide("s", "write_marker", true, Map.of(), PermissionService.Mode.AUTO).kind(),
        "AUTO: ordinary tool auto-approved");
    assertEquals(PermissionService.Kind.ALLOW,
        ps.decide("s", "git_commit", true, Map.of("message", "x"), PermissionService.Mode.AUTO).kind(),
        "AUTO: git_commit auto-approved");

    // grant/revoke are NEVER auto-approved: they route to the approval path (DENY here, given EOF).
    assertFalse(
        ps.decide("s", "grant_workspace_root", true, Map.of("path", "/x", "access", "read"),
            PermissionService.Mode.AUTO).kind() == PermissionService.Kind.ALLOW,
        "AUTO: grant_workspace_root NOT auto-approved");
    assertFalse(
        ps.decide("s", "revoke_workspace_root", true, Map.of("path", "/x"),
            PermissionService.Mode.AUTO).kind() == PermissionService.Kind.ALLOW,
        "AUTO: revoke_workspace_root NOT auto-approved");
  }

  @Test
  void grantRecordsInPlanModeAndIgnoresAutoApproveFlag() throws Exception {
    PermissionService ps = permissions();

    assertEquals(PermissionService.Kind.RECORD_PLAN,
        ps.decide("s", "grant_workspace_root", true, Map.of("path", "/x", "access", "read"),
            PermissionService.Mode.PLAN).kind(),
        "PLAN: grant records a plan");

    // Even with the global autoApprove flag on, grant is still not auto-approved; ordinary tool is.
    set(ps, "autoApprove", true);
    assertFalse(
        ps.decide("s", "grant_workspace_root", true, Map.of("path", "/x", "access", "read"),
            PermissionService.Mode.ASK).kind() == PermissionService.Kind.ALLOW,
        "autoApprove: grant still NOT auto-approved");
    assertEquals(PermissionService.Kind.ALLOW,
        ps.decide("s", "write_marker", true, Map.of(), PermissionService.Mode.ASK).kind(),
        "autoApprove: ordinary tool auto-approved");
  }

  @Test
  void grantAndRevokeExecutorsHonorAccessAndAudit() throws Exception {
    Path def = Files.createTempDirectory("def").toAbsolutePath().normalize();
    Path src = Files.createTempDirectory("src").toAbsolutePath().normalize();
    AuditLog audit = new AuditLog(new Database());
    WorkspaceRoots wr = roots(true, def.toString());
    WorkspaceRootTools tools = new WorkspaceRootTools(wr, audit);

    Tool grant = tools.grantTool();
    Tool revoke = tools.revokeTool();
    assertTrue(grant.mutating, "grant is mutating");
    assertTrue(revoke.mutating, "revoke is mutating");

    String g = grant.executor.apply(Map.of("path", src.toString(), "access", "read"));
    assertTrue(g.contains("Granted") && g.contains("READ"), "grant READ message: " + g);
    assertTrue(wr.canRead(src.resolve("f").toString()), "after grant: read allowed");
    assertFalse(wr.canWrite(src.resolve("f").toString()), "after grant: write denied (READ access)");

    String r = revoke.executor.apply(Map.of("path", src.toString()));
    assertTrue(r.contains("Revoked"), "revoke message: " + r);
    assertFalse(wr.canRead(src.resolve("f").toString()), "after revoke: read denied");

    // Relative path is rejected; absolute required.
    String rel = grant.executor.apply(Map.of("path", "relative/dir", "access", "read"));
    assertTrue(rel.contains("ABSOLUTE"), "relative path rejected: " + rel);
  }

  @Test
  void grantReportsWhenMultiRootDisabled() throws Exception {
    Path def = Files.createTempDirectory("def-off").toAbsolutePath().normalize();
    Path src = Files.createTempDirectory("src-off").toAbsolutePath().normalize();
    WorkspaceRoots wr = roots(false, def.toString()); // disabled
    WorkspaceRootTools tools = new WorkspaceRootTools(wr, new AuditLog(new Database()));

    String g = tools.grantTool().executor.apply(Map.of("path", src.toString(), "access", "read_write"));
    assertNotNull(g);
    assertTrue(g.toLowerCase().contains("disabled"), "disabled: grant reports it: " + g);
    assertFalse(wr.canRead(src.resolve("f").toString()), "disabled: nothing granted");
  }
}
