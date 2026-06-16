package com.example.imini;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory store of staged patch previews (per session). A preview is a list of <b>hunks</b> -- one per
 * {@code apply_patch} edit, each independently applicable -- plus a rendered diff. It writes NOTHING
 * until {@code apply_previewed_patch} re-validates and applies the selected hunks. Ephemeral by design.
 */
@Component
public class PreviewStore {

    /** One independently-applicable edit within a preview, with its own rendered diff. */
    public record Hunk(int index, String path, String kind, int added, int removed, String diff,
                       Map<String, String> edit) {}

    /** A staged, not-yet-applied patch as a set of hunks. */
    public record Preview(String id, String sessionId, long ts, String summary, String diff,
                          List<Hunk> hunks) {
        /** The raw edits (one per hunk), for (re-)applying. */
        public List<Map<String, String>> edits() {
            List<Map<String, String>> out = new ArrayList<>();
            for (Hunk h : hunks) out.add(h.edit());
            return out;
        }
    }

    private final Map<String, List<Preview>> bySession = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    public synchronized Preview stage(String sessionId, String summary, String diff, List<Hunk> hunks) {
        String id = "pv-" + seq.getAndIncrement();
        Preview p = new Preview(id, sessionId, System.currentTimeMillis(), summary, diff,
                new ArrayList<>(hunks));
        bySession.computeIfAbsent(key(sessionId), k -> new ArrayList<>()).add(p);
        return p;
    }

    public synchronized List<Preview> listFor(String sessionId) {
        return new ArrayList<>(bySession.getOrDefault(key(sessionId), List.of()));
    }

    /** Get a specific preview, or the most recent one when id is null/blank/"latest". */
    public synchronized Preview get(String sessionId, String id) {
        List<Preview> list = bySession.get(key(sessionId));
        if (list == null || list.isEmpty()) return null;
        if (id == null || id.isBlank() || id.equals("latest")) return list.get(list.size() - 1);
        for (Preview p : list) if (p.id().equals(id)) return p;
        return null;
    }

    /** Replace a preview's hunks in place (same id/ts/position), or remove it when no hunks remain. */
    public synchronized void replaceHunks(String sessionId, String id, String summary, String diff,
                                          List<Hunk> remaining) {
        List<Preview> list = bySession.get(key(sessionId));
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) {
                if (remaining == null || remaining.isEmpty()) {
                    list.remove(i);
                } else {
                    Preview old = list.get(i);
                    list.set(i, new Preview(id, old.sessionId(), old.ts(), summary, diff,
                            new ArrayList<>(remaining)));
                }
                return;
            }
        }
    }

    public synchronized boolean discard(String sessionId, String id) {
        List<Preview> list = bySession.get(key(sessionId));
        if (list == null) return false;
        if (id == null || id.isBlank() || id.equals("latest")) {
            if (list.isEmpty()) return false;
            list.remove(list.size() - 1);
            return true;
        }
        return list.removeIf(p -> p.id().equals(id));
    }

    private static String key(String sessionId) {
        return sessionId == null ? "default" : sessionId;
    }
}
