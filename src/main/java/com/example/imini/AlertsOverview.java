package com.example.imini;

import java.util.List;
import java.util.Map;

/**
 * Renders a single operator overview of the alerting pipeline from the {@link AlertSink#stats()} map (plus the
 * dedup summary): top-line delivery counters, per-route breakdown, per-tier escalation counts with ack-SLA
 * latency, and the most-suppressed dedup keys. Pure and dependency-free so it can be unit-tested; the endpoint
 * just serves the string. Numbers are read defensively (missing keys render as 0/empty).
 */
public final class AlertsOverview {

    private AlertsOverview() {}

    @SuppressWarnings("unchecked")
    public static String render(Map<String, Object> stats, List<AlertSink.DedupSummary> digests) {
        if (stats == null) stats = Map.of();
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        sb.append("<title>imini — alerting overview</title><style>");
        sb.append("body{font:14px/1.5 system-ui,sans-serif;margin:2rem;color:#1a1a1a;}");
        sb.append("h1{font-size:1.3rem;} h2{font-size:1.05rem;margin-top:1.5rem;} .muted{color:#777;}");
        sb.append(".cards{display:flex;flex-wrap:wrap;gap:.6rem;margin:1rem 0;}");
        sb.append(".card{border:1px solid #ddd;border-radius:6px;padding:.5rem .8rem;min-width:6rem;}");
        sb.append(".card .n{font-size:1.4rem;font-weight:600;font-variant-numeric:tabular-nums;}");
        sb.append(".card .l{font-size:.75rem;color:#666;text-transform:uppercase;letter-spacing:.03em;}");
        sb.append(".card.warn .n{color:#a00;}");
        sb.append("table{border-collapse:collapse;width:100%;margin:.5rem 0;}");
        sb.append("th,td{text-align:left;padding:.4rem .6rem;border-bottom:1px solid #ddd;}");
        sb.append("th{background:#f5f5f5;} td.num{text-align:right;font-variant-numeric:tabular-nums;}");
        sb.append("a.nav{margin-right:1rem;}");
        sb.append("</style></head><body>");
        sb.append("<h1>imini alerting overview</h1>");
        sb.append("<p class=\"muted\"><a class=\"nav\" href=\"/admin/alerts.html\">Dead-letter viewer \u2192</a>");
        sb.append("<a class=\"nav\" href=\"/metrics/prom\">Prometheus \u2192</a></p>");

        // top-line counters
        sb.append("<div class=\"cards\">");
        card(sb, "queued", num(stats, "queued"), false);
        card(sb, "sent", num(stats, "sent"), false);
        card(sb, "failed", num(stats, "failed"), num(stats, "failed") > 0);
        card(sb, "retried", num(stats, "retried"), false);
        card(sb, "dead-lettered", num(stats, "dead_lettered"), num(stats, "dead_lettered") > 0);
        card(sb, "dropped", num(stats, "dropped"), num(stats, "dropped") > 0);
        card(sb, "replayed", num(stats, "replayed"), false);
        card(sb, "suppressed", num(stats, "suppressed"), false);
        card(sb, "escalated", num(stats, "escalated"), num(stats, "escalated") > 0);
        card(sb, "sla breaches", num(stats, "sla_breaches"), num(stats, "sla_breaches") > 0);
        card(sb, "digested", num(stats, "digested"), false);
        card(sb, "in flight", num(stats, "in_flight"), false);
        card(sb, "backlog", num(stats, "dead_letter_size"), num(stats, "dead_letter_size") > 0);
        sb.append("</div>");

        // per-route
        Object byRoute = stats.get("by_route");
        if (byRoute instanceof Map<?, ?> br && !br.isEmpty()) {
            sb.append("<h2>By route</h2><table><thead><tr><th>route</th>"
                    + "<th class=\"num\">sent</th><th class=\"num\">failed</th>"
                    + "<th class=\"num\">dead-lettered</th><th class=\"num\">suppressed</th></tr></thead><tbody>");
            for (Map.Entry<String, Map<String, Long>> e : ((Map<String, Map<String, Long>>) br).entrySet()) {
                Map<String, Long> r = e.getValue();
                sb.append("<tr><td>").append(esc(e.getKey())).append("</td>");
                sb.append("<td class=\"num\">").append(val(r, "sent")).append("</td>");
                sb.append("<td class=\"num\">").append(val(r, "failed")).append("</td>");
                sb.append("<td class=\"num\">").append(val(r, "dead_lettered")).append("</td>");
                sb.append("<td class=\"num\">").append(val(r, "suppressed")).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }

        // per-tier escalations + ack-SLA
        Object byTier = stats.get("by_tier");
        Object sla = stats.get("ack_sla_by_tier");
        Map<String, Long> tiers = (byTier instanceof Map) ? (Map<String, Long>) byTier : Map.of();
        Map<String, Map<String, Long>> slaMap = (sla instanceof Map) ? (Map<String, Map<String, Long>>) sla : Map.of();
        if (!tiers.isEmpty() || !slaMap.isEmpty()) {
            sb.append("<h2>Escalation tiers</h2><table><thead><tr><th>tier</th>"
                    + "<th class=\"num\">paged</th><th class=\"num\">acked</th>"
                    + "<th class=\"num\">avg ack</th><th class=\"num\">max ack</th></tr></thead><tbody>");
            java.util.TreeSet<String> keys = new java.util.TreeSet<>();
            keys.addAll(tiers.keySet());
            keys.addAll(slaMap.keySet());
            for (String t : keys) {
                Map<String, Long> s = slaMap.getOrDefault(t, Map.of());
                sb.append("<tr><td>T").append(esc(t)).append("</td>");
                sb.append("<td class=\"num\">").append(tiers.getOrDefault(t, 0L)).append("</td>");
                sb.append("<td class=\"num\">").append(val(s, "count")).append("</td>");
                sb.append("<td class=\"num\">").append(humanMs(val(s, "avg_ms"))).append("</td>");
                sb.append("<td class=\"num\">").append(humanMs(val(s, "max_ms"))).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }

        // top suppressed keys
        if (digests != null && !digests.isEmpty()) {
            sb.append("<h2>Top suppressed keys</h2><table><thead><tr><th>action</th><th>target</th>"
                    + "<th class=\"num\">suppressed</th></tr></thead><tbody>");
            for (AlertSink.DedupSummary d : digests) {
                sb.append("<tr><td>").append(esc(d.action())).append("</td>");
                sb.append("<td>").append(esc(snippet(d.target(), 80))).append("</td>");
                sb.append("<td class=\"num\">").append(d.suppressed()).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    private static void card(StringBuilder sb, String label, long n, boolean warn) {
        sb.append("<div class=\"card").append(warn ? " warn" : "").append("\">");
        sb.append("<div class=\"n\">").append(n).append("</div>");
        sb.append("<div class=\"l\">").append(esc(label)).append("</div></div>");
    }

    private static long num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return (v instanceof Number) ? ((Number) v).longValue() : 0L;
    }

    private static long val(Map<String, Long> m, String k) {
        Long v = m == null ? null : m.get(k);
        return v == null ? 0L : v;
    }

    /** Pure: render a millisecond duration compactly (ms/s/m/h), or "—" for 0. */
    static String humanMs(long ms) {
        if (ms <= 0) return "\u2014";
        if (ms < 1000) return ms + "ms";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        return (m / 60) + "h";
    }

    static String snippet(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\u2026";
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
