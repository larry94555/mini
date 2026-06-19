package com.example.imini;

import org.springframework.stereotype.Component;

/**
 * Bridges the audit log to {@link Metrics} so security-relevant events — capability denials, spend alerts,
 * tool rate-limit rejections, and every other audited action — become scrapable counters. It registers an
 * {@link AuditLog} listener at startup and, for each recorded entry, increments a counter named
 * {@code audit_<action>} (plus an {@code audit_events} total). These flow through the existing
 * {@code counters} block of the metrics snapshot and the Prometheus endpoint with no extra wiring.
 *
 * <p>Always on and independent of the alert sink, so the counts are available even when alerting is disabled.
 * Audit actions are a small bounded set, so label/series cardinality stays low.
 */
@Component
public class AuditMetrics {

    private final AuditLog audit;
    private final Metrics metrics;

    public AuditMetrics(AuditLog audit, Metrics metrics) {
        this.audit = audit;
        this.metrics = metrics;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        audit.addListener(this::count);
    }

    private void count(AuditLog.Entry e) {
        metrics.inc("audit_events");
        String action = e.action();
        if (action != null && !action.isBlank()) {
            metrics.inc("audit_" + action);
        }
    }
}
