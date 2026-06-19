package com.example.imini;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Forwards selected audit events to an external notification channel, so security-relevant events
 * ({@code capability_denied}, {@code spend_alert}, {@code tool_rate_limited}, and anything else configured)
 * can page an operator instead of only being browsable at {@code /admin/audit.html}.
 *
 * <p>It registers as an {@link AuditLog} listener at startup. For each recorded entry whose action is in the
 * configured set it (a) logs a {@code WARN} line and (b) if a webhook is resolved for that action, enqueues a
 * POST.
 *
 * <h2>Delivery buffer, retry &amp; durable dead-letter</h2>
 * Delivery is dispatched on a background scheduler; a failed POST is retried up to {@code alerts.max-retries}
 * times with exponential backoff. An alert that exhausts its retries is dead-lettered — durably in the
 * {@code alerts_dead_letter} SQLite table (or a bounded in-memory ring when no database). Dead-letters are
 * inspectable at {@code GET /admin/alerts/failed} and replayable via {@code POST /admin/alerts/replay}.
 *
 * <h3>Replay safety</h3>
 * Replay does not delete-then-resend. It marks a row {@code replaying} (keeping it), and only deletes it on a
 * confirmed 2xx; if the replay exhausts its retries the row is restored to {@code failed} with its attempt
 * count and last error updated (retry history). Rows stuck in {@code replaying} after a crash are reset to
 * {@code failed} at startup, so an alert is never lost mid-replay.
 *
 * <h2>Per-action routing</h2>
 * {@code alerts.routes} maps an action to its own webhook (and optionally template), so e.g. {@code spend_alert}
 * can page a finance channel while {@code capability_denied} pages security. Actions without a route fall back
 * to {@code alerts.webhook-url} and {@code alerts.template}.
 *
 * <h2>Templates</h2>
 * {@code alerts.template} (or a route's template) shapes the payload via {@code {ts} {time} {user} {action}
 * {target} {outcome}} placeholders (string fields JSON-escaped). {@link #validateTemplate} flags issues and
 * {@code POST /admin/alerts/test} previews/sends a sample. Off by default.
 */
@Component
public class AlertSink {

    private static final Logger log = LoggerFactory.getLogger(AlertSink.class);

    private static final Set<String> KNOWN_PLACEHOLDERS =
            Set.of("ts", "time", "user", "action", "target", "outcome");

    @Value("${alerts.enabled:false}") private boolean enabled;
    @Value("${alerts.webhook-url:}") private String webhookUrl;
    @Value("${alerts.actions:capability_denied,spend_alert,tool_rate_limited}") private String actionsCfg;
    @Value("${alerts.max-retries:3}") private int maxRetries;
    @Value("${alerts.retry-backoff-ms:500}") private long retryBackoffMs;
    @Value("${alerts.queue-capacity:1000}") private int queueCapacity;
    @Value("${alerts.dead-letter-capacity:100}") private int deadLetterCapacity;
    @Value("${alerts.template:}") private String template;
    @Value("${alerts.dead-letter-persistent:true}") private boolean deadLetterPersistent;
    @Value("${alerts.routes:}") private String routesCfg;

    private final AuditLog audit;
    private final Database db;
    private Set<String> actions = Set.of();
    private Map<String, Route> routes = Map.of();

    private HttpClient http;
    private ScheduledExecutorService scheduler;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final Deque<String> deadLetter = new ArrayDeque<>(); // in-memory fallback (no DB)
    private final Object dlLock = new Object();

    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong retried = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong replayed = new AtomicLong();

    /** A per-action route: a webhook URL and an optional template (null = use the global template). */
    public record Route(String url, String template) {}

    /** One unit of delivery work: payload + destination, current attempt, and the dead-letter row id (or null). */
    private record Delivery(String payload, String url, int attemptNo, String dlId) {}

    /** A dead-lettered delivery row (durable rows carry an id/status/history; memory rows are payload-only). */
    public record DeadLetter(String id, long ts, String payload, String url, int attempts,
                             String lastError, String status, long lastAttemptAt) {}

    public AlertSink(AuditLog audit, Database db) {
        this.audit = audit;
        this.db = db;
    }

    boolean dlPersistent() {
        return deadLetterPersistent && db != null && db.available();
    }

    public boolean enabled() { return enabled; }

    @jakarta.annotation.PostConstruct
    public void init() {
        this.actions = parseActions(actionsCfg);
        this.routes = parseRoutes(routesCfg);
        if (enabled) {
            this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            this.scheduler = Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "alert-sink");
                t.setDaemon(true);
                return t;
            });
            audit.addListener(this::onEntry);
            // crash recovery: any row left mid-replay becomes replayable again
            if (dlPersistent()) {
                try {
                    int reset = db.update("UPDATE alerts_dead_letter SET status='failed' WHERE status='replaying'");
                    if (reset > 0) log.info("[alerts] reset " + reset + " stuck 'replaying' dead-letter(s) to 'failed'");
                } catch (Exception ex) {
                    log.warn("[alerts] could not reset replaying dead-letters: " + ex.getMessage());
                }
            }
            log.info("[alerts] enabled for actions " + actions + " routes=" + routes.keySet()
                    + (webhookUrl != null && !webhookUrl.isBlank() || !routes.isEmpty()
                        ? " -> webhook (retries=" + maxRetries + ")" : " (log only)"));
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    /** Pure: parse a comma-separated action list into a set (trimmed, non-empty). */
    static Set<String> parseActions(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) return out;
        for (String a : csv.split(",")) {
            String t = a.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * Pure: parse routes. Routes are separated by {@code ;;}; each is {@code action|url|template} where the
     * template (which may contain anything but {@code ;;}) is optional. Pipes split the first two fields only,
     * so a URL with no pipe and a template are handled. Malformed routes (missing action/url) are skipped.
     */
    static Map<String, Route> parseRoutes(String cfg) {
        Map<String, Route> out = new LinkedHashMap<>();
        if (cfg == null || cfg.isBlank()) return out;
        for (String entry : cfg.split(";;")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            int p1 = e.indexOf('|');
            if (p1 <= 0) continue;
            String action = e.substring(0, p1).trim();
            String rest = e.substring(p1 + 1);
            int p2 = rest.indexOf('|');
            String url = (p2 < 0 ? rest : rest.substring(0, p2)).trim();
            String tmpl = (p2 < 0 ? null : rest.substring(p2 + 1));
            if (action.isEmpty() || url.isEmpty()) continue;
            out.put(action, new Route(url, (tmpl == null || tmpl.isBlank()) ? null : tmpl));
        }
        return out;
    }

    /** Pure: should an entry with this action be forwarded, given current config? */
    boolean shouldForward(String action) {
        return enabled && action != null && actions.contains(action);
    }

    /** Resolve the webhook URL for an action: the route's URL, else the default. Blank if neither set. */
    String urlFor(String action) {
        Route r = routes.get(action);
        if (r != null && r.url() != null && !r.url().isBlank()) return r.url();
        return webhookUrl;
    }

    /** Resolve the template for an action: the route's template, else the global one (may be blank). */
    String templateFor(String action) {
        Route r = routes.get(action);
        if (r != null && r.template() != null && !r.template().isBlank()) return r.template();
        return template;
    }

    /** Pure: backoff delay (ms) before the given retry attempt (1-based), capped at 60s. */
    static long backoffMs(int attempt, long base) {
        long b = base <= 0 ? 1 : base;
        long delay = b * (1L << Math.min(Math.max(attempt - 1, 0), 16));
        return Math.min(delay, 60_000L);
    }

    /** Pure: build the built-in JSON payload. */
    static String toJson(AuditLog.Entry e) {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        sb.append("\"ts\":").append(e.ts()).append(',');
        sb.append("\"time\":\"").append(esc(e.time())).append("\",");
        sb.append("\"user\":\"").append(esc(e.user())).append("\",");
        sb.append("\"action\":\"").append(esc(e.action())).append("\",");
        sb.append("\"target\":\"").append(esc(e.target())).append("\",");
        sb.append("\"outcome\":\"").append(esc(e.outcome())).append("\"");
        sb.append('}');
        return sb.toString();
    }

    /** Pure: substitute placeholders ({ts} numeric; string fields JSON-escaped); unknown ones left intact. */
    static String applyTemplate(String template, AuditLog.Entry e) {
        if (template == null) return "";
        return template
                .replace("{ts}", Long.toString(e.ts()))
                .replace("{time}", esc(e.time()))
                .replace("{user}", esc(e.user()))
                .replace("{action}", esc(e.action()))
                .replace("{target}", esc(e.target()))
                .replace("{outcome}", esc(e.outcome()));
    }

    /**
     * Pure: validate a template, returning human-readable issues (empty = OK). Flags unknown {@code {word}}
     * placeholders, unbalanced braces/brackets, and unbalanced double quotes — a lightweight check that
     * catches the common mistakes without a full JSON parser.
     */
    static List<String> validateTemplate(String template) {
        List<String> issues = new ArrayList<>();
        if (template == null || template.isBlank()) return issues;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([a-z_]+)\\}").matcher(template);
        while (m.find()) {
            if (!KNOWN_PLACEHOLDERS.contains(m.group(1))) {
                issues.add("unknown placeholder {" + m.group(1) + "}");
            }
        }
        int curly = 0, square = 0; boolean inStr = false; char prev = 0; int quotes = 0;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '"' && prev != '\\') { inStr = !inStr; quotes++; }
            if (!inStr) {
                if (c == '{') curly++;
                else if (c == '}') curly--;
                else if (c == '[') square++;
                else if (c == ']') square--;
            }
            prev = c;
        }
        if (curly != 0) issues.add("unbalanced curly braces");
        if (square != 0) issues.add("unbalanced square brackets");
        if (quotes % 2 != 0) issues.add("unbalanced double quotes");
        return issues;
    }

    /** A fixed sample entry used by the dry-run preview. */
    static AuditLog.Entry sampleEntry() {
        long now = System.currentTimeMillis();
        return new AuditLog.Entry("sample", now, java.time.Instant.ofEpochMilli(now).toString(),
                "alice", "capability_denied", "tool:run_command", "outside scope of role 'reader'");
    }

    /**
     * Dry-run preview: render the given template (or the configured global template when null) against a
     * sample event, returning the rendered payload + validation issues. When {@code send} is true and a
     * webhook is configured, also enqueues one real delivery of the rendered sample.
     */
    public Map<String, Object> preview(String templateOverride, boolean send) {
        String tmpl = (templateOverride != null) ? templateOverride : template;
        AuditLog.Entry sample = sampleEntry();
        String rendered = (tmpl == null || tmpl.isBlank()) ? toJson(sample) : applyTemplate(tmpl, sample);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("template", tmpl == null ? "" : tmpl);
        out.put("rendered", rendered);
        out.put("issues", validateTemplate(tmpl));
        out.put("default_webhook_configured", webhookUrl != null && !webhookUrl.isBlank());
        boolean didSend = false;
        if (send && enabled && webhookUrl != null && !webhookUrl.isBlank() && scheduler != null) {
            enqueue(rendered, webhookUrl, null);
            didSend = true;
        }
        out.put("sent", didSend);
        return out;
    }

    private void onEntry(AuditLog.Entry e) {
        if (!shouldForward(e.action())) return;
        log.warn("[alert] " + e.action() + " user=" + e.user() + " target=" + e.target()
                + " outcome=" + e.outcome());
        String url = urlFor(e.action());
        String tmpl = templateFor(e.action());
        String payload = (tmpl == null || tmpl.isBlank()) ? toJson(e) : applyTemplate(tmpl, e);
        enqueue(payload, url, null);
    }

    /** Submit a payload for delivery to {@code url} if configured and the buffer isn't saturated. */
    private void enqueue(String payload, String url, String dlId) {
        if (url == null || url.isBlank() || http == null || scheduler == null) return;
        if (inFlight.get() >= Math.max(1, queueCapacity)) {
            dropped.incrementAndGet();
            log.warn("[alerts] delivery buffer full (" + queueCapacity + "); dropping alert");
            return;
        }
        inFlight.incrementAndGet();
        queued.incrementAndGet();
        scheduler.submit(() -> attempt(new Delivery(payload, url, 1, dlId)));
    }

    /** One delivery attempt; on failure reschedule with backoff or dead-letter. Runs on the scheduler. */
    private void attempt(Delivery d) {
        boolean ok = false;
        String err = null;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(d.url()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(d.payload()))
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            ok = resp.statusCode() / 100 == 2;
            if (!ok) { err = "HTTP " + resp.statusCode(); log.warn("[alerts] webhook " + err + " (attempt " + d.attemptNo() + ")"); }
        } catch (Exception ex) {
            err = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.warn("[alerts] webhook post failed (attempt " + d.attemptNo() + "): " + ex.getMessage());
        }
        if (ok) {
            sent.incrementAndGet();
            inFlight.decrementAndGet();
            if (d.dlId() != null) deleteDeadLetter(d.dlId()); // confirmed delivery of a replayed row
            return;
        }
        failed.incrementAndGet();
        if (d.attemptNo() <= maxRetries) {
            retried.incrementAndGet();
            long delay = backoffMs(d.attemptNo(), retryBackoffMs);
            final String fe = err;
            try {
                scheduler.schedule(() -> attempt(new Delivery(d.payload(), d.url(), d.attemptNo() + 1, d.dlId())),
                        delay, TimeUnit.MILLISECONDS);
            } catch (Exception schedEx) {
                exhausted(d, fe);
            }
        } else {
            exhausted(d, err);
        }
    }

    /** Final failure: persist (or update, if this was a replay) the dead-letter row + history. */
    private void exhausted(Delivery d, String err) {
        deadLettered.incrementAndGet();
        inFlight.decrementAndGet();
        String reason = err == null ? "exhausted retries" : err;
        if (dlPersistent()) {
            try {
                if (d.dlId() != null) {
                    // replayed row that failed again — update history, restore to 'failed' (keep the id)
                    db.update("UPDATE alerts_dead_letter SET attempts = attempts + ?, last_error = ?, "
                                    + "status = 'failed', last_attempt_at = ? WHERE id = ?",
                            maxRetries + 1, reason, System.currentTimeMillis(), d.dlId());
                } else {
                    db.update("INSERT INTO alerts_dead_letter(id, ts, payload, url, attempts, last_error, "
                                    + "status, last_attempt_at) VALUES(?,?,?,?,?,?,?,?)",
                            java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), d.payload(),
                            d.url(), maxRetries + 1, reason, "failed", System.currentTimeMillis());
                }
            } catch (Exception ex) {
                log.warn("[alerts] could not persist dead-letter, keeping in memory: " + ex.getMessage());
                ringAdd(d.payload());
            }
        } else {
            ringAdd(d.payload());
        }
        log.warn("[alerts] alert dead-lettered after " + maxRetries + " retries (" + reason + ")");
    }

    private void deleteDeadLetter(String id) {
        if (!dlPersistent()) return;
        try {
            db.update("DELETE FROM alerts_dead_letter WHERE id=?", id);
        } catch (Exception ex) {
            log.warn("[alerts] could not delete dead-letter row " + id + ": " + ex.getMessage());
        }
    }

    private void ringAdd(String payload) {
        synchronized (dlLock) {
            deadLetter.addFirst(payload);
            while (deadLetter.size() > Math.max(0, deadLetterCapacity)) deadLetter.removeLast();
        }
    }

    /** Snapshot of delivery counters (for {@code /metrics} and Prometheus). */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("queued", queued.get());
        m.put("sent", sent.get());
        m.put("failed", failed.get());
        m.put("retried", retried.get());
        m.put("dead_lettered", deadLettered.get());
        m.put("dropped", dropped.get());
        m.put("replayed", replayed.get());
        m.put("in_flight", (long) inFlight.get());
        m.put("dead_letter_persistent", dlPersistent());
        m.put("routes", routes.keySet());
        m.put("dead_letter_size", (long) deadLetterSize());
        return m;
    }

    private int deadLetterSize() {
        if (dlPersistent()) {
            try {
                var rows = db.query("SELECT COUNT(*) FROM alerts_dead_letter WHERE status='failed'", rs -> rs.getInt(1));
                return rows.isEmpty() ? 0 : rows.get(0);
            } catch (Exception ex) {
                return 0;
            }
        }
        synchronized (dlLock) { return deadLetter.size(); }
    }

    /** Dead-letter entries, newest first (for {@code GET /admin/alerts/failed}). */
    public List<DeadLetter> deadLetterEntries() {
        if (dlPersistent()) {
            try {
                return db.query("SELECT id, ts, payload, url, attempts, last_error, status, last_attempt_at "
                                + "FROM alerts_dead_letter ORDER BY ts DESC LIMIT 500",
                        rs -> new DeadLetter(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                rs.getInt(5), rs.getString(6), rs.getString(7), rs.getLong(8)));
            } catch (Exception ex) {
                log.warn("[alerts] could not read dead-letter table: " + ex.getMessage());
                return List.of();
            }
        }
        List<DeadLetter> out = new ArrayList<>();
        synchronized (dlLock) {
            for (String p : deadLetter) out.add(new DeadLetter(null, 0L, p, null, maxRetries + 1,
                    "exhausted retries", "failed", 0L));
        }
        return out;
    }

    /** Backward-compatible: the dead-letter payloads, newest first. */
    public List<String> deadLetters() {
        List<String> out = new ArrayList<>();
        for (DeadLetter d : deadLetterEntries()) out.add(d.payload());
        return out;
    }

    /**
     * Crash-safe replay: mark matching {@code failed} rows {@code replaying} (without deleting), then
     * re-enqueue them carrying their id. A confirmed delivery deletes the row; a repeated failure restores it
     * to {@code failed} with updated history. With {@code id}, replays just that row; otherwise all failed
     * rows. Returns the number re-enqueued. No-op when alerting isn't configured.
     */
    public synchronized int replay(String id) {
        if (!enabled || scheduler == null) return 0;
        int n = 0;
        if (dlPersistent()) {
            List<DeadLetter> rows = deadLetterEntries();
            for (DeadLetter d : rows) {
                if (!"failed".equals(d.status())) continue;
                if (id != null && !id.equals(d.id())) continue;
                String url = (d.url() != null && !d.url().isBlank()) ? d.url() : webhookUrl;
                if (url == null || url.isBlank()) continue; // nowhere to send
                try {
                    db.update("UPDATE alerts_dead_letter SET status='replaying', last_attempt_at=? WHERE id=?",
                            System.currentTimeMillis(), d.id());
                } catch (Exception ex) {
                    log.warn("[alerts] could not mark replaying " + d.id() + ": " + ex.getMessage());
                    continue;
                }
                enqueue(d.payload(), url, d.id());
                n++;
            }
        } else {
            List<String> snapshot;
            synchronized (dlLock) { snapshot = new ArrayList<>(deadLetter); deadLetter.clear(); }
            for (String p : snapshot) {
                if (webhookUrl == null || webhookUrl.isBlank()) break;
                enqueue(p, webhookUrl, null);
                n++;
            }
        }
        if (n > 0) { replayed.addAndGet(n); log.info("[alerts] replaying " + n + " dead-lettered alert(s)"); }
        return n;
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
