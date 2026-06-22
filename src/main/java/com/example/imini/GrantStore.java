package com.example.imini;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Durable persistence for Track B workspace-root grants (the {@code workspace_grants} table). Thin and
 * best-effort: every method is a no-op when persistence is unavailable, so a DB hiccup never blocks an
 * approved grant. {@link WorkspaceRoots} owns the in-memory registry and uses this only to survive restarts.
 *
 * <p>Kept as a separate, override-friendly class so the reload/persist logic can be unit-tested with an
 * in-memory double — no live SQLite required.
 */
@Component
public class GrantStore {

  private final Database db;

  public GrantStore(Database db) {
    this.db = db;
  }

  /** One persisted grant row. */
  public record GrantRow(String sessionId, String path, String access, long grantedAt) {}

  /** True only when there is a real, available database to talk to. */
  public boolean active() {
    return db != null && db.available();
  }

  /** Persist (or replace) a grant. Best-effort. */
  public void save(String sessionId, String path, String access, long grantedAt) {
    if (!active()) {
      return;
    }
    db.update(
        "INSERT OR REPLACE INTO workspace_grants(session_id, path, access, granted_at) VALUES (?, ?, ?, ?)",
        sessionId, path, access, grantedAt);
  }

  /** Remove a grant by (session, path). Best-effort. */
  public void delete(String sessionId, String path) {
    if (!active()) {
      return;
    }
    db.update("DELETE FROM workspace_grants WHERE session_id = ? AND path = ?", sessionId, path);
  }

  /** Prune all grants older than {@code cutoffMillis} (granted_at &lt; cutoff). Best-effort. */
  public void deleteOlderThan(long cutoffMillis) {
    if (!active()) {
      return;
    }
    db.update("DELETE FROM workspace_grants WHERE granted_at < ?", cutoffMillis);
  }

  /** Load every persisted grant. Empty when persistence is unavailable. */
  public List<GrantRow> loadAll() {
    if (!active()) {
      return List.of();
    }
    return db.query(
        "SELECT session_id, path, access, granted_at FROM workspace_grants",
        rs -> new GrantRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
  }
}
