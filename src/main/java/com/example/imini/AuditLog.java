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

    /**
     * Optional listeners notified (best-effort, after persistence) of every recorded entry. Used by the
     * alert sink to forward security-relevant events to an external channel. Held as a copy-on-write list so
     * {@link #record} stays lock-free; a listener throwing never affects the audit write or the run.
     */
    private final java.util.List<java.util.function.Consumer<Entry>> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addListener(java.util.function.Consumer<Entry> listener) {
        if (listener != null) listeners.add(listener);
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
        for (java.util.function.Consumer<Entry> l : listeners) {
            try {
                l.accept(e);
            } catch (Exception ex) {
                log.warn("[audit] listener failed for " + action + ": " + ex.getMessage());
            }
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

    /** All entries (newest first) matching user/action/target within [since, until] (0 = unbounded). */
    public List<Entry> range(String user, String action, String target, long since, long until, int limit) {
        List<Entry> all;
        if (db.available()) {
            all = db.query("SELECT id, ts, user, action, target, outcome FROM audit ORDER BY ts DESC, rowid DESC LIMIT ?",
                    rs -> new Entry(rs.getString(1), rs.getLong(2), Instant.ofEpochMilli(rs.getLong(2)).toString(),
                            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)),
                    HARD_CAP);
        } else {
            all = new ArrayList<>(mem);
        }
        return filterRange(all, user, action, target, since, until, limit);
    }

    /** Pure: user/action/target match (see {@link #filter}) plus a time window [since, until]. */
    public static List<Entry> filterRange(List<Entry> entries, String user, String action, String target,
                                          long since, long until, int limit) {
        List<Entry> matched = filter(entries, user, action, target, 0, HARD_CAP);
        List<Entry> windowed = new ArrayList<>();
        int lim = limit <= 0 ? HARD_CAP : Math.min(limit, HARD_CAP);
        for (Entry e : matched) {
            if (since > 0 && e.ts() < since) continue;
            if (until > 0 && e.ts() > until) continue;
            windowed.add(e);
            if (windowed.size() >= lim) break;
        }
        return windowed;
    }

    /**
     * A single page of entries matching user/action/target within [since, until] (0 = unbounded), newest
     * first, skipping {@code offset} matches and returning up to {@code limit}.
     */
    public List<Entry> pageRange(String user, String action, String target,
                                 long since, long until, int offset, int limit) {
        return filterRangePaged(allEntries(), user, action, target, since, until, offset, limit);
    }

    /** Total number of entries matching the same filters (for pagination math). */
    public int countRange(String user, String action, String target, long since, long until) {
        return filterRange(allEntries(), user, action, target, since, until, HARD_CAP).size();
    }

    private List<Entry> allEntries() {
        if (db.available()) {
            return db.query("SELECT id, ts, user, action, target, outcome FROM audit ORDER BY ts DESC, rowid DESC LIMIT ?",
                    rs -> new Entry(rs.getString(1), rs.getLong(2), Instant.ofEpochMilli(rs.getLong(2)).toString(),
                            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)),
                    HARD_CAP);
        }
        return new ArrayList<>(mem);
    }

    /** Pure: filter by user/action/target + [since, until], then apply offset/limit paging. */
    public static List<Entry> filterRangePaged(List<Entry> entries, String user, String action, String target,
                                               long since, long until, int offset, int limit) {
        List<Entry> matched = filterRange(entries, user, action, target, since, until, HARD_CAP);
        int off = Math.max(0, offset);
        int lim = limit <= 0 ? 100 : Math.min(limit, HARD_CAP);
        List<Entry> out = new ArrayList<>();
        for (int i = off; i < matched.size() && out.size() < lim; i++) out.add(matched.get(i));
        return out;
    }

    /** Pure: render entries as CSV (header + RFC-4180-escaped rows). */
    public static String toCsv(List<Entry> entries) {
        StringBuilder sb = new StringBuilder("id,ts,time,user,action,target,outcome\n");
        if (entries != null) {
            for (Entry e : entries) {
                sb.append(csv(e.id())).append(',').append(e.ts()).append(',').append(csv(e.time())).append(',')
                  .append(csv(e.user())).append(',').append(csv(e.action())).append(',')
                  .append(csv(e.target())).append(',').append(csv(e.outcome())).append('\n');
            }
        }
        return sb.toString();
    }

    private static String csv(String s) {
        String v = s == null ? "" : s;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
