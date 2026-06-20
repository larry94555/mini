package com.example.imini;

import java.util.List;
import java.util.Map;

/**
 * Renders a single operator overview of the alerting pipeline from the {@link AlertSink#stats()} map (plus the
 * dedup summary): top-line delivery counter cards, a per-route table, a per-tier table combining escalation
 * counts with ack-SLA latency, and the most-suppressed dedup keys. When {@code autoRefreshSeconds > 0} the page
 * polls {@code /admin/alerts/overview.json} and live-updates the cards and tables in place (for a wall display).
 * Pure and dependency-free so it can be unit-tested; the endpoint just serves the string. Numbers are read
 * defensively (missing keys render as 0/empty).
 */
public final class AlertsOverview {

    private AlertsOverview() {}

    /** Counter cards, in display order: {statsKey, label}. */
    private static final String[][] CARDS = {
            {"queued", "queued"}, {"sent", "sent"}, {"failed", "failed"}, {"retried", "retried"},
            {"dead_lettered", "dead-lettered"}, {"dropped", "dropped"}, {"replayed", "replayed"},
            {"suppressed", "suppressed"}, {"escalated", "escalated"}, {"sla_breaches", "sla breaches"},
            {"digested", "digested"}, {"in_flight", "in flight"}, {"dead_letter_size", "backlog"}};

    /** Stats keys whose non-zero value should render in the warning colour. */
    private static final java.util.Set<String> WARN_KEYS = java.util.Set.of(
            "failed", "dead_lettered", "dropped", "escalated", "sla_breaches", "dead_letter_size");

    public static String render(Map<String, Object> stats, List<AlertSink.DedupSummary> digests) {
        return render(stats, digests, 0);
    }

