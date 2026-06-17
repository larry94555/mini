package com.example.imini;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A bounded, in-memory ring buffer of recent run records for the admin "run history" view. Pure data
 * structure (no Spring/IO): newest records are kept up to a capacity; older ones drop off. Thread-safe
 * via simple synchronization so {@link Metrics} can append from request threads while the dashboard reads.
 */
public final class RunHistory {

    /** One finished run: when, which endpoint/session, the resolved mode, duration, and outcome. */
    public record Record(long ts, String endpoint, String session, String mode, long ms, boolean ok) {}

    private final int capacity;
    private final Deque<Record> records = new ArrayDeque<>();

    public RunHistory(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** Append a record; drops the oldest when over capacity. */
    public synchronized void add(Record r) {
        if (r == null) return;
        records.addLast(r);
        while (records.size() > capacity) records.removeFirst();
    }

    /** The most recent {@code n} records, newest first. */
    public synchronized List<Record> recent(int n) {
        int want = Math.max(0, n);
        List<Record> all = new ArrayList<>(records);
        List<Record> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < want; i--) out.add(all.get(i));
        return out;
    }

    /** Recent records as plain maps (newest first), for JSON responses. */
    public synchronized List<Map<String, Object>> recentMaps(int n) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Record r : recent(n)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", r.ts());
            m.put("endpoint", r.endpoint());
            m.put("session", r.session());
            m.put("mode", r.mode());
            m.put("ms", r.ms());
            m.put("ok", r.ok());
            out.add(m);
        }
        return out;
    }

    public synchronized int size() {
        return records.size();
    }
}
