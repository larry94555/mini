package com.example.imini;

import java.util.List;

/**
 * Renders the dead-letter backlog as a single self-contained HTML page from a list of
 * {@link AlertSink.DeadLetter}, mirroring {@link AuditDashboard}: a filter form (action/status/q), a table
 * with the row's id/age/attempts/last error/escalation tier and a payload snippet, inline ack/replay/delete
 * buttons (POST/DELETE forms to the admin endpoints), and a Prev/Next pager that preserves filters. All
 * caller-influenced values are HTML-escaped. Pure and dependency-free for easy testing; the endpoint serves
 * the string.
 */
public final class DeadLetterDashboard {

    private DeadLetterDashboard() {}

    public static String render(List<AlertSink.DeadLetter> rows, String action, String status, String q,
                                int offset, int limit, int total) {
        return render(rows, action, status, q, offset, limit, total, "", List.of());
    }

    public static String render(List<AlertSink.DeadLetter> rows, String action, String status, String q,
                                int offset, int limit, int total, String csrfToken,
                                List<AlertSink.DedupSummary> digests) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        sb.append("<title>imini — dead-letter alerts</title><style>");
        sb.append("body{font:14px/1.5 system-ui,sans-serif;margin:2rem;color:#1a1a1a;}");
        sb.append("h1{font-size:1.3rem;} .muted{color:#777;}");
        sb.append("form.filter{margin:1rem 0;display:flex;gap:.5rem;flex-wrap:wrap;align-items:end;}");
        sb.append("label{display:flex;flex-direction:column;font-size:.8rem;color:#555;gap:.2rem;}");
        sb.append("input{font:inherit;padding:.3rem .4rem;border:1px solid #ccc;border-radius:4px;}");
        sb.append("button{font:inherit;padding:.3rem .7rem;border:1px solid #3b7;background:#3b7;color:#fff;border-radius:4px;cursor:pointer;}");
        sb.append("button.danger{border-color:#c44;background:#c44;} button.ghost{border-color:#999;background:#fff;color:#333;}");
        sb.append(".pager{display:flex;gap:.5rem;align-items:center;margin:.5rem 0;}");
        sb.append(".pager a{padding:.3rem .7rem;border:1px solid #ccc;border-radius:4px;text-decoration:none;color:#1a1a1a;}");
        sb.append(".pager a.disabled{color:#bbb;border-color:#eee;pointer-events:none;}");
        sb.append("table{border-collapse:collapse;width:100%;margin:.5rem 0;}");
        sb.append("th,td{text-align:left;padding:.4rem .6rem;border-bottom:1px solid #ddd;vertical-align:top;}");
        sb.append("th{background:#f5f5f5;} td.time{white-space:nowrap;font-variant-numeric:tabular-nums;color:#555;}");
        sb.append("tr.acked{opacity:.55;} td.err{color:#a00;} form.act{display:inline;margin:0;}");
        sb.append(".bulk{margin:.5rem 0;display:flex;gap:.5rem;}");
        sb.append(".badge{display:inline-block;font-size:.7rem;background:#3b7;color:#fff;border-radius:3px;padding:0 .35rem;vertical-align:middle;}");
        sb.append("h2.sub{font-size:1.05rem;margin-top:1.5rem;}");
        sb.append("code{background:#f3f3f3;padding:0 .2rem;border-radius:3px;word-break:break-all;}");
        sb.append("td.payload{max-width:32rem;}");
        sb.append("</style></head><body>");
        sb.append("<h1>imini dead-letter alerts</h1>");

        sb.append("<form class=\"filter\" method=\"get\" action=\"/admin/alerts.html\">");
        sb.append("<label>action<input name=\"action\" value=\"").append(esc(action)).append("\"></label>");
        sb.append("<label>status<input name=\"status\" placeholder=\"failed|replaying\" value=\"").append(esc(status)).append("\"></label>");
        sb.append("<label>search payload<input name=\"q\" value=\"").append(esc(q)).append("\"></label>");
        sb.append("<label>page size<input name=\"limit\" type=\"number\" min=\"1\" value=\"").append(limit).append("\"></label>");
        sb.append("<button type=\"submit\">Filter</button>");
        sb.append("</form>");

        int shown = (rows == null) ? 0 : rows.size();
        int from = total == 0 ? 0 : offset + 1;
        int to = offset + shown;
        sb.append("<p class=\"muted\">Showing ").append(from).append("\u2013").append(to)
          .append(" of ").append(total).append(" matching dead-letter").append(total == 1 ? "" : "s")
          .append(". Newest first.</p>");

        // bulk actions over the current filter
        String fa = esc(action), fs = esc(status), fq = esc(q);
        String filterQs = "action=" + enc(action) + "&status=" + enc(status) + "&q=" + enc(q);
        sb.append("<div class=\"bulk\">");
        sb.append("<button type=\"button\" onclick=\"if(confirm('Replay ALL ").append(total)
          .append(" matching?'))act('post','/admin/alerts/replay-all?").append(filterQs).append("')\">Replay all matching</button> ");
        sb.append("<button type=\"button\" class=\"ghost\" onclick=\"if(confirm('Ack ALL matching?'))act('post','/admin/alerts/ack-all?")
          .append(filterQs).append("')\">Ack all matching</button>");
        sb.append("</div>");

