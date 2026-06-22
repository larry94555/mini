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
  private final List<Root> roots = new ArrayList<>();
  private int seq;

  @PostConstruct
  public synchronized void load() {
    defaultRoot =
        (workspaceRootCfg == null || workspaceRootCfg.isBlank()
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(workspaceRootCfg))
            .toAbsolutePath()
            .normalize();

    roots.clear();
    seq = 0;
    roots.add(new Root("default", defaultRoot, Access.READ_WRITE));

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
          add(Path.of(pathPart), parseAccess(accPart));
        }
      }
    }

    log.info(
        "[workspace-roots] multi-root="
            + enabled
            + "; default="
            + defaultRoot
            + (roots.size() > 1 ? "; +" + (roots.size() - 1) + " additional root(s)" : ""));
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

  /** Immutable snapshot of the registered roots (default first). */
  public synchronized List<Root> roots() {
    return List.copyOf(roots);
  }

  /**
   * Register (or replace) an additional root. No-op unless multi-root is enabled — so this cannot widen
   * access while the feature is off. The default root cannot be replaced or downgraded here.
   */
  public synchronized Root add(Path path, Access access) {
    if (!enabled) {
      log.warn("[workspace-roots] ignoring add(" + path + "): multi-root is disabled");
      return null;
    }
    Path norm = path.toAbsolutePath().normalize();
    if (norm.equals(defaultRoot)) {
      return roots.get(0); // default stays READ_WRITE; never downgraded
    }
    roots.removeIf(r -> r.path().equals(norm) && !"default".equals(r.id()));
    Root r = new Root("r" + (++seq), norm, access);
    roots.add(r);
    log.info("[workspace-roots] added root " + r.id() + " " + r.access() + " " + norm);
    return r;
  }

  /** Remove an additional root by path. The default root cannot be removed. Returns true if removed. */
  public synchronized boolean remove(Path path) {
    Path norm = path.toAbsolutePath().normalize();
    if (norm.equals(defaultRoot)) {
      return false;
    }
    return roots.removeIf(r -> r.path().equals(norm) && !"default".equals(r.id()));
  }

  /** True if {@code candidate} (relative or absolute) resolves within ANY registered root. */
  public synchronized boolean canRead(String candidate) {
    for (Root r : roots) {
      if (PermissionService.isWithin(r.path(), candidate)) {
        return true;
      }
    }
    return false;
  }

  /** True if {@code candidate} resolves within any {@code READ_WRITE} root. */
  public synchronized boolean canWrite(String candidate) {
    for (Root r : roots) {
      if (r.writable() && PermissionService.isWithin(r.path(), candidate)) {
        return true;
      }
    }
    return false;
  }
}
