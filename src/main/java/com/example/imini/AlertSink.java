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
    @Value("${alerts.dead-letter-retention-hours:168}") private long retentionHours;
    @Value("${alerts.dedup-window-seconds:0}") private long dedupWindowSeconds;
    @Value("${alerts.dedup-shared:true}") private boolean dedupShared;
    @Value("${alerts.escalate-after-minutes:0}") private long escalateAfterMinutes;
    @Value("${alerts.escalate-url:}") private String escalateUrl;
    @Value("${alerts.escalate-tiers:}") private String escalateTiersCfg;
    @Value("${alerts.dedup-digest:true}") private boolean dedupDigest;

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
    private final AtomicLong suppressed = new AtomicLong(); // collapsed duplicate alerts
    private final AtomicLong escalated = new AtomicLong();   // re-paged to the escalation route
    private final AtomicLong digested = new AtomicLong();    // dedup-digest notifications emitted

    private List<Tier> escalationTiers = List.of();

    // per-route delivery counters: action -> [sent, failed, dead_lettered]
    private final Map<String, long[]> byRoute = new java.util.concurrent.ConcurrentHashMap<>();

    // dedup windows: key (action|target) -> mutable window state
    private final Map<String, long[]> dedupState = new java.util.concurrent.ConcurrentHashMap<>(); // [windowStart, suppressed]
    private final Object dedupLock = new Object();

    /** A per-action route: a webhook URL and an optional template (null = use the global template). */
    public record Route(String url, String template) {}

    /** One escalation tier: page to {@code url} once a dead-letter is older than {@code afterMs}. */
    public record Tier(long afterMs, String url, String template) {}

    /** One unit of delivery work: payload + destination, current attempt, dead-letter row id, route label. */
    private record Delivery(String payload, String url, int attemptNo, String dlId, String action) {}

    /** Pure dedup decision: whether to forward now, and how many were suppressed in the just-closed window. */
    record DedupResult(boolean forward, int suppressedSincePrev) {}

    /** A dead-lettered delivery row (durable rows carry an id/status/history; memory rows are payload-only). */
    public record DeadLetter(String id, long ts, String payload, String url, int attempts,
                             String lastError, String status, long lastAttemptAt, String action) {}

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
        this.escalationTiers = resolveTiers();
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
            enqueue(rendered, webhookUrl, null, "test");
            didSend = true;
        }
        out.put("sent", didSend);
        return out;
    }

    private void onEntry(AuditLog.Entry e) {
        if (!shouldForward(e.action())) return;
        // Dedup/throttle: collapse repeated identical (action|target) alerts within the configured window.
        DedupResult dd = dedupDecide(e.action() + "|" + e.target(), System.currentTimeMillis());
        if (!dd.forward()) {
            suppressed.incrementAndGet();
            routeInc(e.action(), 3);
            return; // suppressed; not even logged at WARN to avoid log storms
        }
        log.warn("[alert] " + e.action() + " user=" + e.user() + " target=" + e.target()
                + " outcome=" + e.outcome()
                + (dd.suppressedSincePrev() > 0 ? " (+" + dd.suppressedSincePrev() + " suppressed since prev)" : ""));
        String url = urlFor(e.action());
        String tmpl = templateFor(e.action());
        String payload = (tmpl == null || tmpl.isBlank()) ? toJson(e) : applyTemplate(tmpl, e);
        enqueue(payload, url, null, e.action());
    }

    /** Pure dedup outcome for a window state at {@code nowMs}: whether to forward, and the next state. */
    record DedupOutcome(boolean forward, long newWindowStart, int newSuppressed, int suppressedSincePrev) {}

    /**
     * Pure: given the current window (start + suppressed count, or null state via windowStart&lt;0) decide the
     * next state. A new/elapsed window forwards and resets (reporting the prior window's suppressed count);
     * within a window it suppresses and increments the count.
     */
    static DedupOutcome dedupOutcome(long windowStart, int suppressed, long nowMs, long windowMs) {
        boolean fresh = windowStart < 0 || nowMs - windowStart >= windowMs;
        if (fresh) {
            int prev = windowStart < 0 ? 0 : suppressed;
            return new DedupOutcome(true, nowMs, 0, prev);
        }
        return new DedupOutcome(false, windowStart, suppressed + 1, 0);
    }

    boolean dedupPersistent() {
        return dedupShared && db != null && db.available();
    }

    /**
     * Dedup decision for a key at {@code nowMs}. Backed by the shared {@code alert_dedup} SQLite table when
     * available (so throttling is cluster-wide), else an in-memory map. A window of 0 disables dedup.
     */
    DedupResult dedupDecide(String key, long nowMs) {
        long windowMs = dedupWindowSeconds * 1000L;
        if (windowMs <= 0) return new DedupResult(true, 0);
        if (dedupPersistent()) {
            synchronized (dedupLock) { // serialize the read-modify-write within this process
                try {
                    var rows = db.query("SELECT window_start, suppressed FROM alert_dedup WHERE dk_key=?",
                            rs -> new long[]{rs.getLong(1), rs.getLong(2)}, key);
                    long ws = rows.isEmpty() ? -1 : rows.get(0)[0];
                    int sup = rows.isEmpty() ? 0 : (int) rows.get(0)[1];
                    DedupOutcome o = dedupOutcome(ws, sup, nowMs, windowMs);
                    db.update("INSERT INTO alert_dedup(dk_key, window_start, suppressed) VALUES(?,?,?) "
                                    + "ON CONFLICT(dk_key) DO UPDATE SET window_start=excluded.window_start, "
                                    + "suppressed=excluded.suppressed",
                            key, o.newWindowStart(), (long) o.newSuppressed());
                    return new DedupResult(o.forward(), o.suppressedSincePrev());
                } catch (Exception ex) {
                    log.warn("[alerts] shared dedup failed, using in-memory: " + ex.getMessage());
                    // fall through to in-memory
                }
            }
        }
        synchronized (dedupLock) {
            long[] st = dedupState.get(key);
            long ws = st == null ? -1 : st[0];
            int sup = st == null ? 0 : (int) st[1];
            DedupOutcome o = dedupOutcome(ws, sup, nowMs, windowMs);
            dedupState.put(key, new long[]{o.newWindowStart(), o.newSuppressed()});
            return new DedupResult(o.forward(), o.suppressedSincePrev());
        }
    }

    /** True when dedup digests should be emitted (dedup window set and digests enabled). */
    boolean digestEnabled() { return dedupWindowSeconds > 0 && dedupDigest; }

    /** Pure: a small JSON digest payload summarizing how many alerts a dedup key suppressed. */
    static String digestPayload(String key, long count, long windowSeconds) {
        String action = key, target = "";
        int bar = key == null ? -1 : key.indexOf('|');
        if (bar >= 0) { action = key.substring(0, bar); target = key.substring(bar + 1); }
        return "{\"digest\":true,\"action\":\"" + esc(action) + "\",\"target\":\"" + esc(target)
                + "\",\"suppressed\":" + count + ",\"window_seconds\":" + windowSeconds + "}";
    }

    /**
     * Emit a digest for each dedup window that has elapsed with suppressions, then clear it — so a suppressed
     * storm stays visible (one summary per key) without flooding. Routed to the action's webhook. Runs on the
     * reaper tick. Returns the number of digests emitted. No-op unless dedup + digests are enabled.
     */
    public int dedupDigestSweep(long nowMs) {
        if (!digestEnabled() || scheduler == null) return 0;
        long windowMs = dedupWindowSeconds * 1000L;
        int n = 0;
        if (dedupPersistent()) {
            record DRow(String key, long suppressed) {}
            List<DRow> due;
            try {
                synchronized (dedupLock) {
                    due = db.query("SELECT dk_key, suppressed FROM alert_dedup WHERE suppressed > 0 AND ? - window_start >= ?",
                            rs -> new DRow(rs.getString(1), rs.getLong(2)), nowMs, windowMs);
                    for (DRow r : due) db.update("DELETE FROM alert_dedup WHERE dk_key=?", r.key());
                }
            } catch (Exception ex) {
                log.warn("[alerts] dedup digest sweep failed: " + ex.getMessage());
                return 0;
            }
            for (DRow r : due) {
                String action = r.key().contains("|") ? r.key().substring(0, r.key().indexOf('|')) : r.key();
                enqueue(digestPayload(r.key(), r.suppressed(), dedupWindowSeconds), urlFor(action), null, action);
                digested.incrementAndGet();
                n++;
            }
        } else {
            List<Map.Entry<String, long[]>> due = new ArrayList<>();
            synchronized (dedupLock) {
                for (var e : dedupState.entrySet()) {
                    if (e.getValue()[1] > 0 && nowMs - e.getValue()[0] >= windowMs) due.add(e);
                }
                for (var e : due) dedupState.remove(e.getKey());
            }
            for (var e : due) {
                String key = e.getKey();
                String action = key.contains("|") ? key.substring(0, key.indexOf('|')) : key;
                enqueue(digestPayload(key, e.getValue()[1], dedupWindowSeconds), urlFor(action), null, action);
                digested.incrementAndGet();
                n++;
            }
        }
        if (n > 0) log.info("[alerts] emitted " + n + " dedup digest(s)");
        return n;
    }

    /** Submit a payload for delivery to {@code url} if configured and the buffer isn't saturated. */
    private void enqueue(String payload, String url, String dlId, String action) {
        if (url == null || url.isBlank() || http == null || scheduler == null) return;
        if (inFlight.get() >= Math.max(1, queueCapacity)) {
            dropped.incrementAndGet();
            log.warn("[alerts] delivery buffer full (" + queueCapacity + "); dropping alert");
            return;
        }
        inFlight.incrementAndGet();
        queued.incrementAndGet();
        scheduler.submit(() -> attempt(new Delivery(payload, url, 1, dlId, action)));
    }

    private void routeInc(String action, int idx) {
        if (action == null || action.isBlank()) action = "default";
        long[] c = byRoute.computeIfAbsent(action, k -> new long[4]); // sent, failed, dead_lettered, suppressed
        synchronized (c) { c[idx]++; }
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
            routeInc(d.action(), 0);
            inFlight.decrementAndGet();
            if (d.dlId() != null) deleteDeadLetter(d.dlId()); // confirmed delivery of a replayed row
            return;
        }
        failed.incrementAndGet();
        routeInc(d.action(), 1);
        if (d.attemptNo() <= maxRetries) {
            retried.incrementAndGet();
            long delay = backoffMs(d.attemptNo(), retryBackoffMs);
            final String fe = err;
            try {
                scheduler.schedule(() -> attempt(new Delivery(d.payload(), d.url(), d.attemptNo() + 1, d.dlId(), d.action())),
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
        routeInc(d.action(), 2);
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
                                    + "status, last_attempt_at, action) VALUES(?,?,?,?,?,?,?,?,?)",
                            java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), d.payload(),
                            d.url(), maxRetries + 1, reason, "failed", System.currentTimeMillis(), d.action());
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
        m.put("suppressed", suppressed.get());
        m.put("escalated", escalated.get());
        m.put("digested", digested.get());
        m.put("in_flight", (long) inFlight.get());
        m.put("dead_letter_persistent", dlPersistent());
        m.put("routes", routes.keySet());
        m.put("by_route", byRouteSnapshot());
        m.put("dead_letter_size", (long) deadLetterSize());
        return m;
    }

    /** Per-route counters as action -> {sent, failed, dead_lettered} for the metrics snapshot. */
    private Map<String, Map<String, Long>> byRouteSnapshot() {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : byRoute.entrySet()) {
            long[] c = e.getValue();
            Map<String, Long> m = new LinkedHashMap<>();
            synchronized (c) {
                m.put("sent", c[0]);
                m.put("failed", c[1]);
                m.put("dead_lettered", c[2]);
                m.put("suppressed", c[3]);
            }
            out.put(e.getKey(), m);
        }
        return out;
    }

    /** Pure: the epoch-ms cutoff before which failed dead-letters should be purged (0 = keep forever). */
    static long cutoff(long nowMs, long retentionHours) {
        if (retentionHours <= 0) return 0L;
        return nowMs - retentionHours * 3_600_000L;
    }

    /**
     * Age out failed dead-letters older than {@code retentionHours} (called by the reaper). Rows currently
     * {@code replaying} are never purged. Returns the number removed; 0 when retention is disabled, there's no
     * database, or nothing qualifies. The in-memory ring is already size-bounded, so this is a no-op there.
     */
    public int purgeOlderThan(long retentionHours, long nowMs) {
        long cut = cutoff(nowMs, retentionHours);
        if (cut <= 0 || !dlPersistent()) return 0;
        try {
            int removed = db.update("DELETE FROM alerts_dead_letter WHERE status='failed' AND ts < ?", cut);
            if (removed > 0) log.info("[alerts] purged " + removed + " dead-letter(s) older than "
                    + retentionHours + "h");
            return removed;
        } catch (Exception ex) {
            log.warn("[alerts] dead-letter purge failed: " + ex.getMessage());
            return 0;
        }
    }

    /** The configured retention window in hours (0 = keep forever); used by the reaper. */
    public long retentionHours() { return retentionHours; }

    /** The escalation threshold in minutes (legacy single-tier accessor); 0 = none. */
    public long escalateAfterMinutes() { return escalateAfterMinutes; }

    /** True when at least one escalation tier is configured (new ladder or legacy single tier). */
    boolean escalationEnabled() { return !escalationTiers.isEmpty(); }

    /** The resolved escalation ladder (sorted by delay), for inspection/tests. */
    List<Tier> tiers() { return escalationTiers; }

    /**
     * Pure: a duration like {@code 15m}, {@code 30s}, {@code 2h}, {@code 1d}, or a bare number of milliseconds,
     * to milliseconds. Returns -1 if unparseable.
     */
    static long parseDuration(String s) {
        if (s == null || s.isBlank()) return -1;
        String v = s.trim().toLowerCase();
        try {
            char unit = v.charAt(v.length() - 1);
            long mult = switch (unit) {
                case 's' -> 1000L;
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                case 'd' -> 86_400_000L;
                default -> 1L; // bare ms
            };
            String num = Character.isLetter(unit) ? v.substring(0, v.length() - 1) : v;
            long n = Long.parseLong(num.trim());
            return n < 0 ? -1 : n * mult;
        } catch (Exception ex) {
            return -1;
        }
    }

    /**
     * Pure: parse an escalation ladder. Tiers are separated by {@code ;;}; each is {@code delay|url|template}
     * (template optional). Invalid tiers are skipped. Result is sorted by delay ascending.
     */
    static List<Tier> parseTiers(String cfg) {
        List<Tier> out = new ArrayList<>();
        if (cfg == null || cfg.isBlank()) return out;
        for (String entry : cfg.split(";;")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            int p1 = e.indexOf('|');
            if (p1 <= 0) continue;
            long after = parseDuration(e.substring(0, p1));
            String rest = e.substring(p1 + 1);
            int p2 = rest.indexOf('|');
            String url = (p2 < 0 ? rest : rest.substring(0, p2)).trim();
            String tmpl = (p2 < 0 ? null : rest.substring(p2 + 1));
            if (after < 0 || url.isEmpty()) continue;
            out.add(new Tier(after, url, (tmpl == null || tmpl.isBlank()) ? null : tmpl));
        }
        out.sort(java.util.Comparator.comparingLong(Tier::afterMs));
        return out;
    }

    /** Resolve the ladder: the new tiered config if present, else synthesize one tier from the legacy keys. */
    private List<Tier> resolveTiers() {
        List<Tier> t = parseTiers(escalateTiersCfg);
        if (!t.isEmpty()) return t;
        if (escalateAfterMinutes > 0 && escalateUrl != null && !escalateUrl.isBlank()) {
            return List.of(new Tier(escalateAfterMinutes * 60_000L, escalateUrl, null));
        }
        return List.of();
    }

    /**
     * Walk un-acknowledged dead-letters up the escalation ladder: a row at tier {@code k} is paged to tier
     * {@code k+1} once its age crosses that tier's delay. The advance is an <b>atomic claim</b>
     * ({@code UPDATE ... WHERE escalation_tier=k}), so when several instances run the reaper only one wins and
     * the alert is paged once per tier. Advances at most one tier per row per call. Returns the number paged.
     */
    public int escalateStale(long nowMs) {
        if (!escalationEnabled() || !dlPersistent() || scheduler == null) return 0;
        record EscRow(String id, long ts, String payload, int tier, String action) {}
        List<EscRow> rows;
        try {
            rows = db.query("SELECT id, ts, payload, escalation_tier, action FROM alerts_dead_letter "
                            + "WHERE status='failed' AND acked_at IS NULL AND escalation_tier < ? "
                            + "ORDER BY ts ASC LIMIT 200",
                    rs -> new EscRow(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getString(5)),
                    escalationTiers.size());
        } catch (Exception ex) {
            log.warn("[alerts] escalation query failed: " + ex.getMessage());
            return 0;
        }
        int n = 0;
        for (EscRow d : rows) {
            int cur = d.tier();
            int next = cur + 1;
            if (next > escalationTiers.size()) continue;
            Tier tier = escalationTiers.get(next - 1);
            if (nowMs - d.ts() < tier.afterMs()) continue; // not due yet
            int claimed;
            try {
                claimed = db.update("UPDATE alerts_dead_letter SET escalation_tier=?, escalated_at=? "
                                + "WHERE id=? AND escalation_tier=? AND status='failed' AND acked_at IS NULL",
                        next, nowMs, d.id(), cur);
            } catch (Exception ex) {
                log.warn("[alerts] escalation claim failed for " + d.id() + ": " + ex.getMessage());
                continue;
            }
            if (claimed != 1) continue; // lost the race to another instance/tick
            enqueue(d.payload(), tier.url(), null, "escalation_t" + next);
            escalated.incrementAndGet();
            n++;
        }
        if (n > 0) log.warn("[alerts] escalated " + n + " un-acked dead-letter(s) up the ladder");
        return n;
    }

    /** Acknowledge a dead-letter so it stops counting toward escalation. Returns rows affected. */
    public int ack(String id) {
        if (id == null || id.isBlank() || !dlPersistent()) return 0;
        try {
            return db.update("UPDATE alerts_dead_letter SET acked_at=? WHERE id=?", System.currentTimeMillis(), id);
        } catch (Exception ex) {
            log.warn("[alerts] ack failed for " + id + ": " + ex.getMessage());
            return 0;
        }
    }

    /** Delete all dead-letters (or one by {@code id}). Clears the in-memory ring when not persistent. */
    public int purgeAll(String id) {
        if (dlPersistent()) {
            try {
                return (id == null || id.isBlank())
                        ? db.update("DELETE FROM alerts_dead_letter")
                        : db.update("DELETE FROM alerts_dead_letter WHERE id=?", id);
            } catch (Exception ex) {
                log.warn("[alerts] dead-letter purge-all failed: " + ex.getMessage());
                return 0;
            }
        }
        synchronized (dlLock) {
            int n = deadLetter.size();
            deadLetter.clear();
            return n;
        }
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

    /** Pure: does a dead-letter match the given filters? (action exact; status exact; q = payload substring) */
    static boolean matchesFilter(DeadLetter d, String action, String status, String q) {
        if (action != null && !action.isBlank() && !action.equals(d.action())) return false;
        if (status != null && !status.isBlank() && !status.equalsIgnoreCase(d.status())) return false;
        if (q != null && !q.isBlank() && (d.payload() == null || !d.payload().contains(q))) return false;
        return true;
    }

    /**
     * A filtered, paginated page of dead-letters (newest first). Filters: {@code action} (exact),
     * {@code status} (exact), {@code q} (payload substring). Uses SQL when durable, else filters the ring.
     */
    public List<DeadLetter> deadLetterPage(String action, String status, String q, int offset, int limit) {
        int off = Math.max(0, offset);
        int lim = limit <= 0 ? 50 : Math.min(limit, 500);
        if (dlPersistent()) {
            StringBuilder sql = new StringBuilder("SELECT id, ts, payload, url, attempts, last_error, status, "
                    + "last_attempt_at, action FROM alerts_dead_letter WHERE 1=1");
            List<Object> params = new ArrayList<>();
            if (action != null && !action.isBlank()) { sql.append(" AND action=?"); params.add(action); }
            if (status != null && !status.isBlank()) { sql.append(" AND status=?"); params.add(status); }
            if (q != null && !q.isBlank()) { sql.append(" AND payload LIKE ?"); params.add("%" + q + "%"); }
            sql.append(" ORDER BY ts DESC LIMIT ? OFFSET ?");
            params.add(lim); params.add(off);
            try {
                return db.query(sql.toString(),
                        rs -> new DeadLetter(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                rs.getInt(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getString(9)),
                        params.toArray());
            } catch (Exception ex) {
                log.warn("[alerts] dead-letter page query failed: " + ex.getMessage());
                return List.of();
            }
        }
        List<DeadLetter> all = deadLetterEntries();
        List<DeadLetter> filtered = new ArrayList<>();
        for (DeadLetter d : all) if (matchesFilter(d, action, status, q)) filtered.add(d);
        List<DeadLetter> out = new ArrayList<>();
        for (int i = off; i < filtered.size() && out.size() < lim; i++) out.add(filtered.get(i));
        return out;
    }

    /** Total dead-letters matching the same filters (for pagination math). */
    public int deadLetterCount(String action, String status, String q) {
        if (dlPersistent()) {
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM alerts_dead_letter WHERE 1=1");
            List<Object> params = new ArrayList<>();
            if (action != null && !action.isBlank()) { sql.append(" AND action=?"); params.add(action); }
            if (status != null && !status.isBlank()) { sql.append(" AND status=?"); params.add(status); }
            if (q != null && !q.isBlank()) { sql.append(" AND payload LIKE ?"); params.add("%" + q + "%"); }
            try {
                var rows = db.query(sql.toString(), rs -> rs.getInt(1), params.toArray());
                return rows.isEmpty() ? 0 : rows.get(0);
            } catch (Exception ex) {
                return 0;
            }
        }
        int n = 0;
        for (DeadLetter d : deadLetterEntries()) if (matchesFilter(d, action, status, q)) n++;
        return n;
    }

    /** Dead-letter entries, newest first (for {@code GET /admin/alerts/failed}). */
    public List<DeadLetter> deadLetterEntries() {
        if (dlPersistent()) {
            try {
                return db.query("SELECT id, ts, payload, url, attempts, last_error, status, last_attempt_at, action "
                                + "FROM alerts_dead_letter ORDER BY ts DESC LIMIT 500",
                        rs -> new DeadLetter(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                rs.getInt(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getString(9)));
            } catch (Exception ex) {
                log.warn("[alerts] could not read dead-letter table: " + ex.getMessage());
                return List.of();
            }
        }
        List<DeadLetter> out = new ArrayList<>();
        synchronized (dlLock) {
            for (String p : deadLetter) out.add(new DeadLetter(null, 0L, p, null, maxRetries + 1,
                    "exhausted retries", "failed", 0L, null));
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
                enqueue(d.payload(), url, d.id(), d.action());
                n++;
            }
        } else {
            List<String> snapshot;
            synchronized (dlLock) { snapshot = new ArrayList<>(deadLetter); deadLetter.clear(); }
            for (String p : snapshot) {
                if (webhookUrl == null || webhookUrl.isBlank()) break;
                enqueue(p, webhookUrl, null, "replay");
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