        sb.append("<table><thead><tr>");
        sb.append("<th>age</th><th>action</th><th>status</th><th>tier</th><th>attempts</th>");
        sb.append("<th>last error</th><th>payload</th><th>actions</th>");
        sb.append("</tr></thead><tbody>");
        if (rows != null) {
            long now = System.currentTimeMillis();
            for (AlertSink.DeadLetter d : rows) {
                boolean acked = d.ackedAt() > 0;
                sb.append("<tr class=\"").append(acked ? "acked" : "").append("\">");
                sb.append("<td class=\"time\">").append(esc(humanAge(now - d.ts()))).append("</td>");
                sb.append("<td>").append(esc(d.action() == null ? "" : d.action())).append("</td>");
                sb.append("<td>").append(esc(d.status() == null ? "" : d.status()));
                if (acked) sb.append(" <span class=\"badge\">acked</span>");
                sb.append("</td>");
                sb.append("<td>").append(tierCell(d)).append("</td>");
                sb.append("<td>").append(d.attempts()).append("</td>");
                sb.append("<td class=\"err\">").append(esc(snippet(d.lastError(), 80))).append("</td>");
                sb.append("<td class=\"payload\"><code>").append(esc(snippet(d.payload(), 200))).append("</code></td>");
                sb.append("<td>");
                String id = d.id() == null ? "" : d.id();
                if (!id.isEmpty()) {
                    String jid = esc(id);
                    sb.append("<button type=\"button\" onclick=\"act('post','/admin/alerts/replay?id=").append(jid).append("')\">Replay</button> ");
                    if (!acked) sb.append("<button type=\"button\" class=\"ghost\" onclick=\"act('post','/admin/alerts/ack?id=").append(jid).append("')\">Ack</button> ");
                    sb.append("<button type=\"button\" class=\"danger\" onclick=\"if(confirm('Delete this dead-letter?'))act('delete','/admin/alerts/failed?id=").append(jid).append("')\">Delete</button>");
                }
                sb.append("</td></tr>");
            }
        }
        sb.append("</tbody></table>");

        int prevOffset = Math.max(0, offset - Math.max(1, limit));
        int nextOffset = offset + Math.max(1, limit);
        boolean hasPrev = offset > 0;
        boolean hasNext = to < total;
        sb.append("<div class=\"pager\">");
        sb.append("<a class=\"").append(hasPrev ? "" : "disabled").append("\" href=\"")
          .append(link(action, status, q, prevOffset, limit)).append("\">\u2190 Prev</a>");
        sb.append("<a class=\"").append(hasNext ? "" : "disabled").append("\" href=\"")
          .append(link(action, status, q, nextOffset, limit)).append("\">Next \u2192</a>");
        sb.append("</div>");

        // dedup-digest summary panel: which keys are currently being throttled the most
        if (digests != null && !digests.isEmpty()) {
            sb.append("<h2 class=\"sub\">Top suppressed keys (active dedup windows)</h2>");
            sb.append("<table><thead><tr><th>action</th><th>target</th><th>suppressed</th></tr></thead><tbody>");
            for (AlertSink.DedupSummary d : digests) {
                sb.append("<tr><td>").append(esc(d.action())).append("</td>");
                sb.append("<td>").append(esc(snippet(d.target(), 80))).append("</td>");
                sb.append("<td>").append(d.suppressed()).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }

        sb.append("<script>var CSRF=\"").append(esc(csrfToken == null ? "" : csrfToken)).append("\";</script>");
        sb.append(ACT_SCRIPT);
        sb.append("</body></html>");
        return sb.toString();
    }

    /** Build a viewer URL preserving filters, with the given offset/limit. */
    static String link(String action, String status, String q, int offset, int limit) {
        StringBuilder sb = new StringBuilder("/admin/alerts.html?");
        sb.append("action=").append(enc(action));
        sb.append("&status=").append(enc(status));
        sb.append("&q=").append(enc(q));
        sb.append("&offset=").append(Math.max(0, offset));
        sb.append("&limit=").append(limit);
        return sb.toString();
    }

    private static final String ACT_SCRIPT =
            "<script>function act(m,u){fetch(u,{method:m.toUpperCase(),headers:{'X-CSRF-Token':CSRF}})"
            + ".then(function(r){if(!r.ok)alert('Request failed: '+r.status);location.reload();})"
            + ".catch(function(e){alert('Request error: '+e);});}</script>";

    /** Pure: the escalation-tier cell — shows the tier reached, or "—" when not yet escalated. */
    static String tierCell(AlertSink.DeadLetter d) {
        int t = d.escalationTier();
        return t <= 0 ? "\u2014" : ("T" + t);
    }

    /** Pure: a coarse human-readable age from a millisecond duration. */
    static String humanAge(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 24) return h + "h";
        return (h / 24) + "d";
    }

    static String snippet(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\u2026";
    }

    private static String enc(String s) {
        if (s == null) return "";
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
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
