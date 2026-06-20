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
 * Periodically ages out failed dead-letter rows older than {@code alerts.dead-letter-retention-hours} so the
 * {@code alerts_dead_letter} table doesn't grow without bound on a deployment where some alerts never deliver.
 * Mirrors {@link RateLimitReaper}/{@code SessionReaper}: a daemon ticker calling a public {@link #reap()}.
 * Disabled when alerting is off, retention is 0 (keep forever), or the interval is 0. The in-memory
 * dead-letter ring is already size-bounded, so this only matters with the durable (SQLite) store.
 */
@Component
public class AlertDeadLetterReaper {

    private static final Logger log = LoggerFactory.getLogger(AlertDeadLetterReaper.class);

    private final AlertSink alerts;

    @Value("${alerts.enabled:false}") private boolean enabled;
    @Value("${alerts.dead-letter-reap-interval-minutes:60}") private long intervalMinutes;

    private ScheduledExecutorService ticker;

    public AlertDeadLetterReaper(AlertSink alerts) {
        this.alerts = alerts;
    }

    @PostConstruct
    public void start() {
        boolean work = alerts.retentionHours() > 0 || alerts.escalationEnabled() || alerts.digestEnabled()
                || alerts.windowPersistent();
        if (!enabled || intervalMinutes <= 0 || !work) {
            log.info("[alerts] dead-letter reaper disabled (enabled=" + enabled
                    + ", interval=" + intervalMinutes + "min, retention=" + alerts.retentionHours()
                    + "h, escalation=" + alerts.escalationEnabled() + ", digest=" + alerts.digestEnabled() + ")");
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "alert-deadletter-reaper");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(this::reap, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        log.info("[alerts] dead-letter reaper enabled: every " + intervalMinutes + "min, retention "
                + alerts.retentionHours() + "h");
    }

    /** One purge pass; safe to call manually. Returns the number of aged-out dead-letters removed. */
    public int reap() {
        try {
            long now = System.currentTimeMillis();
            alerts.escalateStale(now);     // walk un-acked dead-letters up the escalation ladder
            alerts.dedupDigestSweep(now);  // emit digests for elapsed dedup windows with suppressions
            alerts.flushWindow();          // persist rolling-window SLO buckets
            return alerts.purgeOlderThan(alerts.retentionHours(), now);
        } catch (Exception e) {
            log.warn("[alerts] dead-letter reap failed: " + e.getMessage());
            return 0;
        }
    }

    @PreDestroy
    public void stop() {
        if (ticker != null) ticker.shutdownNow();
    }
}
