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

  /**
   * Optional grant time-to-live, in seconds. {@code 0} (the default) means grants never expire. When &gt; 0,
   * a grant older than the TTL is ignored (not reloaded on startup, and not honored at access time) and
   * pruned from the store.
   */
  @Value("${agent.multi-root.grant-ttl:0}")
  private long grantTtlSeconds;

  /** Durable backing for grants. Optional: null in plain construction (tests) -> purely in-memory. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private GrantStore grants;

  /** Injectable clock for deterministic TTL tests; -1 means use the wall clock. */
  private volatile long nowOverride = -1L;

  private long now() {
    return nowOverride >= 0 ? nowOverride : System.currentTimeMillis();
  }

  private long ttlMillis() {
    return grantTtlSeconds > 0 ? grantTtlSeconds * 1000L : 0L;
  }

  private Path defaultRoot;
  private Root defaultEntry;
  /** Additional roots granted at runtime, keyed by session id. The default root is global (not in here). */
  private final java.util.Map<String, List<Root>> sessionRoots = new java.util.HashMap<>();
  /** When each session+path grant was made (epoch millis), for TTL and admin reporting. */
  private final java.util.Map<String, java.util.Map<String, Long>> grantedAtMs = new java.util.HashMap<>();
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
    grantedAtMs.clear();
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

    // Reload durable grants (only when multi-root is enabled — disabled mode never touches the table).
    if (enabled && grants != null) {
      reloadGrants();
    }

    log.info("[workspace-roots] multi-root=" + enabled + "; default=" + defaultRoot);
  }

  /** Reload non-expired persisted grants into the in-memory registry; prune expired rows. */
  private synchronized void reloadGrants() {
    long ttl = ttlMillis();
    long cutoff = now() - ttl;
    if (ttl > 0) {
      grants.deleteOlderThan(cutoff); // prune expired rows from the store
    }
    int loaded = 0;
    for (GrantStore.GrantRow row : grants.loadAll()) {
      if (ttl > 0 && row.grantedAt() < cutoff) {
        continue; // ignore expired (defensive; deleteOlderThan already removed it)
      }
      Path norm;
      try {
        norm = Path.of(row.path()).toAbsolutePath().normalize();
      } catch (RuntimeException e) {
        continue;
      }
      if (norm.equals(defaultRoot)) {
        continue; // default is global, never persisted/reloaded
      }
      addInMemory(sid(row.sessionId()), norm, parseAccess(row.access()), row.grantedAt());
      loaded++;
    }
    if (loaded > 0) {
      log.info("[workspace-roots] reloaded " + loaded + " durable grant(s)");
    }
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
    long ts = now();
    Root r = addInMemory(sid(sessionId), norm, access, ts);
    if (grants != null) {
      grants.save(sid(sessionId), norm.toString(), access == Access.READ_WRITE ? "read_write" : "read", ts);
    }
    log.info("[workspace-roots] session " + sid(sessionId) + " added root " + r.id() + " " + r.access() + " " + norm);
    return r;
  }

  /** Insert into the in-memory maps only (no persistence) — shared by {@link #add} and reload. */
  private synchronized Root addInMemory(String sessionId, Path norm, Access access, long grantedAt) {
    List<Root> list = sessionRoots.computeIfAbsent(sessionId, k -> new ArrayList<>());
    list.removeIf(r -> r.path().equals(norm));
    Root r = new Root("r" + (++seq), norm, access);
    list.add(r);
    grantedAtMs.computeIfAbsent(sessionId, k -> new java.util.HashMap<>()).put(norm.toString(), grantedAt);
    return r;
  }

  /** Remove an additional root by path from one session. The default root cannot be removed. */
  public synchronized boolean remove(String sessionId, Path path) {
    Path norm = path.toAbsolutePath().normalize();
    if (norm.equals(defaultRoot)) {
      return false;
    }
    List<Root> list = sessionRoots.get(sid(sessionId));
    boolean removed = list != null && list.removeIf(r -> r.path().equals(norm));
    java.util.Map<String, Long> when = grantedAtMs.get(sid(sessionId));
    if (when != null) {
      when.remove(norm.toString());
    }
    if (removed && grants != null) {
      grants.delete(sid(sessionId), norm.toString());
    }
    return removed;
  }

  /** True if the grant for this session+path has aged past the TTL (TTL=0 means never). */
  private boolean expired(String sessionId, Path path) {
    long ttl = ttlMillis();
    if (ttl <= 0) {
      return false;
    }
    java.util.Map<String, Long> when = grantedAtMs.get(sessionId);
    Long ts = when == null ? null : when.get(path.toString());
    return ts != null && now() - ts > ttl;
  }

  /** True if {@code candidate} resolves within the default root or any of this session's (non-expired) roots. */
  public synchronized boolean canRead(String sessionId, String candidate) {
    if (PermissionService.isWithin(defaultRoot, candidate)) {
      return true;
    }
    List<Root> list = sessionRoots.get(sid(sessionId));
    if (list != null) {
      for (Root r : list) {
        if (!expired(sid(sessionId), r.path()) && PermissionService.isWithin(r.path(), candidate)) {
          return true;
        }
      }
    }
    return false;
  }

  /** True if {@code candidate} resolves within the default root or any {@code READ_WRITE} (non-expired) root. */
  public synchronized boolean canWrite(String sessionId, String candidate) {
    if (PermissionService.isWithin(defaultRoot, candidate)) {
      return true; // the default root is always READ_WRITE
    }
    List<Root> list = sessionRoots.get(sid(sessionId));
    if (list != null) {
      for (Root r : list) {
        if (r.writable() && !expired(sid(sessionId), r.path()) && PermissionService.isWithin(r.path(), candidate)) {
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

  /** A grant with its lifecycle metadata, for the admin view. */
  public record GrantMeta(String sessionId, String id, Path path, Access access, long grantedAt, Long remainingTtlMs) {}

  /** All additional grants across sessions with granted-at time and remaining TTL (null when unlimited). */
  public synchronized List<GrantMeta> allGrants() {
    long ttl = ttlMillis();
    List<GrantMeta> out = new ArrayList<>();
    for (java.util.Map.Entry<String, List<Root>> e : sessionRoots.entrySet()) {
      java.util.Map<String, Long> when = grantedAtMs.getOrDefault(e.getKey(), java.util.Map.of());
      for (Root r : e.getValue()) {
        long ts = when.getOrDefault(r.path().toString(), 0L);
        Long remaining = ttl > 0 ? Math.max(0L, ts + ttl - now()) : null;
        out.add(new GrantMeta(e.getKey(), r.id(), r.path(), r.access(), ts, remaining));
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
