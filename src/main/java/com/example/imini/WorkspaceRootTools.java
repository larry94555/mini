package com.example.imini;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Track B PR #2 — the agent-facing tools that grant and revoke additional {@link WorkspaceRoots} entries.
 *
 * <p>Both tools are {@code mutating=true}, so they pass through {@link PermissionService}. They are also in
 * {@code PermissionService.ALWAYS_CONFIRM}, which means they are <strong>never auto-approved</strong> — even
 * in {@code auto} mode they route to the human approval path (in {@code plan} mode they record as a plan).
 * Granting a new root is a trust decision, so it always asks.
 *
 * <p>Grants are honored only when {@code agent.multi-root.enabled=true}; with multi-root off the tools report
 * that clearly and change nothing. Every grant/revoke is written to the {@link AuditLog}.
 *
 * <p><strong>Scope note:</strong> grants currently apply to the process-wide registry (one {@link
 * WorkspaceRoots} bean), not per-session — per-session isolation is future work. The default root can never
 * be revoked or downgraded.
 */
@Component
public class WorkspaceRootTools {

  private final WorkspaceRoots workspaceRoots;
  private final AuditLog audit;

  public WorkspaceRootTools(WorkspaceRoots workspaceRoots, AuditLog audit) {
    this.workspaceRoots = workspaceRoots;
    this.audit = audit;
  }

  public List<Tool> all() {
    return List.of(grantTool(), revokeTool());
  }

  public Tool grantTool() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("path", strProp("Absolute path of the directory to grant the agent access to."));
    props.put(
        "access",
        strProp("Access level: 'read' (read-only) or 'read_write' (read and write). Defaults to 'read'."));
    return new Tool(
        "grant_workspace_root",
        "Grant the agent access to an additional workspace root at an absolute path, with 'read' or "
            + "'read_write' access. Mutating and ALWAYS requires explicit approval (never auto-approved). "
            + "Needed before the agent can read a second project or write a new one outside the default "
            + "workspace. No effect unless multi-root is enabled.",
        schema(props, "path"),
        true,
        args -> {
          String path = str(args.get("path"));
          String access = str(args.get("access"));
          if (path == null || path.isBlank()) {
            return "grant_workspace_root requires an absolute 'path'.";
          }
          Path p;
          try {
            p = Path.of(path);
          } catch (Exception e) {
            return "grant_workspace_root: not a valid path: '" + path + "'.";
          }
          if (!p.isAbsolute()) {
            return "grant_workspace_root requires an ABSOLUTE path; got relative '" + path + "'.";
          }
          WorkspaceRoots.Access acc = WorkspaceRoots.parseAccess(access);
          if (!workspaceRoots.enabled()) {
            audit.record("agent", "grant_workspace_root", p + " [" + acc + "]", "denied: multi-root disabled");
            return "Multi-root is disabled (agent.multi-root.enabled=false); cannot grant '" + p + "'. "
                + "Enable multi-root first.";
          }
          WorkspaceRoots.Root r = workspaceRoots.add(p, acc);
          if (r == null) {
            audit.record("agent", "grant_workspace_root", p + " [" + acc + "]", "failed");
            return "Could not grant '" + p + "'.";
          }
          audit.record("agent", "grant_workspace_root", r.path() + " [" + r.access() + "]", "granted " + r.id());
          return "Granted " + r.access() + " access to " + r.path() + " (id=" + r.id() + ").";
        });
  }

  public Tool revokeTool() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("path", strProp("Absolute path of a previously granted root to revoke. The default root cannot be revoked."));
    return new Tool(
        "revoke_workspace_root",
        "Revoke a previously granted workspace root by absolute path. The default workspace root cannot be "
            + "revoked. Mutating and ALWAYS requires explicit approval. No effect unless multi-root is enabled.",
        schema(props, "path"),
        true,
        args -> {
          String path = str(args.get("path"));
          if (path == null || path.isBlank()) {
            return "revoke_workspace_root requires a 'path'.";
          }
          if (!workspaceRoots.enabled()) {
            return "Multi-root is disabled; there is nothing to revoke.";
          }
          Path p;
          try {
            p = Path.of(path);
          } catch (Exception e) {
            return "revoke_workspace_root: not a valid path: '" + path + "'.";
          }
          boolean removed = workspaceRoots.remove(p);
          audit.record("agent", "revoke_workspace_root", String.valueOf(p), removed ? "revoked" : "no-match");
          return removed
              ? "Revoked access to " + p.toAbsolutePath().normalize() + "."
              : "No removable root matched '" + p + "' (the default root cannot be revoked).";
        });
  }

  // --- small schema helpers (mirror GitWriteTools' style) ---

  private static String str(Object o) {
    return o == null ? null : String.valueOf(o);
  }

  private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("type", "object");
    s.put("properties", properties);
    if (required.length > 0) {
      s.put("required", List.of(required));
    }
    return s;
  }

  private static Map<String, Object> strProp(String description) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("type", "string");
    p.put("description", description);
    return p;
  }
}
