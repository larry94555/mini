package com.example.imini;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and validates a portable session "bundle" -- a JSON-able snapshot of a session's conversation,
 * plan history (steps + tools + reports), and todos -- for export to a file and import into a fresh
 * session (possibly on another instance). All assembly/validation/extraction here is pure and
 * unit-testable; the controller wires it to the stores.
 */
public final class SessionBundle {

    public static final String VERSION = "imini-session/1";

    private SessionBundle() {}

    /** Assemble a bundle map (caller passes the timestamp so this stays pure/testable). */
    public static Map<String, Object> build(String sessionId, String owner, long exportedAt,
                                            List<Map<String, Object>> messages,
                                            List<Map<String, Object>> plans, List<TodoStore.Item> todos) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", VERSION);
        m.put("sessionId", sessionId == null ? "" : sessionId);
        m.put("owner", owner == null ? "" : owner);
        m.put("exportedAt", exportedAt);
        m.put("messages", messages == null ? List.of() : messages);
        m.put("plans", plans == null ? List.of() : plans);
        m.put("todos", todoPayload(todos));
        return m;
    }

    public static List<Map<String, Object>> todoPayload(List<TodoStore.Item> todos) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (todos != null) {
            for (TodoStore.Item it : todos) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("content", it.content());
                t.put("status", it.status());
                out.add(t);
            }
        }
        return out;
    }

    /** Problems with a bundle (empty list == valid enough to import). */
    public static List<String> validate(Map<String, Object> bundle) {
        List<String> problems = new ArrayList<>();
        if (bundle == null || bundle.isEmpty()) {
            problems.add("bundle is empty");
            return problems;
        }
        Object v = bundle.get("version");
        if (v == null || !String.valueOf(v).startsWith("imini-session/")) {
            problems.add("missing or unrecognized version (expected " + VERSION + ")");
        }
        if (bundle.get("messages") != null && !(bundle.get("messages") instanceof List)) {
            problems.add("messages must be a list");
        }
        return problems;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> messages(Map<String, Object> b) {
        Object o = b == null ? null : b.get("messages");
        return o instanceof List ? (List<Map<String, Object>>) o : List.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> plans(Map<String, Object> b) {
        Object o = b == null ? null : b.get("plans");
        return o instanceof List ? (List<Map<String, Object>>) o : List.of();
    }

    public static List<TodoStore.Item> todos(Map<String, Object> b) {
        List<TodoStore.Item> out = new ArrayList<>();
        Object o = b == null ? null : b.get("todos");
        if (o instanceof List<?> l) {
            for (Object e : l) {
                if (e instanceof Map<?, ?> m) {
                    out.add(new TodoStore.Item(str(m.get("content")), str(m.get("status"))));
                }
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
