package com.example.imini;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Track B PR #1 — the registry of workspace roots the harness is allowed to read and/or write.
 *
 * <p>Replaces the historical single {@code agent.workspace-root} with a set of roots, each carrying an id,
 * an absolute+normalized path, and an {@link Access} level. The <strong>default</strong> root (the current
 * working directory, or {@code agent.workspace-root} if set) is always present and {@code READ_WRITE}.
 *
 * <p><strong>Default-closed.</strong> Multi-root is off unless {@code agent.multi-root.enabled=true}. When
 * off, the registry holds exactly the one default {@code READ_WRITE} root, so {@link #canRead}/{@link
 * #canWrite} reduce to {@code isWithin(defaultRoot, ...)} — i.e. behavior is byte-for-byte what it was
 * before this registry existed. Additional roots (config-seeded here, or granted at runtime in a later PR)
 * are honored only when multi-root is enabled.
 *
 * <p>This class holds no Sandbox/Permission logic; {@link Sandbox}, {@link PermissionService}, and
 * {@link RetrievalService} consult it. Path containment uses {@link PermissionService#isWithin} so semantics
 * match the rest of the harness on every platform (the JVM's {@code Path} handles Windows drive letters and
 * case-insensitivity vs POSIX case-sensitivity).
 */
@Component
public class WorkspaceRoots {
  private static final Logger log = LoggerFactory.getLogger(WorkspaceRoots.class);

  public enum Access {
    READ,
    READ_WRITE
  }

  /** A registered root: stable id, absolute normalized path, and access level. */
  public record Root(String id, Path path, Access access) {
    public boolean writable() {
      return access == Access.READ_WRITE;
    }
  }

  @Value("${agent.workspace-root:}")
  private String workspaceRootCfg;

  @Value("${agent.multi-root.enabled:false}")
  private boolean enabled;

  /**
   * Optional static seeds, applied only when multi-root is enabled. CSV of {@code path|access} entries,
   * e.g. {@code /srv/readonly|read, /srv/out|read_write}. Runtime grants (a later PR) use {@link #add}.
   */
  @Value("${agent.multi-root.roots:}")
  private String rootsCfg;

  private Path defaultRoot;
  private Root defaultEntry;
  /** Additional roots granted at runtime, keyed by session id. The default root is global (not in here). */
  private final java.util.Map<String, List<Root>> sessionRoots = new java.util.HashMap<>();
  private int seq;

  private static final String DEFAULT_SESSION = "default";

  private static String sid(String sessionId) {
    return (sessionId == null || sessionId.isBlank()) ? DEFAULT_SESSION : sessionId;
  }

  @PostConstruct
  public synchronized void load() {
    defaultRoot =
        (workspaceRootCfg == null || workspaceRootCfg.isBlank()
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(workspaceRootCfg))
            .toAbsolutePath()
            .normalize();

    sessionRoots.clear();
    seq = 0;
    defaultEntry = new Root("default", defaultRoot, Access.READ_WRITE);

    // Static seeds (only when enabled) populate the DEFAULT session; runtime grants are per-session.
    if (enabled && rootsCfg != null && !rootsCfg.isBlank()) {
      for (String entry : rootsCfg.split(",")) {
        String e = entry.trim();
        if (e.isEmpty()) {
          continue;
        }
        int bar = e.lastIndexOf('|');
        String pathPart = bar >= 0 ? e.substring(0, bar).trim() : e;
        String accPart = bar >= 0 ? e.substring(bar + 1).trim() : "read";
        if (!pathPart.isEmpty()) {
          add(DEFAULT_SESSION, Path.of(pathPart), parseAccess(accPart));
        }
      }
    }

    log.info("[workspace-roots] multi-root=" + enabled + "; default=" + defaultRoot);
  }

  static Access parseAccess(String token) {
    if (token == null) {
      return Access.READ;
    }
    switch (token.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "rw":
      case "read_write":
      case "read-write":
      case "readwrite":
        return Access.READ_WRITE;
      default:
        return Access.READ;
    }
  }

  /** The primary/default root (current dir or {@code agent.workspace-root}); always {@code READ_WRITE}. */
  public synchronized Path defaultRoot() {
    return defaultRoot;
  }

  public synchronized boolean enabled() {
    return enabled;
  }

  // --- session-aware API (the canonical methods) -----------------------

  /**
   * Register (or replace) an additional root <strong>for one session</strong>. No-op unless multi-root is
   * enabled. The default root cannot be replaced or downgraded. A root granted in one session is invisible
   * to other sessions, so one run cannot widen another's access.
   */
  public synchronized Root add(String sessionId, Path path, Access access) {
    if (!enabled) {
      log.warn("[workspace-roots] ignoring add(" + path + "): multi-root is disabled");
      return null;
    }
    Path norm = path.toAbsolutePath().normalize();
    if (norm.equals(defaultRoot)) {
      return defaultEntry; // default stays READ_WRITE; never downgraded
    }
    List<Root> list = sessionRoots.computeIfAbsent(sid(sessionId), k -> new ArrayList<>());
    list.removeIf(r -> r.path().equals(norm));
    Root r = new Root("r" + (++seq), norm, access);
    list.add(r);
    log.info("[workspace-roots] session " + sid(sessionId) + " added root " + r.id() + " " + r.access() + " " + norm);
    return r;
  }

  /** Remove an additional root by path from one session. The default root cannot be removed. */
  public synchronized boolean remove(String sessionId, Path path) {
    Path norm = path.toAbsolutePath().normalize();
    if (norm.equals(defaultRoot)) {
      return false;
    }
    List<Root> list = sessionRoots.get(sid(sessionId));
    return list != null && list.removeIf(r -> r.path().equals(norm));
  }

  /** True if {@code candidate} resolves within the default root or any of this session's roots. */
  public synchronized boolean canRead(String sessionId, String candidate) {
    if (PermissionService.isWithin(defaultRoot, candidate)) {
      return true;
    }
    List<Root> list = sessionRoots.get(sid(sessionId));
    if (list != null) {
      for (Root r : list) {
        if (PermissionService.isWithin(r.path(), candidate)) {
          return true;
        }
      }
    }
    return false;
  }

  /** True if {@code candidate} resolves within the default root or any {@code READ_WRITE} root of this session. */
  public synchronized boolean canWrite(String sessionId, String candidate) {
    if (PermissionService.isWithin(defaultRoot, candidate)) {
      return true; // the default root is always READ_WRITE
    }
    List<Root> list = sessionRoots.get(sid(sessionId));
    if (list != null) {
      for (Root r : list) {
        if (r.writable() && PermissionService.isWithin(r.path(), candidate)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Snapshot of the roots visible to a session: the default root first, then that session's added roots. */
  public synchronized List<Root> roots(String sessionId) {
    List<Root> out = new ArrayList<>();
    out.add(defaultEntry);
    List<Root> list = sessionRoots.get(sid(sessionId));
    if (list != null) {
      out.addAll(list);
    }
    return List.copyOf(out);
  }

  /** Per-session additional grants (default root excluded), for admin visibility. */
  public synchronized java.util.Map<String, List<Root>> bySession() {
    java.util.Map<String, List<Root>> out = new java.util.LinkedHashMap<>();
    for (java.util.Map.Entry<String, List<Root>> e : sessionRoots.entrySet()) {
      if (!e.getValue().isEmpty()) {
        out.put(e.getKey(), List.copyOf(e.getValue()));
      }
    }
    return out;
  }

  // --- legacy overloads: resolve the session from SessionContext -------
  // Existing callers (Sandbox, PermissionService, ProjectTools, the grant tool) call these no-session forms;
  // they run on the engine's tool-dispatch thread where SessionContext is set, so they are session-scoped
  // automatically. Outside a run (tests, admin), SessionContext.sessionId() is "default".

  public Root add(Path path, Access access) {
    return add(SessionContext.sessionId(), path, access);
  }

  public boolean remove(Path path) {
    return remove(SessionContext.sessionId(), path);
  }

  public boolean canRead(String candidate) {
    return canRead(SessionContext.sessionId(), candidate);
  }

  public boolean canWrite(String candidate) {
    return canWrite(SessionContext.sessionId(), candidate);
  }

  /** Roots visible to the current session (or the default session outside a run). */
  public List<Root> roots() {
    return roots(SessionContext.sessionId());
  }
}
