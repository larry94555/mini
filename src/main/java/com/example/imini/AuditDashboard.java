package com.example.imini;

import java.util.List;

/**
 * Renders the audit-log viewer as a single self-contained HTML page from a list of {@link AuditLog.Entry}.
 * Like {@link UsageDashboard}, it is dependency-free (no template engine) and pure (entries + filter values
 * in, a string out) so it is unit-testable and the controller endpoint stays thin. Every dynamic value is
 * HTML-escaped, since users, targets, and outcomes are caller-influenced.
 *
 * <p>It surfaces the now-durable security events the rest of the stack writes — {@code capability_denied},
 * {@code spend_alert}, {@code tool_rate_limited}, and every other audited action — with a small filter form
 * (user / action / target) that round-trips to {@code GET /admin/audit.html} query params. Rows for denial,
 * alert, and rate-limit actions are visually highlighted.
 */
public final class AuditDashboard {

    private AuditDashboard() {}

    public static String render(List<AuditLog.Entry> entries, String user, String action, String target, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        sb.append("<title>imini — audit log</title><style>");
        sb.append("body{font:14px/1.5 system-ui,sans-serif;margin:2rem;color:#1a1a1a;}");
        sb.append("h1{font-size:1.3rem;} .muted{color:#777;}");
        sb.append("form{margin:1rem 0;display:flex;gap:.5rem;flex-wrap:wrap;align-items:end;}");
        sb.append("label{display:flex;flex-direction:column;font-size:.8rem;color:#555;gap:.2rem;}");
        sb.append("input{font:inherit;padding:.3rem .4rem;border:1px solid #ccc;border-radius:4px;}");
        sb.append("button{font:inherit;padding:.35rem .8rem;border:1px solid #3b7;background:#3b7;color:#fff;border-radius:4px;cursor:pointer;}");
        sb.append("table{border-collapse:collapse;width:100%;margin:.5rem 0;}");
        sb.append("th,td{text-align:left;padding:.4rem .6rem;border-bottom:1px solid #ddd;vertical-align:top;}");
        sb.append("th{background:#f5f5f5;} td.time{white-space:nowrap;font-variant-numeric:tabular-nums;color:#555;}");
        sb.append("tr.denied td.action,tr.alert td.action{color:#a00;font-weight:600;}");
        sb.append("code{background:#f3f3f3;padding:0 .2rem;border-radius:3px;}");
        sb.append("</style></head><body>");
        sb.append("<h1>imini audit log</h1>");

        // filter form (GET, so it round-trips via query params)
        sb.append("<form method=\"get\" action=\"/admin/audit.html\">");
        sb.append("<label>user<input name=\"user\" value=\"").append(esc(user)).append("\"></label>");
        sb.append("<label>action<input name=\"action\" value=\"").append(esc(action)).append("\"></label>");
        sb.append("<label>target<input name=\"target\" value=\"").append(esc(target)).append("\"></label>");
        sb.append("<label>limit<input name=\"limit\" type=\"number\" min=\"1\" value=\"").append(limit).append("\"></label>");
        sb.append("<button type=\"submit\">Filter</button>");
        sb.append("</form>");

        int shown = (entries == null) ? 0 : entries.size();
        sb.append("<p class=\"muted\">Showing ").append(shown).append(" most recent matching ")
          .append(shown == 1 ? "entry" : "entries").append(". Newest first.</p>");

        sb.append("<table><thead><tr><th>time</th><th>user</th><th>action</th><th>target</th><th>outcome</th></tr></thead><tbody>");
        if (entries != null) {
            for (AuditLog.Entry e : entries) {
                String act = e.action() == null ? "" : e.action();
                String cls = ("capability_denied".equals(act) || "tool_rate_limited".equals(act)) ? " class=\"denied\""
                        : "spend_alert".equals(act) ? " class=\"alert\"" : "";
                sb.append("<tr").append(cls).append(">")
                  .append("<td class=\"time\">").append(esc(e.time())).append("</td>")
                  .append("<td>").append(esc(e.user())).append("</td>")
                  .append("<td class=\"action\">").append(esc(act)).append("</td>")
                  .append("<td>").append(esc(e.target())).append("</td>")
                  .append("<td>").append(esc(e.outcome())).append("</td>")
                  .append("</tr>");
            }
        }
        if (shown == 0) {
            sb.append("<tr><td colspan=\"5\" class=\"muted\">No matching audit entries.</td></tr>");
        }
        sb.append("</tbody></table>");
        sb.append("<p class=\"muted\">Raw JSON at <code>/audit</code>; CSV/JSON export at <code>/audit/export</code>.</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /** Minimal HTML escaping for text nodes and quoted attribute values. */
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
