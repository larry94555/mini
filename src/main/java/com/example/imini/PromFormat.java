package com.example.imini;

import java.util.List;
import java.util.Map;

/**
 * Pure renderer from the {@link Metrics} snapshot map to the Prometheus text exposition format, so the
 * metrics already tracked can be scraped by external monitoring. Dependency-free and deterministic
 * (sorted keys, sanitized names) for easy unit testing; the endpoint just serves the string.
 */
public final class PromFormat {

    private PromFormat() {}

    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private static final String PREFIX = "imini_";

    /** Render a snapshot to Prometheus text. Unknown/odd shapes are skipped rather than failing. */
    @SuppressWarnings("unchecked")
    public static String render(Map<String, Object> snapshot) {
        StringBuilder sb = new StringBuilder();
        if (snapshot == null) return sb.toString();

        if (snapshot.get("uptime_ms") instanceof Number up) {
            gauge(sb, "uptime_seconds", "Process uptime in seconds", up.longValue() / 1000.0, "");
        }

        if (snapshot.get("counters") instanceof Map<?, ?> counters) {
            help(sb, "counter", "Named event counters", "counter");
            for (Map.Entry<String, Long> e : sorted((Map<String, Long>) counters)) {
                line(sb, PREFIX + "counter", "name=\"" + esc(e.getKey()) + "\"", e.getValue());
            }
        }
        if (snapshot.get("tool_calls_by_name") instanceof Map<?, ?> tools) {
            help(sb, "tool_calls", "Tool calls by tool name", "counter");
            for (Map.Entry<String, Long> e : sorted((Map<String, Long>) tools)) {
                line(sb, PREFIX + "tool_calls", "tool=\"" + esc(e.getKey()) + "\"", e.getValue());
            }
        }
        if (snapshot.get("requests_by_key") instanceof Map<?, ?> keys) {
            help(sb, "requests_by_key", "Requests by API key label", "counter");
            for (Map.Entry<String, Long> e : sorted((Map<String, Long>) keys)) {
                line(sb, PREFIX + "requests_by_key", "key=\"" + esc(e.getKey()) + "\"", e.getValue());
            }
        }
        if (snapshot.get("runs_by_endpoint") instanceof Map<?, ?> eps) {
            help(sb, "runs_by_endpoint", "Runs by endpoint", "counter");
            for (Map.Entry<String, Long> e : sorted((Map<String, Long>) eps)) {
                line(sb, PREFIX + "runs_by_endpoint", "endpoint=\"" + esc(e.getKey()) + "\"", e.getValue());
            }
        }
        if (snapshot.get("run_latency") instanceof Map<?, ?> lat) {
            number(sb, "run_latency_count", "Completed runs measured for latency", "gauge", lat.get("count"));
            number(sb, "run_latency_avg_ms", "Average run latency (ms)", "gauge", lat.get("avg_ms"));
            number(sb, "run_latency_max_ms", "Max run latency (ms)", "gauge", lat.get("max_ms"));
            number(sb, "run_latency_p50_ms", "Median (p50) run latency (ms)", "gauge", lat.get("p50_ms"));
            number(sb, "run_latency_p95_ms", "p95 run latency (ms)", "gauge", lat.get("p95_ms"));
        }
        if (snapshot.get("slo") instanceof Map<?, ?> slo) {
            number(sb, "run_success_rate", "Run success rate (percent)", "gauge", slo.get("success_rate"));
        }
        if (snapshot.get("approx_output_tokens") instanceof Number tok) {
            gauge(sb, "approx_output_tokens", "Approximate model output tokens", tok.doubleValue(), "");
        }
        if (snapshot.get("concurrency") instanceof Map<?, ?> conc) {
            number(sb, "concurrency_limit", "Max concurrent runs", "gauge", conc.get("limit"));
            number(sb, "concurrency_active", "Active runs", "gauge", conc.get("active"));
            number(sb, "concurrency_queued", "Queued runs", "gauge", conc.get("queued"));
        }
        if (snapshot.get("alerts") instanceof Map<?, ?> alerts) {
            number(sb, "alerts_queued", "Alerts enqueued for delivery", "counter", alerts.get("queued"));
            number(sb, "alerts_sent", "Alerts delivered successfully", "counter", alerts.get("sent"));
            number(sb, "alerts_failed", "Alert delivery attempt failures", "counter", alerts.get("failed"));
            number(sb, "alerts_retried", "Alert delivery retries scheduled", "counter", alerts.get("retried"));
            number(sb, "alerts_dead_lettered", "Alerts moved to dead-letter", "counter", alerts.get("dead_lettered"));
            number(sb, "alerts_dropped", "Alerts dropped (buffer full)", "counter", alerts.get("dropped"));
            number(sb, "alerts_replayed", "Dead-lettered alerts re-enqueued", "counter", alerts.get("replayed"));
            number(sb, "alerts_suppressed", "Duplicate alerts collapsed by dedup", "counter", alerts.get("suppressed"));
            number(sb, "alerts_escalated", "Un-acked dead-letters re-paged to escalation route", "counter", alerts.get("escalated"));
            number(sb, "alerts_digested", "Dedup-digest notifications emitted", "counter", alerts.get("digested"));
            number(sb, "alerts_sla_breaches", "Re-escalations triggered by an ack-SLA breach", "counter", alerts.get("sla_breaches"));
            number(sb, "alerts_in_flight", "Alert deliveries in flight", "gauge", alerts.get("in_flight"));
            number(sb, "alerts_dead_letter_size", "Dead-letter ring size", "gauge", alerts.get("dead_letter_size"));
            if (alerts.get("by_route") instanceof Map<?, ?> byRoute) {
                help(sb, "alerts_route_sent", "Alerts delivered by route", "counter");
                for (Map.Entry<String, Map<String, Long>> e : sortedRoutes((Map<String, Map<String, Long>>) byRoute)) {
                    line(sb, PREFIX + "alerts_route_sent", "route=\"" + esc(e.getKey()) + "\"", e.getValue().get("sent"));
                }
                help(sb, "alerts_route_failed", "Alert attempt failures by route", "counter");
                for (Map.Entry<String, Map<String, Long>> e : sortedRoutes((Map<String, Map<String, Long>>) byRoute)) {
                    line(sb, PREFIX + "alerts_route_failed", "route=\"" + esc(e.getKey()) + "\"", e.getValue().get("failed"));
                }
                help(sb, "alerts_route_dead_lettered", "Alerts dead-lettered by route", "counter");
                for (Map.Entry<String, Map<String, Long>> e : sortedRoutes((Map<String, Map<String, Long>>) byRoute)) {
                    line(sb, PREFIX + "alerts_route_dead_lettered", "route=\"" + esc(e.getKey()) + "\"", e.getValue().get("dead_lettered"));
                }
                help(sb, "alerts_route_suppressed", "Alerts suppressed by dedup by route", "counter");
                for (Map.Entry<String, Map<String, Long>> e : sortedRoutes((Map<String, Map<String, Long>>) byRoute)) {
                    line(sb, PREFIX + "alerts_route_suppressed", "route=\"" + esc(e.getKey()) + "\"", e.getValue().get("suppressed"));
                }
                help(sb, "alerts_route_latency_avg_ms", "Mean webhook delivery latency by route (ms)", "gauge");
                for (Map.Entry<String, Map<String, Long>> e : sortedRoutes((Map<String, Map<String, Long>>) byRoute)) {
                    Long avg = e.getValue().get("avg_latency_ms");
                    if (avg != null) line(sb, PREFIX + "alerts_route_latency_avg_ms", "route=\"" + esc(e.getKey()) + "\"", avg);
                }
            }
            if (alerts.get("by_tier") instanceof Map<?, ?> byTier) {
                help(sb, "alerts_escalated_tier", "Escalations paged by ladder tier", "counter");
                for (Map.Entry<String, Long> e : sorted((Map<String, Long>) byTier)) {
                    line(sb, PREFIX + "alerts_escalated_tier", "tier=\"" + esc(e.getKey()) + "\"", e.getValue());
                }
            }
            if (alerts.get("ack_sla_by_tier") instanceof Map<?, ?> sla) {
                Map<String, Map<String, Long>> m = (Map<String, Map<String, Long>>) sla;
                help(sb, "alerts_ack_latency_avg_ms", "Mean ack latency by escalation tier (ms)", "gauge");
                for (Map.Entry<String, Map<String, Long>> e : sortedRoutes(m)) {
                    line(sb, PREFIX + "alerts_ack_latency_avg_ms", "tier=\"" + esc(e.getKey()) + "\"", e.getValue().get("avg_ms"));
                }
                help(sb, "alerts_ack_latency_max_ms", "Max ack latency by escalation tier (ms)", "gauge");
                for (Map.Entry<String, Map<String, Long>> e : sortedRoutes(m)) {
                    line(sb, PREFIX + "alerts_ack_latency_max_ms", "tier=\"" + esc(e.getKey()) + "\"", e.getValue().get("max_ms"));
                }
            }
            if (alerts.get("delivery_latency") instanceof Map<?, ?> dl) {
                Map<String, Object> h = (Map<String, Object>) dl;
                Object bk = h.get("buckets");
                if (bk instanceof Map<?, ?> buckets) {
                    help(sb, "alerts_delivery_latency_ms", "Webhook delivery round-trip latency (ms)", "histogram");
                    long cumulative = 0;
                    for (Map.Entry<String, Long> e : ((Map<String, Long>) buckets).entrySet()) {
                        cumulative += e.getValue() == null ? 0 : e.getValue();
                        String le = "+Inf".equals(e.getKey()) ? "+Inf" : e.getKey();
                        line(sb, PREFIX + "alerts_delivery_latency_ms_bucket", "le=\"" + esc(le) + "\"", cumulative);
                    }
                    Object sum = h.get("sum_ms");
                    Object cnt = h.get("count");
                    line(sb, PREFIX + "alerts_delivery_latency_ms_sum", null, sum instanceof Number ? ((Number) sum).longValue() : 0);
                    line(sb, PREFIX + "alerts_delivery_latency_ms_count", null, cnt instanceof Number ? ((Number) cnt).longValue() : 0);
                }
            }
            if (alerts.get("delivery_slo") instanceof Map<?, ?> slo) {
                Map<String, Object> m = (Map<String, Object>) slo;
                gaugeNum(sb, "alerts_slo_target", "Delivery-latency SLO target success ratio", m.get("target"));
                gaugeNum(sb, "alerts_slo_success_ratio", "Observed delivery success ratio within the SLO latency", m.get("success_ratio"));
                gaugeNum(sb, "alerts_slo_burn_rate", "Error-budget burn rate (>1 = over budget)", m.get("burn_rate"));
                gaugeNum(sb, "alerts_slo_total", "Timed deliveries counted toward the SLO", m.get("total"));
                gaugeNum(sb, "alerts_slo_good", "Deliveries within the SLO latency objective", m.get("good"));
            }
            if (alerts.get("selftest") instanceof Map<?, ?> st) {
                Map<String, Object> m = (Map<String, Object>) st;
                if (Boolean.TRUE.equals(m.get("ran"))) {
                    help(sb, "alerts_selftest_ok", "Last scheduled self-test result (1=ok, 0=failed)", "gauge");
                    line(sb, PREFIX + "alerts_selftest_ok", null, Boolean.TRUE.equals(m.get("ok")) ? 1 : 0);
                    Object lat = m.get("latency_ms");
                    if (lat instanceof Number n && n.longValue() >= 0) {
                        help(sb, "alerts_selftest_latency_ms", "Last scheduled self-test probe latency (ms)", "gauge");
                        line(sb, PREFIX + "alerts_selftest_latency_ms", null, n.longValue());
                    }
                }
            }
        }
        return sb.toString();
    }

