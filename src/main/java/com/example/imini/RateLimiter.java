package com.example.imini;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window per-key rate limiter. {@code limit <= 0} disables it. Deterministic given a clock, so it is
 * unit-testable without sleeping.
 *
 * <p>Optionally backed by a {@link Database} so windows survive a restart (preventing a burst of requests
 * from sneaking through immediately after a redeploy). When no database is supplied (or it is unavailable),
 * it falls back to the in-memory map -- identical behaviour, just not durable.
 */
public class RateLimiter {

    private final int limit;
    private final long windowMs;
    private final Map<String, long[]> state = new ConcurrentHashMap<>(); // key -> [windowStartMs, count]
    private final Database db; // nullable: null => in-memory only

    public RateLimiter(int limitPerMinute) {
        this(limitPerMinute, 60_000L, null);
    }

    public RateLimiter(int limit, long windowMs) {
        this(limit, windowMs, null);
    }

    public RateLimiter(int limit, long windowMs, Database db) {
        this.limit = limit;
        this.windowMs = windowMs;
        this.db = db;
    }

    /** Pure window arithmetic: given the current [start,count], return the next [start,count]. */
    static long[] step(long windowStart, long count, long nowMs, long windowMs) {
        if (nowMs - windowStart >= windowMs) { // new window
            return new long[]{nowMs, 1};
        }
        return new long[]{windowStart, count + 1};
    }

    public synchronized boolean allow(String key, long nowMs) {
        if (limit <= 0) return true;
        if (db != null && db.available()) {
            return allowPersistent(key, nowMs);
        }
        long[] w = state.computeIfAbsent(key, k -> new long[]{nowMs, 0});
        long[] next = step(w[0], w[1], nowMs, windowMs);
        w[0] = next[0];
        w[1] = next[1];
        return w[1] <= limit;
    }

    private boolean allowPersistent(String key, long nowMs) {
        long windowStart = nowMs, count = 0;
        var rows = db.query("SELECT window_start, count FROM rate_limits WHERE rl_key=?",
                rs -> new long[]{rs.getLong(1), rs.getLong(2)}, key);
        if (!rows.isEmpty()) { windowStart = rows.get(0)[0]; count = rows.get(0)[1]; }
        long[] next = step(windowStart, count, nowMs, windowMs);
        db.update("INSERT INTO rate_limits(rl_key, window_start, count) VALUES(?,?,?) "
                        + "ON CONFLICT(rl_key) DO UPDATE SET window_start=excluded.window_start, count=excluded.count",
                key, next[0], next[1]);
        return next[1] <= limit;
    }

    /** Remove rows whose window has fully elapsed, so the table can't grow without bound. */
    public int pruneStale(long nowMs) {
        if (db == null || !db.available()) {
            int before = state.size();
            state.entrySet().removeIf(e -> nowMs - e.getValue()[0] >= windowMs);
            return before - state.size();
        }
        return db.update("DELETE FROM rate_limits WHERE ? - window_start >= ?", nowMs, windowMs);
    }
}
