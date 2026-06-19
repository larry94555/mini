package com.example.imini;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A minimal, dependency-free distributed tracer that follows the OpenTelemetry data model: each operation
 * is a {@link Span} with a 128-bit {@code traceId}, a 64-bit {@code spanId}, an optional {@code parentId},
 * a name, start/end timestamps, string attributes, and a status. IDs are W3C Trace Context hex, so a span
 * can be correlated with the {@code traceparent} header and exported to any OTLP backend.
 *
 * <p>We deliberately do NOT depend on the OpenTelemetry SDK — it isn't on the (intentionally tiny)
 * classpath, and for a teaching harness an explicit ~150-line tracer shows exactly what a span is. Spans
 * are kept in a bounded in-memory ring (for {@code GET /admin/traces}) and, when persistence is on,
 * appended to the {@code trace_spans} table. The current trace/span context lives in a ThreadLocal so
 * nested spans nest correctly within a run.
 *
 * <p>Enable with {@code tracing.enabled=true} (default false — zero overhead when off).
 */
@Component
public class Tracer {

    private static final Logger log = LoggerFactory.getLogger(Tracer.class);

    @Value("${tracing.enabled:false}") private boolean enabled;
    @Value("${tracing.persist:true}") private boolean persist;
    @Value("${tracing.ring-size:512}") private int ringSize;

    private final Database db;

    // Bounded in-memory ring of finished spans (newest appended).
    private final Deque<Span> ring = new ArrayDeque<>();
    private final Object ringLock = new Object();

    // Per-thread stack of open spans, so child spans pick up the right parent + trace id.
    private final ThreadLocal<Deque<Span>> stack = ThreadLocal.withInitial(ArrayDeque::new);

    public Tracer(Database db) {
        this.db = db;
    }

    public boolean enabled() { return enabled; }

    /** A single span. Mutable while open; immutable in spirit once {@link #end} is called. */
    public final class Span implements AutoCloseable {
        final String traceId;
        final String spanId;
        final String parentId;
        final String name;
        final long startMs;
        long endMs;
        String status = "OK";
        final Map<String, String> attributes = new LinkedHashMap<>();

        Span(String traceId, String spanId, String parentId, String name, long startMs) {
            this.traceId = traceId; this.spanId = spanId; this.parentId = parentId;
            this.name = name; this.startMs = startMs;
        }

        public Span attr(String k, String v) { if (k != null && v != null) attributes.put(k, v); return this; }
        public Span attr(String k, long v) { attributes.put(k, Long.toString(v)); return this; }
        public Span error(String message) { this.status = "ERROR"; if (message != null) attributes.put("error", message); return this; }

        public String traceId() { return traceId; }
        public String spanId() { return spanId; }

        /** W3C traceparent header value for propagating this context to a downstream service. */
        public String traceparent() {
            return "00-" + traceId + "-" + spanId + "-01";
        }

        public void end() {
            if (endMs != 0) return; // idempotent
            endMs = System.currentTimeMillis();
            Tracer.this.finish(this);
        }

        @Override public void close() { end(); }
    }

    /**
     * Start a span. If a span is already open on this thread it becomes the parent (same trace); otherwise
     * a new trace is started. When tracing is disabled this returns a no-op span so callers can use
     * try-with-resources unconditionally.
     */
    public Span start(String name) {
        if (!enabled) return noop(name);
        Deque<Span> s = stack.get();
        Span parent = s.peek();
        String traceId = parent != null ? parent.traceId : hex(16);
        String spanId = hex(8);
        Span span = new Span(traceId, spanId, parent != null ? parent.spanId : null, name,
                System.currentTimeMillis());
        s.push(span);
        return span;
    }

    private Span noop(String name) {
        // A detached span not pushed on the stack; end() is a cheap no-op because enabled==false guards finish.
        return new Span("0".repeat(32), "0".repeat(16), null, name, System.currentTimeMillis());
    }

    private void finish(Span span) {
        if (!enabled) return;
        Deque<Span> s = stack.get();
        if (!s.isEmpty() && s.peek() == span) s.pop();
        synchronized (ringLock) {
            ring.addLast(span);
            while (ring.size() > Math.max(16, ringSize)) ring.removeFirst();
        }
        if (persist && db.available()) {
            try {
                db.update("INSERT INTO trace_spans(span_id, trace_id, parent_id, name, start_ms, end_ms, "
                                + "attributes, status) VALUES(?,?,?,?,?,?,?,?)",
                        span.spanId, span.traceId, span.parentId, span.name, span.startMs, span.endMs,
                        attrsToJson(span.attributes), span.status);
            } catch (Exception e) {
                log.warn("[trace] persist failed: " + e.getMessage());
            }
        }
    }

    /** Recent spans (newest first) as OTLP-ish maps, for GET /admin/traces. */
    public List<Map<String, Object>> recent(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (ringLock) {
            var it = ring.descendingIterator();
            while (it.hasNext() && out.size() < limit) out.add(toMap(it.next()));
        }
        return out;
    }

    private static Map<String, Object> toMap(Span s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traceId", s.traceId);
        m.put("spanId", s.spanId);
        m.put("parentId", s.parentId);
        m.put("name", s.name);
        m.put("startMs", s.startMs);
        m.put("endMs", s.endMs);
        m.put("durationMs", s.endMs - s.startMs);
        m.put("status", s.status);
        m.put("attributes", new LinkedHashMap<>(s.attributes));
        return m;
    }

    // --- helpers -------------------------------------------------------------

    /** Random lowercase hex of {@code bytes} bytes (16 -> 32 chars for trace id, 8 -> 16 for span id). */
    static String hex(int bytes) {
        byte[] b = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    /** Tiny JSON object encoder for the attribute map (string values, escaped). */
    static String attrsToJson(Map<String, String> attrs) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(esc(e.getKey())).append("\":\"").append(esc(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
