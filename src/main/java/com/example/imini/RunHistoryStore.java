package com.example.imini;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Durable backing for the run-history view: persists finished-run records to the {@code run_history}
 * table and reloads a tail on startup, so the admin dashboard's recent-runs list survives a restart
 * (like settings and scheduled tasks already do). Pruned to a cap so it can't grow without bound. Falls
 * back to no-op / empty when persistence is unavailable.
 */
@Component
public class RunHistoryStore {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RunHistoryStore.class);

    @Value("${agent.run-history.persist-max:500}") private int persistMax;

    private final Database db;

    public RunHistoryStore(Database db) {
        this.db = db;
    }

    /** Persist one record, then prune to the most recent {@code persistMax} rows. */
    public void append(RunHistory.Record r) {
        if (!db.available() || r == null) return;
        try {
            db.update("INSERT INTO run_history(ts, endpoint, session, mode, ms, ok) VALUES(?,?,?,?,?,?)",
                    r.ts(), r.endpoint(), r.session(), r.mode(), r.ms(), r.ok() ? 1 : 0);
            int cap = Math.max(1, persistMax);
            // keep only the newest `cap` rows (SQLite rowid orders by insertion)
            db.update("DELETE FROM run_history WHERE rowid NOT IN "
                    + "(SELECT rowid FROM run_history ORDER BY ts DESC, rowid DESC LIMIT ?)", cap);
        } catch (Exception e) {
            log.warn("[run-history] persist failed: " + e.getMessage());
        }
    }

    /** The most recent {@code n} records, oldest-first (ready to replay into the in-memory buffer). */
    public List<RunHistory.Record> loadRecent(int n) {
        List<RunHistory.Record> out = new ArrayList<>();
        if (!db.available()) return out;
        try {
            List<RunHistory.Record> newestFirst = db.query(
                    "SELECT ts, endpoint, session, mode, ms, ok FROM run_history ORDER BY ts DESC, rowid DESC LIMIT ?",
                    rs -> new RunHistory.Record(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getLong(5), rs.getInt(6) == 1),
                    Math.max(1, n));
            for (int i = newestFirst.size() - 1; i >= 0; i--) out.add(newestFirst.get(i)); // -> oldest first
        } catch (Exception e) {
            log.warn("[run-history] load failed: " + e.getMessage());
        }
        return out;
    }
}
