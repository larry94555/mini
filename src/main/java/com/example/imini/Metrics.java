package com.example.imini;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-process observability: counters, per-tool and per-key tallies, run latency, plus live
 * concurrency gauges read from {@link RunService}. Exposed at GET /metrics. Cheap and thread-safe;
 * not a replacement for a real metrics backend, but enough to see what a small deployment is doing.
 */
@Component
public class Metrics {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Metrics.class);


    private final Instant start = Instant.now();
    private final RunService runService; // may be null in unit tests

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> toolCalls = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> byKey = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> byEndpoint = new ConcurrentHashMap<>();

    private final AtomicLong runLatencyCount = new AtomicLong();
    private final AtomicLong runLatencyTotalMs = new AtomicLong();
    private final AtomicLong runLatencyMaxMs = new AtomicLong();
    private final AtomicLong approxOutputChars = new AtomicLong();
    private final RunHistory history = new RunHistory(200);

    private final RunHistoryStore historyStore; // may be null in unit tests

    public Metrics(RunService runService, RunHistoryStore historyStore) {
        this.runService = runService;
        this.historyStore = historyStore;
    }

    @jakarta.annotation.PostConstruct
    public void loadHistory() {
        if (historyStore == null) return;
        for (RunHistory.Record r : historyStore.loadRecent(200)) history.add(r); // oldest-first -> newest last
    }

    public void inc(String name) {
        counters.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    // --- per-run context-management tally (folds/compactions/trims attributed to the active run) ---
    // The run loop, recordRun, and these notes all run on the same thread, so a ThreadLocal cleanly
    // attributes events to the current run. Sub-agent work on child threads is not attributed (it rolls
    // up only into the global counters). Index 0=folds, 1=compactions, 2=trims.
    private final ThreadLocal<long[]> runCtx = ThreadLocal.withInitial(() -> new long[3]);
    private final ThreadLocal<java.util.List<String>> runEvents = ThreadLocal.withInitial(java.util.ArrayList::new);
    private static final int MAX_RUN_EVENTS = 100; // cap so one pathological run can't grow unbounded

    private void noteCtx(int idx, String counter) {
        inc(counter);
        runCtx.get()[idx]++;
    }
    /** Append a context-timeline event line for the current run (persisted with the run record). */
    public void noteEvent(String line) {
        if (line == null) return;
        java.util.List<String> ev = runEvents.get();
        if (ev.size() < MAX_RUN_EVENTS) ev.add(line);
    }
    /** A context fold happened on the current run's thread. */
    public void noteFold() { noteCtx(0, "context_fold"); }
    public void noteFold(String event) { noteFold(); noteEvent(event); }
    /** A history compaction happened on the current run's thread. */
    public void noteCompact() { noteCtx(1, "context_compact"); }
    public void noteCompact(String event) { noteCompact(); noteEvent(event); }
    /** A budget trim happened on the current run's thread. */
    public void noteTrim() { noteCtx(2, "context_trim"); }
    public void noteTrim(String event) { noteTrim(); noteEvent(event); }

    public void incTool(String name) {
        inc("tool_calls");
        toolCalls.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    public void incKey(String label) {
        if (label != null) byKey.computeIfAbsent(label, k -> new LongAdder()).increment();
    }

    public void addModelOutput(int chars) {
        if (chars > 0) approxOutputChars.addAndGet(chars);
    }

    public void recordRun(long ms, boolean ok) {
        inc(ok ? "runs_ok" : "runs_failed");
        runLatencyCount.incrementAndGet();
        runLatencyTotalMs.addAndGet(ms);
        runLatencyMaxMs.accumulateAndGet(ms, Math::max);
    }

    /** Record a run AND append it to the run-history ring buffer (endpoint/session/mode for the dashboard). */
    public void recordRun(String endpoint, String session, String mode, long ms, boolean ok) {
        recordRun(ms, ok);
        if (endpoint != null) byEndpoint.computeIfAbsent(endpoint, k -> new LongAdder()).increment();
        long[] ctx = runCtx.get();
        java.util.List<String> events = runEvents.get();
        runCtx.remove();    // reset for the next run on this (possibly pooled) thread
        runEvents.remove();
        RunHistory.Record r = new RunHistory.Record(System.currentTimeMillis(), endpoint, session, mode, ms, ok,
                (int) ctx[0], (int) ctx[1], (int) ctx[2], new java.util.ArrayList<>(events));
        history.add(r);
        if (historyStore != null) historyStore.append(r); // durable across restarts
    }

    /** Most recent runs (newest first) as plain maps, for the admin run-history view. */
    public java.util.List<java.util.Map<String, Object>> recentRuns(int n) {
        return history.recentMaps(n);
    }

    /** Most recent runs (newest first) matching endpoint/outcome/session filters (blank = any). */
    public java.util.List<java.util.Map<String, Object>> recentRuns(int n, String endpoint, String outcome, String session) {
        return history.recentMaps(n, endpoint, outcome, session);
    }

    /** Most recent runs (newest first) for exactly one session. */
    public java.util.List<java.util.Map<String, Object>> recentRunsForSession(int n, String sessionId) {
        return history.recentMapsForSession(n, sessionId);
    }

    /** Structured one-line run log for tailing/grep. */
    public void logRun(String endpoint, String sessionId, String keyLabel, long ms, boolean ok) {
        log.info("[metrics] run endpoint=" + endpoint + " session=" + sessionId
                + " key=" + (keyLabel == null ? "-" : keyLabel) + " ms=" + ms + " ok=" + ok);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uptime_ms", Instant.now().toEpochMilli() - start.toEpochMilli());

        Map<String, Long> c = new TreeMap<>();
        counters.forEach((k, v) -> c.put(k, v.sum()));
        out.put("counters", c);

        Map<String, Long> tools = new TreeMap<>();
        toolCalls.forEach((k, v) -> tools.put(k, v.sum()));
        out.put("tool_calls_by_name", tools);

        Map<String, Long> keys = new TreeMap<>();
        byKey.forEach((k, v) -> keys.put(k, v.sum()));
        out.put("requests_by_key", keys);

        Map<String, Long> eps = new TreeMap<>();
        byEndpoint.forEach((k, v) -> eps.put(k, v.sum()));
        out.put("runs_by_endpoint", eps);

        long n = runLatencyCount.get();
        Map<String, Object> lat = new LinkedHashMap<>();
        lat.put("count", n);
        lat.put("avg_ms", n == 0 ? 0 : runLatencyTotalMs.get() / n);
        lat.put("max_ms", runLatencyMaxMs.get());
        out.put("run_latency", lat);

        out.put("approx_output_tokens", approxOutputChars.get() / 4); // ~4 chars/token, approximate

        // Unified context-management summary (folds, compactions, budget trims) for the timeline view.
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("folds", c.getOrDefault("context_fold", 0L));
        ctx.put("fold_fallbacks", c.getOrDefault("context_fold_fallback", 0L));
        ctx.put("compactions", c.getOrDefault("context_compact", 0L));
        ctx.put("trims", c.getOrDefault("context_trim", 0L));
        out.put("context", ctx);

        if (runService != null) {
            Map<String, Object> conc = new LinkedHashMap<>();
            conc.put("limit", runService.limit());
            conc.put("active", runService.active());
            conc.put("queued", runService.queued());
            out.put("concurrency", conc);
        }
        return out;
    }
}
