package com.example.imini;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically calls {@link RateLimiter#pruneStale} on the active limiter so the {@code rate_limits} table
 * (or the in-memory map) doesn't accumulate windows for keys that have gone quiet. Without this, the table
 * is bounded only by the number of distinct keys ever seen, which grows over time on a busy multi-user
 * deployment. Disabled when rate limiting is off, or when the interval is set to 0. Mirrors SessionReaper.
 */
@Component
public class RateLimitReaper {

    private static final Logger log = LoggerFactory.getLogger(RateLimitReaper.class);

    private final AuthFilter auth;

    @Value("${auth.rate-limit-per-minute:0}") private int rateLimitPerMinute;
    @Value("${auth.rate-limit-reap-interval-minutes:10}") private long intervalMinutes;

    private ScheduledExecutorService ticker;

    public RateLimitReaper(AuthFilter auth) {
        this.auth = auth;
    }

    @PostConstruct
    public void start() {
        if (rateLimitPerMinute <= 0 || intervalMinutes <= 0) {
            log.info("[ratelimit] reaper disabled (rate-limit-per-minute=" + rateLimitPerMinute
                    + ", interval=" + intervalMinutes + "min)");
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ratelimit-reaper");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(this::reap, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        log.info("[ratelimit] reaper enabled: every " + intervalMinutes + "min");
    }

    /** One prune pass; safe to call manually. Returns the number of stale entries removed. */
    public int reap() {
        try {
            RateLimiter limiter = auth.limiter();
            if (limiter == null) return 0;
            int removed = limiter.pruneStale(System.currentTimeMillis());
            if (removed > 0) log.info("[ratelimit] pruned " + removed + " stale window(s)");
            return removed;
        } catch (Exception e) {
            log.warn("[ratelimit] reap failed: " + e.getMessage());
            return 0;
        }
    }

    @PreDestroy
    public void stop() {
        if (ticker != null) ticker.shutdownNow();
    }
}
