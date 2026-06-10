package com.example.imini;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Fixed-window per-key rate limiter. limitPerMinute <= 0 disables it. Deterministic given a clock,
 * so it is unit-testable without sleeping.
 */
public class RateLimiter {

    private final int limit;
    private final long windowMs;
    private final Map<String, long[]> state = new ConcurrentHashMap<>(); // key -> [windowStartMs, count]

    public RateLimiter(int limitPerMinute) {
        this(limitPerMinute, 60_000L);
    }

    public RateLimiter(int limit, long windowMs) {
        this.limit = limit;
        this.windowMs = windowMs;
    }

    public synchronized boolean allow(String key, long nowMs) {
        if (limit <= 0) return true;
        long[] w = state.computeIfAbsent(key, k -> new long[]{nowMs, 0});
        if (nowMs - w[0] >= windowMs) {
            w[0] = nowMs;
            w[1] = 0;
        }
        w[1]++;
        return w[1] <= limit;
    }
}
