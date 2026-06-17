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
        if (snapshot.get("run_latency") instanceof Map<?, ?> lat) {
            number(sb, "run_latency_count", "Completed runs measured for latency", "gauge", lat.get("count"));
            number(sb, "run_latency_avg_ms", "Average run latency (ms)", "gauge", lat.get("avg_ms"));
            number(sb, "run_latency_max_ms", "Max run latency (ms)", "gauge", lat.get("max_ms"));
        }
        if (snapshot.get("approx_output_tokens") instanceof Number tok) {
            gauge(sb, "approx_output_tokens", "Approximate model output tokens", tok.doubleValue(), "");
        }
        if (snapshot.get("concurrency") instanceof Map<?, ?> conc) {
            number(sb, "concurrency_limit", "Max concurrent runs", "gauge", conc.get("limit"));
            number(sb, "concurrency_active", "Active runs", "gauge", conc.get("active"));
            number(sb, "concurrency_queued", "Queued runs", "gauge", conc.get("queued"));
        }
        return sb.toString();
    }

    private static List<Map.Entry<String, Long>> sorted(Map<String, Long> m) {
        List<Map.Entry<String, Long>> l = new java.util.ArrayList<>(m.entrySet());
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
