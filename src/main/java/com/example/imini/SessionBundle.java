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

    public static final String VERSION = "imini-session/3";

    private SessionBundle() {}

    /** Assemble a bundle map (caller passes the timestamp so this stays pure/testable). */
    public static Map<String, Object> build(String sessionId, String owner, long exportedAt,
                                            List<Map<String, Object>> messages,
                                            List<Map<String, Object>> plans, List<TodoStore.Item> todos) {
        return build(sessionId, owner, exportedAt, messages, plans, todos, List.of());
    }

    /** Assemble a bundle, including a session's per-session skill overrides ({@code [{name,enabled}]}). */
    public static Map<String, Object> build(String sessionId, String owner, long exportedAt,
                                            List<Map<String, Object>> messages,
                                            List<Map<String, Object>> plans, List<TodoStore.Item> todos,
                                            List<Map<String, Object>> skillOverrides) {
        return build(sessionId, owner, exportedAt, messages, plans, todos, skillOverrides, List.of());
    }

    /** Assemble a bundle including skill overrides and the session's reader list ({@code [name,...]}). */
    public static Map<String, Object> build(String sessionId, String owner, long exportedAt,
                                            List<Map<String, Object>> messages,
                                            List<Map<String, Object>> plans, List<TodoStore.Item> todos,
                                            List<Map<String, Object>> skillOverrides, List<String> readers) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", VERSION);
        m.put("sessionId", sessionId == null ? "" : sessionId);
        m.put("owner", owner == null ? "" : owner);
        m.put("exportedAt", exportedAt);
        m.put("messages", messages == null ? List.of() : messages);
        m.put("plans", plans == null ? List.of() : plans);
        m.put("todos", todoPayload(todos));
        m.put("skillOverrides", skillOverrides == null ? List.of() : skillOverrides);
        m.put("readers", readers == null ? List.of() : readers);
        return m;
    }

    /** True if this build understands the bundle's (major) version. */
    public static boolean supports(String version) {
        return version != null
                && (version.startsWith("imini-session/1") || version.startsWith("imini-session/2")
                    || version.startsWith("imini-session/3"));
    }

    /**
     * Normalize an older / looser bundle into the current shape (pure). Handles: a missing or legacy
     * ({@code imini-session/0}) version (stamped to {@link #VERSION} after transforms); a {@code history}
     * alias for {@code messages}; and {@code todos} given as plain strings (wrapped to
     * {@code {content, status:"pending"}}). A bundle already at the current version is returned
     * essentially unchanged. Run this AFTER verifying integrity (which is over the bundle as received).
     */
    public static Map<String, Object> migrate(Map<String, Object> bundle) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (bundle != null) b.putAll(bundle);
        String version = b.get("version") == null ? "" : String.valueOf(b.get("version"));
        boolean legacy = version.isBlank() || version.equals("imini-session/0")
                || version.startsWith("imini-session/1")  // pre-overrides
                || version.startsWith("imini-session/2"); // pre-readers -> upconvert to current

        if (!b.containsKey("messages") && b.get("history") instanceof List) {
            b.put("messages", b.remove("history")); // legacy alias
            legacy = true;
        }
        Object todos = b.get("todos");
        if (todos instanceof List<?> l) {
            List<Map<String, Object>> norm = new ArrayList<>();
            boolean changed = false;
            for (Object e : l) {
                if (e instanceof Map<?, ?> m) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("content", m.get("content") == null ? "" : String.valueOf(m.get("content")));
                    t.put("status", m.get("status") == null ? "pending" : String.valueOf(m.get("status")));
                    norm.add(t);
                } else { // a plain string -> wrap it
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("content", e == null ? "" : String.valueOf(e));
                    t.put("status", "pending");
                    norm.add(t);
                    changed = true;
                }
            }
            if (changed) b.put("todos", norm);
        }
        if (!b.containsKey("skillOverrides")) b.put("skillOverrides", List.of());
        if (!b.containsKey("readers")) b.put("readers", List.of());
        if (legacy) b.put("version", VERSION);
        return b;
    }

    /** The content to hash for integrity: everything except the volatile/derived fields. Pure. */
    public static Map<String, Object> contentForHash(Map<String, Object> bundle) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", bundle == null ? "" : bundle.getOrDefault("version", ""));
        m.put("sessionId", bundle == null ? "" : bundle.getOrDefault("sessionId", ""));
        m.put("messages", messages(bundle));
        m.put("plans", plans(bundle));
        m.put("todos", bundle == null ? List.of() : bundle.getOrDefault("todos", List.of()));
        String version = bundle == null ? "" : String.valueOf(bundle.getOrDefault("version", ""));
        boolean v2plus = version.startsWith("imini-session/2") || version.startsWith("imini-session/3");
        if (v2plus) { // v1 bundles hashed without this field
            m.put("skillOverrides", bundle.getOrDefault("skillOverrides", List.of()));
        }
        if (version.startsWith("imini-session/3")) { // v1/v2 bundles hashed without readers
            m.put("readers", bundle.getOrDefault("readers", List.of()));
        }
        return m;
    }

    /** Per-session skill overrides carried by the bundle: {@code [{name, enabled}]}. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> skillOverrides(Map<String, Object> b) {
        Object o = b == null ? null : b.get("skillOverrides");
        return o instanceof List ? (List<Map<String, Object>>) o : List.of();
    }

    /** The session's reader (shared-with) usernames carried by the bundle. */
    public static List<String> readers(Map<String, Object> b) {
        List<String> out = new ArrayList<>();
        Object o = b == null ? null : b.get("readers");
        if (o instanceof List<?> l) for (Object e : l) if (e != null) out.add(String.valueOf(e));
        return out;
    }

    /** The stored integrity hash (or "" if none). */
    public static String integrity(Map<String, Object> bundle) {
        Object v = bundle == null ? null : bundle.get("integrity");
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * Project what an import would do, without applying it (pure). Given the destination's current
     * counts and the incoming bundle's counts, returns before/incoming/after for each section under the
     * chosen mode: {@code merge} appends messages (others overwrite), {@code todos} are set in every
     * mode, and plans are always appended to history. For {@code new}, callers pass current counts of 0.
     */
    public static Map<String, Object> preview(String mode, int curMsgs, int curTodos, int curPlans,
                                               int inMsgs, int inTodos, int inPlans) {
        String m = mode == null ? "new" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        int outMsgs = "merge".equals(m) ? curMsgs + inMsgs : inMsgs; // replace/new overwrite
        int outTodos = inTodos;            // todos.set in all modes
        int outPlans = curPlans + inPlans; // plans appended
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", m);
        out.put("messages", section(curMsgs, inMsgs, outMsgs));
        out.put("todos", section(curTodos, inTodos, outTodos));
        out.put("plans", section(curPlans, inPlans, outPlans));
        return out;
    }

    private static Map<String, Object> section(int before, int incoming, int after) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("before", before);
        s.put("incoming", incoming);
        s.put("after", after);
        return s;
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
