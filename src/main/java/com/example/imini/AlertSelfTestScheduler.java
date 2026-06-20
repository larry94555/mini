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
 * Periodically runs {@link AlertSink#selfTest} so wiring breakage is detectable proactively instead of only
 * when an operator checks. Mirrors {@link AlertDeadLetterReaper}: a daemon ticker calling a public
 * {@link #runOnce()}. Disabled when alerting is off or {@code alerts.selftest-interval-minutes} is 0. By
 * default it only checks resolution (does the action route to a configured URL?); set
 * {@code alerts.selftest-send=true} to also fire a live probe POST each tick (point
 * {@code alerts.selftest-action} at a route wired to a health endpoint to avoid paging a real channel). The
 * result is recorded on the sink ({@link AlertSink#selfTestStatus()}) and exported as
 * {@code imini_alerts_selftest_ok}.
 */
@Component
public class AlertSelfTestScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlertSelfTestScheduler.class);

    private final AlertSink alerts;

    @Value("${alerts.enabled:false}") private boolean enabled;
    @Value("${alerts.selftest-interval-minutes:0}") private long intervalMinutes;
    @Value("${alerts.selftest-action:}") private String action;
    @Value("${alerts.selftest-send:false}") private boolean send;

    private ScheduledExecutorService ticker;

    public AlertSelfTestScheduler(AlertSink alerts) {
        this.alerts = alerts;
    }

    @PostConstruct
    public void start() {
        if (!enabled || intervalMinutes <= 0) {
            log.info("[alerts] self-test scheduler disabled (enabled=" + enabled
                    + ", interval=" + intervalMinutes + "min)");
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "alert-selftest");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(this::runOnce, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        log.info("[alerts] self-test scheduler enabled: every " + intervalMinutes + "min (send=" + send + ")");
    }

    /** One self-test pass; safe to call manually. Records the outcome on the sink. */
    public void runOnce() {
        try {
            Map<String, Object> r = alerts.selfTest(action == null || action.isBlank() ? null : action, send);
            boolean ok = interpret(r, send);
            long latency = probeLatency(r);
            alerts.recordSelfTest(ok, latency, summarize(r, send));
            if (!ok) log.warn("[alerts] scheduled self-test FAILED: " + summarize(r, send));
        } catch (Exception e) {
            alerts.recordSelfTest(false, -1, "exception: " + e.getMessage());
            log.warn("[alerts] scheduled self-test errored: " + e.getMessage());
        }
    }

    /** Pure: did the self-test pass? Resolution must say it would deliver; if probing, the probe must be ok. */
    static boolean interpret(Map<String, Object> r, boolean send) {
        if (r == null) return false;
        boolean wouldDeliver = Boolean.TRUE.equals(r.get("would_deliver"));
        if (!send) return wouldDeliver;
        Object p = r.get("probe");
        if (!(p instanceof Map<?, ?> probe)) return false;
        return Boolean.TRUE.equals(probe.get("ok"));
    }

    private static long probeLatency(Map<String, Object> r) {
        Object p = r == null ? null : r.get("probe");
        if (p instanceof Map<?, ?> probe && probe.get("latency_ms") instanceof Number n) return n.longValue();
        return -1;
    }

    private static String summarize(Map<String, Object> r, boolean send) {
        if (r == null) return "no result";
        StringBuilder sb = new StringBuilder();
        sb.append("action=").append(r.get("action"));
        sb.append(" would_deliver=").append(r.get("would_deliver"));
        if (send && r.get("probe") instanceof Map<?, ?> probe) {
            sb.append(" probe_ok=").append(probe.get("ok"));
            if (probe.get("status") != null) sb.append(" status=").append(probe.get("status"));
            if (probe.get("error") != null) sb.append(" error=").append(probe.get("error"));
        }
        return sb.toString();
    }

    @PreDestroy
    public void stop() {
        if (ticker != null) ticker.shutdownNow();
    }
}
