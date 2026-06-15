package com.example.imini;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Append-only audit trail of privileged actions: who did what, to which target, when, and the outcome.
 * Now that every request carries a {@link Principal} (see AuthFilter/RequestContext) and resources are
 * owned (see Ownership), this turns "we gate actions" into "we can show who did what". Entries are
 * persisted in SQLite (table {@code audit}); when persistence is unavailable a bounded in-memory ring
 * is used instead. Exposed read-only to admins at {@code GET /audit} (filterable by user/target).
 */
@Component
public class AuditLog {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuditLog.class);

    private static final int MEM_MAX = 1000;
    private static final int HARD_CAP = 1000;

    public record Entry(String id, long ts, String time, String user, String action, String target, String outcome) {}

    private final Database db;
    private final Deque<Entry> mem = new ConcurrentLinkedDeque<>(); // newest first; fallback

    public AuditLog(Database db) {
        this.db = db;
    }

    /** Record one privileged action. Best-effort: never throws into the caller's request path. */
    public void record(String user, String action, String target, String outcome) {
        long ts = System.currentTimeMillis();
        Entry e = new Entry(UUID.randomUUID().toString(), ts, Instant.ofEpochMilli(ts).toString(),
                nz(user), nz(action), nz(target), nz(outcome));
        try {
            if (db.available()) {
                db.update("INSERT INTO audit(id, ts, user, action, target, outcome) VALUES(?,?,?,?,?,?)",
                        e.id(), e.ts(), e.user(), e.action(), e.target(), e.outcome());
            } else {
                mem.addFirst(e);
                while (mem.size() > MEM_MAX) mem.removeLast();
            }
        } catch (Exception ex) {
            log.warn("[audit] could not record " + action + ": " + ex.getMessage());
        }
    }

    /** Most recent entries (newest first), optionally filtered by user and/or target substring. */
    public List<Entry> recent(String user, String target, int limit) {
        return recent(user, null, target, 0, limit);
    }

    public List<Entry> recent(String user, String action, String target, int offset, int limit) {
        List<Entry> all;
        if (db.available()) {
            all = db.query("SELECT id, ts, user, action, target, outcome FROM audit ORDER BY ts DESC, rowid DESC LIMIT ?",
                    rs -> new Entry(rs.getString(1), rs.getLong(2), Instant.ofEpochMilli(rs.getLong(2)).toString(),
                            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)),
                    HARD_CAP);
        } else {
            all = new ArrayList<>(mem);
        }
        return filter(all, user, action, target, offset, limit);
    }

    /** Pure, unit-testable filter: by exact user (case-insensitive) and/or target substring, capped. */
    public static List<Entry> filter(List<Entry> entries, String user, String target, int limit) {
        return filter(entries, user, null, target, 0, limit);
    }

    /** Filter by user (exact, case-insensitive), action (substring), target (substring), then page. Pure. */
    public static List<Entry> filter(List<Entry> entries, String user, String action, String target,
                                     int offset, int limit) {
        int lim = limit <= 0 ? 100 : Math.min(limit, HARD_CAP);
        int off = Math.max(0, offset);
        List<Entry> matched = new ArrayList<>();
        for (Entry e : entries) {
            String u = e.user() == null ? "" : e.user();
            String a = e.action() == null ? "" : e.action();
            String t = e.target() == null ? "" : e.target();
            if (user != null && !user.isBlank() && !user.equalsIgnoreCase(u)) continue;
            if (action != null && !action.isBlank() && !a.toLowerCase().contains(action.toLowerCase())) continue;
            if (target != null && !target.isBlank() && !t.contains(target)) continue;
            matched.add(e);
        }
        List<Entry> out = new ArrayList<>();
        for (int i = off; i < matched.size() && out.size() < lim; i++) out.add(matched.get(i));
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
