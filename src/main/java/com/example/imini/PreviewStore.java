package com.example.imini;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory store of staged patch previews (per session). A preview holds the raw edits plus a rendered
 * diff, but writes NOTHING until {@code apply_previewed_patch} re-validates and applies it. Ephemeral by
 * design -- a preview is a "look before you leap" artifact, not durable state.
 */
@Component
public class PreviewStore {

    /** A staged, not-yet-applied patch. */
    public record Preview(String id, String sessionId, long ts, String summary, String diff,
                          List<Map<String, String>> edits) {}

    private final Map<String, List<Preview>> bySession = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    public synchronized Preview stage(String sessionId, String summary, String diff,
                                      List<Map<String, String>> edits) {
        String id = "pv-" + seq.getAndIncrement();
        Preview p = new Preview(id, sessionId, System.currentTimeMillis(), summary, diff, edits);
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