    @SuppressWarnings("unchecked")
    public static String render(Map<String, Object> stats, List<AlertSink.DedupSummary> digests,
                                int autoRefreshSeconds) {
        if (stats == null) stats = Map.of();
        boolean live = autoRefreshSeconds > 0;
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
        sb.append("<a class=\"nav\" href=\"/admin/alerts/config\">Effective config \u2192</a>");
        sb.append("<a class=\"nav\" href=\"/metrics/prom\">Prometheus \u2192</a>");
        if (live) {
            sb.append("<span id=\"refreshnote\">Auto-refresh every ").append(autoRefreshSeconds)
              .append("s \u00b7 <span id=\"updated\">loading\u2026</span> \u00b7 ");
            sb.append("<a href=\"#\" id=\"toggle\" onclick=\"return toggleRefresh()\">pause</a></span>");
        }
        sb.append("</p>");

        // top-line counters
        sb.append("<div class=\"cards\">");
        for (String[] c : CARDS) {
            long n = num(stats, c[0]);
            card(sb, c[0], c[1], n, WARN_KEYS.contains(c[0]) && n > 0);
        }
        sb.append("</div>");

        // SLO summary (latency cumulative + rolling window, delivery-success), with live-updatable ids
        sb.append("<h2>SLO</h2><div class=\"cards\">");
        sloCard(sb, "slo_ratio", "latency success", pct(slo(stats, "delivery_slo", "success_ratio")));
        sloCard(sb, "slo_budget", "budget left", pct(slo(stats, "delivery_slo", "budget_remaining")));
        sloCard(sb, "slo_win_budget", "30d budget left", pct(slo(stats, "delivery_slo_window", "budget_remaining")));
        sloCard(sb, "succ_ratio", "delivery success", pct(slo(stats, "delivery_success_slo", "success_ratio")));
        sloCard(sb, "succ_budget", "success budget", pct(slo(stats, "delivery_success_slo", "budget_remaining")));
        sb.append("</div>");

        // per-route
        Object byRoute = stats.get("by_route");
        Map<String, Map<String, Long>> routes = (byRoute instanceof Map) ? (Map<String, Map<String, Long>>) byRoute : Map.of();
        if (live || !routes.isEmpty()) {
            sb.append("<h2>By route</h2><table><thead><tr><th>route</th>"
                    + "<th class=\"num\">sent</th><th class=\"num\">failed</th>"
                    + "<th class=\"num\">dead-lettered</th><th class=\"num\">suppressed</th></tr></thead>");
            sb.append("<tbody id=\"rt_body\">");
            for (Map.Entry<String, Map<String, Long>> e : routes.entrySet()) {
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
        if (live || !tiers.isEmpty() || !slaMap.isEmpty()) {
            sb.append("<h2>Escalation tiers</h2><table><thead><tr><th>tier</th>"
                    + "<th class=\"num\">paged</th><th class=\"num\">acked</th>"
                    + "<th class=\"num\">avg ack</th><th class=\"num\">max ack</th></tr></thead>");
            sb.append("<tbody id=\"tier_body\">");
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
        if (live || (digests != null && !digests.isEmpty())) {
            sb.append("<h2>Top suppressed keys</h2><table><thead><tr><th>action</th><th>target</th>"
                    + "<th class=\"num\">suppressed</th></tr></thead>");
            sb.append("<tbody id=\"sup_body\">");
            if (digests != null) {
                for (AlertSink.DedupSummary d : digests) {
                    sb.append("<tr><td>").append(esc(d.action())).append("</td>");
                    sb.append("<td>").append(esc(snippet(d.target(), 80))).append("</td>");
                    sb.append("<td class=\"num\">").append(d.suppressed()).append("</td></tr>");
                }
            }
            sb.append("</tbody></table>");
        }

        if (live) sb.append(liveScript(autoRefreshSeconds));
        sb.append("</body></html>");
        return sb.toString();
    }

    /** The polling/auto-refresh script (re-renders cards and table bodies from the JSON endpoint). */
    private static String liveScript(int seconds) {
        return "<script>"
            + "var REFRESH=" + seconds + ",ON=true;"
            + "function esc(s){return String(s==null?'':s).replace(/[&<>\"']/g,function(c){"
            + "return{'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c];});}"
            + "function fmtMs(ms){ms=+ms||0;if(ms<=0)return '\\u2014';if(ms<1000)return ms+'ms';"
            + "var s=Math.floor(ms/1000);if(s<60)return s+'s';var m=Math.floor(s/60);"
            + "if(m<60)return m+'m';return Math.floor(m/60)+'h';}"
            + "function setCard(k,v){var e=document.getElementById('c_'+k);if(e)e.textContent=v;}"
            + "function rows(id,h){var b=document.getElementById(id);if(b)b.innerHTML=h;}"
            + "function rebuild(d){var s=d.stats||{};"
            + "['queued','sent','failed','retried','dead_lettered','dropped','replayed','suppressed',"
            + "'escalated','sla_breaches','digested','in_flight','dead_letter_size'].forEach(function(k){"
            + "setCard(k,(s[k]||0));});"
            + "function pct(v){return (v==null||isNaN(v))?'\\u2014':(Math.round(v*1000)/10)+'%';}"
            + "function setS(id,v){var e=document.getElementById('s_'+id);if(e)e.textContent=pct(v);}"
            + "var dl=s.delivery_slo||{},dw=s.delivery_slo_window||{},ds=s.delivery_success_slo||{};"
            + "setS('slo_ratio',dl.success_ratio);setS('slo_budget',dl.budget_remaining);"
            + "setS('slo_win_budget',dw.budget_remaining);setS('succ_ratio',ds.success_ratio);"
            + "setS('succ_budget',ds.budget_remaining);"
            + "var br=s.by_route||{},h='';Object.keys(br).forEach(function(k){var r=br[k]||{};"
            + "h+='<tr><td>'+esc(k)+'</td><td class=\"num\">'+(r.sent||0)+'</td><td class=\"num\">'+(r.failed||0)"
            + "+'</td><td class=\"num\">'+(r.dead_lettered||0)+'</td><td class=\"num\">'+(r.suppressed||0)+'</td></tr>';});"
            + "rows('rt_body',h);"
            + "var bt=s.by_tier||{},sl=s.ack_sla_by_tier||{},ks={};Object.keys(bt).forEach(function(k){ks[k]=1;});"
            + "Object.keys(sl).forEach(function(k){ks[k]=1;});h='';Object.keys(ks).sort().forEach(function(t){"
            + "var m=sl[t]||{};h+='<tr><td>T'+esc(t)+'</td><td class=\"num\">'+(bt[t]||0)+'</td><td class=\"num\">'"
            + "+(m.count||0)+'</td><td class=\"num\">'+fmtMs(m.avg_ms)+'</td><td class=\"num\">'+fmtMs(m.max_ms)+'</td></tr>';});"
            + "rows('tier_body',h);"
            + "var dg=d.digests||[];h='';dg.forEach(function(x){h+='<tr><td>'+esc(x.action)+'</td><td>'+esc(x.target)"
            + "+'</td><td class=\"num\">'+(x.suppressed||0)+'</td></tr>';});rows('sup_body',h);"
            + "var u=document.getElementById('updated');if(u)u.textContent='updated '+new Date().toLocaleTimeString();}"
            + "function poll(){if(!ON)return;fetch('/admin/alerts/overview.json',{headers:{}}).then(function(r){"
            + "return r.json();}).then(rebuild).catch(function(){});}"
            + "function toggleRefresh(){ON=!ON;var t=document.getElementById('toggle');if(t)t.textContent=ON?'pause':'resume';"
            + "if(ON)poll();return false;}"
            + "poll();if(REFRESH>0)setInterval(poll,REFRESH*1000);"
            + "</script>";
    }

    /** An SLO summary card; the value span carries id {@code s_<key>} so the poller can live-update it. */
    private static void sloCard(StringBuilder sb, String key, String label, String value) {
        sb.append("<div class=\"card\"><div class=\"n\" id=\"s_").append(esc(key)).append("\">")
          .append(esc(value)).append("</div><div class=\"l\">").append(esc(label)).append("</div></div>");
    }

    /** A nested SLO field from stats (e.g. delivery_slo.success_ratio); NaN if absent. */
    private static double slo(Map<String, Object> stats, String block, String field) {
        Object b = stats.get(block);
        if (b instanceof Map<?, ?> m && m.get(field) instanceof Number n) return n.doubleValue();
        return Double.NaN;
    }

    /** Format a ratio as a percentage string (no trailing .0 for whole numbers); "—" for NaN. */
    static String pct(double r) {
        if (Double.isNaN(r)) return "\u2014";
        double p = Math.round(r * 1000.0) / 10.0;
        String num = (p == Math.rint(p)) ? Long.toString((long) p) : Double.toString(p);
        return num + "%";
    }

    private static void card(StringBuilder sb, String key, String label, long n, boolean warn) {
        sb.append("<div class=\"card").append(warn ? " warn" : "").append("\">");
        sb.append("<div class=\"n\" id=\"c_").append(esc(key)).append("\">").append(n).append("</div>");
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
