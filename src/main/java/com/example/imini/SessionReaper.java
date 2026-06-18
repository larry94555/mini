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
 * Periodically prunes sessions that have been idle longer than {@code agent.session-ttl-hours}, so the
 * sessions table doesn't grow without bound on long-running deployments. Disabled when the TTL is 0
 * (the default — opt in by setting a TTL). Uses a single daemon ticker, mirroring ScheduledTasks.
 */
@Component
public class SessionReaper {

    private static final Logger log = LoggerFactory.getLogger(SessionReaper.class);

    private final SessionStore sessions;

    @Value("${agent.session-ttl-hours:0}") private long ttlHours;          // 0 => disabled
    @Value("${agent.session-reap-interval-minutes:60}") private long intervalMinutes;

    private ScheduledExecutorService ticker;

    public SessionReaper(SessionStore sessions) {
        this.sessions = sessions;
    }

    public long ttlMs() { return ttlHours * 3_600_000L; }

    @PostConstruct
    public void start() {
        if (ttlHours <= 0) {
            log.info("[sessions] reaper disabled (agent.session-ttl-hours=0)");
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-reaper");
            t.setDaemon(true);
            return t;
        });
        long interval = Math.max(1, intervalMinutes);
        ticker.scheduleAtFixedRate(this::reap, interval, interval, TimeUnit.MINUTES);
        log.info("[sessions] reaper enabled: ttl=" + ttlHours + "h, every " + interval + "min");
    }

    /** One pruning pass; safe to call manually. */
    public int reap() {
        try {
            return sessions.pruneExpired(ttlMs(), System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("[sessions] reap failed: " + e.getMessage());
            return 0;
        }
    }

    @PreDestroy
    public void stop() {
        if (ticker != null) ticker.shutdownNow();
    }
}
