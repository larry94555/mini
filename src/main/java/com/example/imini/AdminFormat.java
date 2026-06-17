package com.example.imini;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure formatting/aggregation helpers for the admin dashboard. Kept dependency-free so the
 * human-readable shaping (uptime, top-N tallies, success rate) is deterministic and unit-testable; the
 * controller composes these over live data from {@link Metrics}, {@link RunService}, {@link AuditLog},
 * {@link ScheduledTasks}, and {@link PluginService}.
 */
public final class AdminFormat {

    private AdminFormat() {}

    /** Human-readable uptime, e.g. 90061000 -> "1d 1h 1m". Always shows at least seconds. */
    public static String humanizeUptime(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000, d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60, sec = s % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (d > 0 || h > 0) sb.append(h).append("h ");
        if (d > 0 || h > 0 || m > 0) sb.append(m).append("m ");
        sb.append(sec).append("s");
        return sb.toString().trim();
    }

    /** The top {@code n} entries of a count map, highest first (ties broken by name), as ordered pairs. */
    public static List<Map<String, Object>> topN(Map<String, Long> counts, int n) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (counts == null) return out;
        counts.entrySet().stream()
                .sorted((a, b) -> {
                    int c = Long.compare(b.getValue() == null ? 0 : b.getValue(),
                            a.getValue() == null ? 0 : a.getValue());
                    return c != 0 ? c : a.getKey().compareTo(b.getKey());
                })
                .limit(Math.max(0, n))
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", e.getKey());
                    row.put("count", e.getValue() == null ? 0L : e.getValue());
                    out.add(row);
                });
        return out;
    }

    /** Whole-number success percent from ok/failed counts; 0 when there are no runs. Range 0..100. */
    public static int successRate(long ok, long failed) {
        long total = ok + failed;
        if (total <= 0) return 0;
        return (int) Math.round(100.0 * ok / total);
    }
}
