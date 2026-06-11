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

    private final AtomicLong runLatencyCount = new AtomicLong();
    private final AtomicLong runLatencyTotalMs = new AtomicLong();
    private final AtomicLong runLatencyMaxMs = new AtomicLong();
    private final AtomicLong approxOutputChars = new AtomicLong();

    public Metrics(RunService runService) {
        this.runService = runService;
    }

    public void inc(String name) {
        counters.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

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

        long n = runLatencyCount.get();
        Map<String, Object> lat = new LinkedHashMap<>();
        lat.put("count", n);
        lat.put("avg_ms", n == 0 ? 0 : runLatencyTotalMs.get() / n);
        lat.put("max_ms", runLatencyMaxMs.get());
        out.put("run_latency", lat);

        out.put("approx_output_tokens", approxOutputChars.get() / 4); // ~4 chars/token, approximate

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
