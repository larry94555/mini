package com.example.imini;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key rate limiter with two selectable algorithms:
 *
 * <ul>
 *   <li><b>fixed</b> — a fixed window of {@code windowMs}: count resets to 0 at each window boundary.
 *       Simple, but allows a burst of up to 2x the limit straddling a boundary (limit requests at the end
 *       of one window, then limit again at the start of the next).</li>
 *   <li><b>sliding</b> — a sliding window that estimates the rate over the trailing {@code windowMs} by
 *       weighting the previous window's count by the fraction of it still "in view". This smooths out the
 *       boundary burst at the cost of one extra stored counter per key.</li>
 * </ul>
 *
 * {@code limit <= 0} disables limiting. Both algorithms are deterministic given a clock, so they are
 * unit-testable without sleeping. Optionally backed by a {@link Database} so windows survive a restart;
 * when no database is supplied (or it is unavailable) it falls back to an in-memory map with identical
 * behaviour.
 */
public class RateLimiter {

    public enum Algorithm { FIXED, SLIDING }

    private final int limit;
    private final long windowMs;
    private final Algorithm algorithm;
    private final Database db; // nullable: null => in-memory only

    // Fixed-window in-memory state: key -> [windowStartMs, count]
    private final Map<String, long[]> state = new ConcurrentHashMap<>();
    // Sliding-window in-memory state: key -> [windowStartMs, currentCount, prevCount]
    private final Map<String, long[]> slidingState = new ConcurrentHashMap<>();

    public RateLimiter(int limitPerMinute) {
        this(limitPerMinute, 60_000L, null, Algorithm.FIXED);
    }

    public RateLimiter(int limit, long windowMs) {
        this(limit, windowMs, null, Algorithm.FIXED);
    }

    public RateLimiter(int limit, long windowMs, Database db) {
        this(limit, windowMs, db, Algorithm.FIXED);
    }

    public RateLimiter(int limit, long windowMs, Database db, Algorithm algorithm) {
        this.limit = limit;
        this.windowMs = windowMs;
        this.db = db;
        this.algorithm = algorithm == null ? Algorithm.FIXED : algorithm;
    }

    public Algorithm algorithm() { return algorithm; }

    // ---------------------------------------------------------------------
    // Pure window arithmetic (static, fully unit-testable)
    // ---------------------------------------------------------------------

    /** Fixed window: given [start,count], return the next [start,count] for a request at nowMs. */
    static long[] step(long windowStart, long count, long nowMs, long windowMs) {
        if (nowMs - windowStart >= windowMs) { // new window
            return new long[]{nowMs, 1};
        }
        return new long[]{windowStart, count + 1};
    }

    /**
     * Sliding window: given the stored [start, current, prev], return the new state AND the effective
     * weighted count for a request at nowMs, as [start, current, prev, weightedCount].
     *
     * <p>The weighted count is {@code current + prev * (fraction of the previous window still in the
     * trailing window)}. When a boundary is crossed, the old current becomes prev; crossing two or more
     * boundaries clears history. This is the standard "sliding window counter" approximation.
     */
    static long[] slidingStep(long windowStart, long current, long prev, long nowMs, long windowMs) {
        long elapsed = nowMs - windowStart;
        if (elapsed >= 2 * windowMs) {
            // Idle for >= two windows: history is irrelevant; start fresh.
            windowStart = nowMs; current = 0; prev = 0; elapsed = 0;
        } else if (elapsed >= windowMs) {
            // Crossed exactly one boundary: current rolls into prev, advance start by one window.
            prev = current; current = 0; windowStart += windowMs; elapsed = nowMs - windowStart;
        }
        long newCurrent = current + 1;
        // fraction of the previous window still inside the trailing windowMs (0..1), scaled by 1000 to
        // stay in integer arithmetic: weight = (windowMs - elapsed) / windowMs.
        long weightNumerator = Math.max(0, windowMs - elapsed);
        long weightedPrev = (prev * weightNumerator) / windowMs;
        long weighted = newCurrent + weightedPrev;
        return new long[]{windowStart, newCurrent, prev, weighted};
    }

    // ---------------------------------------------------------------------
    // allow()
    // ---------------------------------------------------------------------

    public synchronized boolean allow(String key, long nowMs) {
        if (limit <= 0) return true;
        return algorithm == Algorithm.SLIDING ? allowSliding(key, nowMs) : allowFixed(key, nowMs);
    }

    private boolean allowFixed(String key, long nowMs) {
        if (db != null && db.available()) return allowFixedPersistent(key, nowMs);
        long[] w = state.computeIfAbsent(key, k -> new long[]{nowMs, 0});
        long[] next = step(w[0], w[1], nowMs, windowMs);
        w[0] = next[0];
        w[1] = next[1];
        return w[1] <= limit;
    }

    private boolean allowFixedPersistent(String key, long nowMs) {
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

    private boolean allowSliding(String key, long nowMs) {
        if (db != null && db.available()) return allowSlidingPersistent(key, nowMs);
        long[] s = slidingState.computeIfAbsent(key, k -> new long[]{nowMs, 0, 0});
        long[] r = slidingStep(s[0], s[1], s[2], nowMs, windowMs);
        s[0] = r[0]; s[1] = r[1]; s[2] = r[2];
        return r[3] <= limit; // r[3] is the weighted count
    }

    private boolean allowSlidingPersistent(String key, long nowMs) {
        long windowStart = nowMs, current = 0, prev = 0;
        var rows = db.query("SELECT window_start, count, prev_count FROM rate_limits WHERE rl_key=?",
                rs -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}, key);
        if (!rows.isEmpty()) { windowStart = rows.get(0)[0]; current = rows.get(0)[1]; prev = rows.get(0)[2]; }
        long[] r = slidingStep(windowStart, current, prev, nowMs, windowMs);
        db.update("INSERT INTO rate_limits(rl_key, window_start, count, prev_count) VALUES(?,?,?,?) "
                        + "ON CONFLICT(rl_key) DO UPDATE SET window_start=excluded.window_start, "
                        + "count=excluded.count, prev_count=excluded.prev_count",
                key, r[0], r[1], r[2]);
        return r[3] <= limit;
    }

    // ---------------------------------------------------------------------
    // Maintenance
    // ---------------------------------------------------------------------

    /** Remove rows/entries whose window is fully stale, so storage can't grow without bound. */
    public int pruneStale(long nowMs) {
        if (db == null || !db.available()) {
            int before = state.size() + slidingState.size();
            // fixed: stale after one window; sliding: stale after two windows (prev no longer in view).
            state.entrySet().removeIf(e -> nowMs - e.getValue()[0] >= windowMs);
            slidingState.entrySet().removeIf(e -> nowMs - e.getValue()[0] >= 2 * windowMs);
            return before - (state.size() + slidingState.size());
        }
        // A row is stale once even a sliding window's previous bucket has scrolled out of view.
        return db.update("DELETE FROM rate_limits WHERE ? - window_start >= ?", nowMs, 2 * windowMs);
    }
}