    /** A single-line gauge for a numeric value (long or double), no labels. */
    private static void gaugeNum(StringBuilder sb, String name, String helpText, Object value) {
        help(sb, name, helpText, "gauge");
        line(sb, PREFIX + name, null, value instanceof Number ? value : 0);
    }

    private static List<Map.Entry<String, Long>> sorted(Map<String, Long> m) {
        List<Map.Entry<String, Long>> l = new java.util.ArrayList<>(m.entrySet());
        l.sort(Map.Entry.comparingByKey());
        return l;
    }

    private static List<Map.Entry<String, Map<String, Long>>> sortedRoutes(Map<String, Map<String, Long>> m) {
        List<Map.Entry<String, Map<String, Long>>> l = new java.util.ArrayList<>(m.entrySet());
        l.sort(Map.Entry.comparingByKey());
        return l;
    }

    private static void help(StringBuilder sb, String name, String help, String type) {
        sb.append("# HELP ").append(PREFIX).append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(PREFIX).append(name).append(' ').append(type).append('\n');
    }

    private static void gauge(StringBuilder sb, String name, String help, double v, String labels) {
        help(sb, name, help, "gauge");
        line(sb, PREFIX + name, labels, v);
    }

    private static void number(StringBuilder sb, String name, String help, String type, Object v) {
        if (!(v instanceof Number n)) return;
        help(sb, name, help, type);
        line(sb, PREFIX + name, "", n);
    }

    private static void line(StringBuilder sb, String metric, String labels, Object value) {
        sb.append(metric);
        if (labels != null && !labels.isEmpty()) sb.append('{').append(labels).append('}');
        double d = value instanceof Number n ? n.doubleValue() : 0;
        if (d == Math.rint(d) && !Double.isInfinite(d)) sb.append(' ').append((long) d).append('\n');
        else sb.append(' ').append(d).append('\n');
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
