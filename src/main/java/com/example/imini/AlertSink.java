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
 * configured set it (a) logs a {@code WARN} line — always available as a channel — and (b) if
 * {@code alerts.webhook-url} is set, enqueues a webhook POST.
 *
 * <h2>Delivery buffer with retry &amp; dead-letter</h2>
 * Webhook delivery is buffered and resilient: each alert is dispatched on a background scheduler; a failed
 * POST (network error or non-2xx) is retried up to {@code alerts.max-retries} times with exponential backoff
 * ({@code alerts.retry-backoff-ms} base). An alert that exhausts its retries is moved to a bounded in-memory
 * dead-letter ring (inspectable at {@code GET /admin/alerts/failed}) rather than silently lost. If more than
 * {@code alerts.queue-capacity} deliveries are in flight, the newest is dropped (and counted) to bound memory.
 * Delivery never blocks or breaks the audit write or the run. Counters are exposed via {@link #stats()} and
 * scraped through the Prometheus endpoint.
 *
 * <p>Configure with {@code alerts.enabled=true}, {@code alerts.actions} (comma-separated audit actions;
 * defaults to the three security actions), optionally {@code alerts.webhook-url}, and the buffer knobs above.
 * Off by default.
 */
@Component
public class AlertSink {

    private static final Logger log = LoggerFactory.getLogger(AlertSink.class);

    @Value("${alerts.enabled:false}") private boolean enabled;
    @Value("${alerts.webhook-url:}") private String webhookUrl;
    @Value("${alerts.actions:capability_denied,spend_alert,tool_rate_limited}") private String actionsCfg;
    @Value("${alerts.max-retries:3}") private int maxRetries;
    @Value("${alerts.retry-backoff-ms:500}") private long retryBackoffMs;
    @Value("${alerts.queue-capacity:1000}") private int queueCapacity;
    @Value("${alerts.dead-letter-capacity:100}") private int deadLetterCapacity;

    private final AuditLog audit;
    private Set<String> actions = Set.of();

    private HttpClient http;
    private ScheduledExecutorService scheduler;

    // in-flight delivery count (bounds memory); dead-letter ring of permanently-failed payloads
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Deque<String> deadLetter = new ArrayDeque<>();
    private final Object dlLock = new Object();

    // delivery counters (exposed via stats() and Prometheus)
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();    // individual attempt failures
    private final AtomicLong retried = new AtomicLong();   // re-scheduled attempts
    private final AtomicLong deadLettered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();   // shed because the buffer was full

    public AlertSink(AuditLog audit) {
        this.audit = audit;
    }

    public boolean enabled() { return enabled; }

    @jakarta.annotation.PostConstruct
    public void init() {
        this.actions = parseActions(actionsCfg);
        if (enabled) {
            this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            this.scheduler = Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "alert-sink");
                t.setDaemon(true);
                return t;
            });
            audit.addListener(this::onEntry);
            log.info("[alerts] enabled for actions " + actions
                    + (webhookUrl != null && !webhookUrl.isBlank()
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

    /** Pure: should an entry with this action be forwarded, given current config? */
    boolean shouldForward(String action) {
        return enabled && action != null && actions.contains(action);
    }

    /** Pure: backoff delay (ms) before the given retry attempt (1-based), capped at 60s. */
    static long backoffMs(int attempt, long base) {
        long b = base <= 0 ? 1 : base;
        long delay = b * (1L << Math.min(Math.max(attempt - 1, 0), 16)); // exponential, guard shift
        return Math.min(delay, 60_000L);
    }

    /** Pure: build the JSON payload posted to the webhook. */
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

    private void onEntry(AuditLog.Entry e) {
        if (!shouldForward(e.action())) return;
        log.warn("[alert] " + e.action() + " user=" + e.user() + " target=" + e.target()
                + " outcome=" + e.outcome());
        if (webhookUrl == null || webhookUrl.isBlank() || http == null || scheduler == null) return;
        // Bound in-flight memory: shed the newest alert if the buffer is saturated.
        if (inFlight.get() >= Math.max(1, queueCapacity)) {
            dropped.incrementAndGet();
            log.warn("[alerts] delivery buffer full (" + queueCapacity + "); dropping alert for " + e.action());
            return;
        }
        inFlight.incrementAndGet();
        queued.incrementAndGet();
        final String payload = toJson(e);
        scheduler.submit(() -> attempt(payload, 1));
    }

    /** One delivery attempt; on failure, reschedule with backoff or dead-letter. Runs on the scheduler. */
    private void attempt(String payload, int attemptNo) {
        boolean ok = false;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            ok = resp.statusCode() / 100 == 2;
            if (!ok) log.warn("[alerts] webhook returned HTTP " + resp.statusCode()
                    + " (attempt " + attemptNo + ")");
        } catch (Exception ex) {
            log.warn("[alerts] webhook post failed (attempt " + attemptNo + "): " + ex.getMessage());
        }
        if (ok) {
            sent.incrementAndGet();
            inFlight.decrementAndGet();
            return;
        }
        failed.incrementAndGet();
        if (attemptNo <= maxRetries) {
            retried.incrementAndGet();
            long delay = backoffMs(attemptNo, retryBackoffMs);
            try {
                scheduler.schedule(() -> attempt(payload, attemptNo + 1), delay, TimeUnit.MILLISECONDS);
            } catch (Exception schedEx) {
                // scheduler shutting down — dead-letter rather than lose silently
                toDeadLetter(payload);
            }
        } else {
            toDeadLetter(payload);
        }
    }

    private void toDeadLetter(String payload) {
        deadLettered.incrementAndGet();
        inFlight.decrementAndGet();
        synchronized (dlLock) {
            deadLetter.addFirst(payload);
            while (deadLetter.size() > Math.max(0, deadLetterCapacity)) deadLetter.removeLast();
        }
        log.warn("[alerts] alert dead-lettered after " + maxRetries + " retries");
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
        m.put("in_flight", (long) inFlight.get());
        synchronized (dlLock) { m.put("dead_letter_size", (long) deadLetter.size()); }
        return m;
    }

    /** The dead-letter payloads, newest first (for {@code GET /admin/alerts/failed}). */
    public List<String> deadLetters() {
        synchronized (dlLock) { return new ArrayList<>(deadLetter); }
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
