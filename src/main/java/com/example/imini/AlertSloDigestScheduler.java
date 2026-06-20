package com.example.imini;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically pushes an SLO posture digest to the alert webhook so the people who care about SLOs don't have
 * to open the dashboard. Mirrors {@link AlertSelfTestScheduler}: a daemon ticker calling a public
 * {@link #runOnce()}. Disabled when alerting is off or {@code alerts.slo-digest-interval-minutes} is 0. The
 * digest is posted to {@code alerts.slo-digest-url} (falling back to {@code alerts.webhook-url}); a periodic
 * summary, not a critical alert, so it is sent synchronously without retry/dead-letter.
 */
@Component
public class AlertSloDigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlertSloDigestScheduler.class);

    private final AlertSink alerts;

    @Value("${alerts.enabled:false}") private boolean enabled;
    @Value("${alerts.slo-digest-interval-minutes:0}") private long intervalMinutes;

    private ScheduledExecutorService ticker;

    public AlertSloDigestScheduler(AlertSink alerts) {
        this.alerts = alerts;
    }

    @PostConstruct
    public void start() {
        if (!enabled || intervalMinutes <= 0) {
            log.info("[alerts] SLO digest scheduler disabled (enabled=" + enabled
                    + ", interval=" + intervalMinutes + "min)");
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "alert-slo-digest");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(this::runOnce, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        log.info("[alerts] SLO digest scheduler enabled: every " + intervalMinutes + "min -> " + alerts.digestUrl());
    }

    /** One digest pass; safe to call manually. Posts the digest and logs the outcome. */
    public void runOnce() {
        try {
            if (alerts.expireMuteIfDue()) {
                log.info("[alerts] SLO digest resumed after mute; sending the next scheduled digest");
            }
            Map<String, Object> result = alerts.postSloDigest();
            if (Boolean.TRUE.equals(result.get("posted"))) {
                log.info("[alerts] SLO digest posted: " + result.get("summary"));
            } else if ("muted".equals(result.get("mode"))) {
                log.info("[alerts] SLO digest suppressed (muted until " + result.get("muted_until") + ")");
            } else {
                log.warn("[alerts] SLO digest not posted: " + result);
            }
        } catch (Exception e) {
            log.warn("[alerts] SLO digest errored: " + e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (ticker != null) ticker.shutdownNow();
    }
}
