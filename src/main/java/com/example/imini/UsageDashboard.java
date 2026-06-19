package com.example.imini;

import java.util.List;
import java.util.Map;

/**
 * Renders the per-tenant usage dashboard as a single self-contained HTML page from a
 * {@link CostService#summary()} map. Kept dependency-free (no template engine) and pure (a map in, a
 * string out) so it is unit-testable and the controller endpoint stays a one-liner. All dynamic values
 * are HTML-escaped, since tenant names ultimately come from caller-controlled identity config.
 */
public final class UsageDashboard {

    private UsageDashboard() {}

    @SuppressWarnings("unchecked")
    public static String render(Map<String, Object> summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        sb.append("<title>imini — usage</title><style>");
        sb.append("body{font:14px/1.5 system-ui,sans-serif;margin:2rem;color:#1a1a1a;}");
        sb.append("h1{font-size:1.3rem;} h2{font-size:1rem;margin-top:1.5rem;}");
        sb.append("table{border-collapse:collapse;width:100%;margin:.5rem 0;}");
        sb.append("th,td{text-align:left;padding:.4rem .6rem;border-bottom:1px solid #ddd;}");
        sb.append("th{background:#f5f5f5;} td.num{text-align:right;font-variant-numeric:tabular-nums;}");
        sb.append(".alert{color:#a00;} .muted{color:#777;} .bar{height:.5rem;background:#e5e5e5;border-radius:3px;overflow:hidden;}");
        sb.append(".bar>span{display:block;height:100%;background:#3b7;}");
        sb.append("</style></head><body>");
        sb.append("<h1>imini usage — current month</h1>");

        boolean enabled = Boolean.TRUE.equals(summary.get("enabled"));
        if (!enabled) {
            sb.append("<p class=\"muted\">Cost accounting is disabled (<code>cost.enabled=false</code>).</p>");
        }
        long defaultQuota = asLong(summary.get("monthlyTokenQuota"));
        sb.append("<p class=\"muted\">Default monthly token quota: ")
          .append(defaultQuota > 0 ? String.valueOf(defaultQuota) : "unlimited").append("</p>");

        sb.append("<h2>Per-tenant usage</h2>");
        List<Map<String, Object>> tenants = (List<Map<String, Object>>) summary.getOrDefault("tenants", List.of());
        if (tenants.isEmpty()) {
            sb.append("<p class=\"muted\">No usage recorded yet this month.</p>");
        } else {
            sb.append("<table><thead><tr><th>Tenant</th><th>Input</th><th>Output</th><th>Total tokens</th>")
              .append("<th>USD</th><th>Runs</th><th>Quota use</th></tr></thead><tbody>");
            for (Map<String, Object> t : tenants) {
                long total = asLong(t.get("totalTokens"));
                double usd = asDouble(t.get("usd"));
                long runs = asLong(t.get("runs"));
                String pctCell;
                if (defaultQuota > 0) {
                    long pct = Math.min(100, Math.round(total * 100.0 / defaultQuota));
                    pctCell = "<div class=\"bar\"><span style=\"width:" + pct + "%\"></span></div>"
                            + "<span class=\"muted\">" + pct + "%</span>";
                } else {
                    pctCell = "<span class=\"muted\">—</span>";
                }
                sb.append("<tr><td>").append(esc(String.valueOf(t.get("tenant")))).append("</td>")
                  .append("<td class=\"num\">").append(asLong(t.get("inputTokens"))).append("</td>")
                  .append("<td class=\"num\">").append(asLong(t.get("outputTokens"))).append("</td>")
                  .append("<td class=\"num\">").append(total).append("</td>")
                  .append("<td class=\"num\">").append(String.format(java.util.Locale.ROOT, "%.4f", usd)).append("</td>")
                  .append("<td class=\"num\">").append(runs).append("</td>")
                  .append("<td>").append(pctCell).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }

        List<Map<String, Object>> alerts = (List<Map<String, Object>>) summary.getOrDefault("alerts", List.of());
        sb.append("<h2>Spend alerts</h2>");
        if (alerts.isEmpty()) {
            sb.append("<p class=\"muted\">No alerts.</p>");
        } else {
            sb.append("<table><thead><tr><th>Tenant</th><th>Tokens</th><th>Threshold</th><th>% of quota</th></tr></thead><tbody>");
            for (Map<String, Object> a : alerts) {
                sb.append("<tr class=\"alert\"><td>").append(esc(String.valueOf(a.get("tenant")))).append("</td>")
                  .append("<td class=\"num\">").append(asLong(a.get("tokens"))).append("</td>")
                  .append("<td class=\"num\">").append(asLong(a.get("threshold"))).append("</td>")
                  .append("<td class=\"num\">").append(a.get("percentOfQuota") == null ? "—" : a.get("percentOfQuota") + "%")
                  .append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }

        sb.append("<p class=\"muted\">Raw JSON: <a href=\"/admin/cost\">/admin/cost</a></p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    static long asLong(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
    static double asDouble(Object o) { return o instanceof Number n ? n.doubleValue() : 0.0; }

    /** Minimal HTML escaping for text nodes / attribute-free content. */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
