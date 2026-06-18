package com.example.imini;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Durable, cross-session memory: one persistent {@code [MEMORY]} note per owner, stored in the
 * {@code memory} table. Unlike a session's in-conversation memory (which lives in that session's message
 * list), this note carries durable facts across <em>different</em> sessions and survives restarts. A new
 * session is seeded from it, and after a run the session's current memory note is written back here, so
 * knowledge accumulates over time. Falls back to a no-op / empty when persistence is unavailable.
 */
@Component
public class MemoryStore {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MemoryStore.class);

    static final String DEFAULT_OWNER = "local";
    private static volatile String WS_ID; // cached workspace id (hash of the working directory)

    private final Database db;
    private final RetrievalService retrieval;
    private final LlamaClient llama;
    @Value("${agent.memory-inject-max:12}") private int injectMax; // max durable facts seeded per session
    @Value("${agent.memory-recall-k:6}") private int recallK;      // default facts returned by recall_memory
    @Value("${agent.memory-recall-shortlist:12}") private int recallShortlist; // 1st-stage candidate count
    @Value("${agent.memory-rerank:true}") private boolean rerank;  // 2nd-stage model rerank of recall
    @Value("${agent.memory-decay-days:30}") private int decayDays; // age out unused facts older than this

    public MemoryStore(Database db, RetrievalService retrieval, LlamaClient llama) {
        this.db = db;
        this.retrieval = retrieval;
        this.llama = llama;
    }

    /**
     * A stable, short id for the current workspace (derived from the working directory), so durable memory
     * is scoped per project rather than shared across every repo a single owner touches.
     */
    public static String workspaceId() {
        String v = WS_ID;
        if (v != null) return v;
        String path = java.nio.file.Path.of("").toAbsolutePath().normalize().toString();
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", h[i]));
            v = sb.toString();
        } catch (Exception e) {
            v = Integer.toHexString(path.hashCode());
        }
        WS_ID = v;
        return v;
    }

    /** The storage key: owner scoped to the current workspace, e.g. {@code local@1a2b3c4d5e6f}. */
    private static String key(String owner) {
        String o = (owner == null || owner.isBlank()) ? DEFAULT_OWNER : owner;
        return o + "@" + workspaceId();
    }

    /** The auto durable memory note for an owner (updated from compactions), or null if none. */
    public String get(String owner) {
        if (!db.available()) return null;
        try {
            List<String> rows = db.query("SELECT note FROM memory WHERE owner=?",
                    rs -> rs.getString(1), key(owner));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("[memory] load failed: " + e.getMessage());
            return null;
        }
    }

    /** Curated, pinned facts for an owner (never overwritten by auto write-back), or "" if none. */
    public String pinned(String owner) {
        if (!db.available()) return "";
        try {
            List<String> facts = db.query(
                    "SELECT fact FROM memory_pins WHERE scope=? ORDER BY created_at, rowid",
                    rs -> rs.getString(1), key(owner));
            return String.join("\n", facts);
        } catch (Exception e) {
            return "";
        }
    }

    /** Pinned facts with provenance: {@code [{fact, source, createdAt}]}, oldest first. */
    public java.util.List<Map<String, Object>> pinsDetailed(String owner) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (!db.available()) return out;
        try {
            return db.query(
                    "SELECT fact, source, created_at FROM memory_pins WHERE scope=? ORDER BY created_at, rowid",
                    rs -> {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("fact", rs.getString(1));
                        m.put("source", rs.getString(2));
                        m.put("createdAt", rs.getLong(3));
                        return m;
                    }, key(owner));
        } catch (Exception e) {
            return out;
        }
    }

    /**
     * The memory actually seeded into a new session: pinned facts first, then the auto note, with
     * duplicate lines removed (case-insensitive). Pinned facts win and are kept verbatim.
     */
    public String effective(String owner) {
        String pinned = pinned(owner);
        String note = get(owner);
        return dedupeLines((pinned == null ? "" : pinned) + "\n" + (note == null ? "" : note));
    }

    /** Drop blank/duplicate lines (case-insensitive), preserving first occurrence and order. */
    static String dedupeLines(String text) {
        if (text == null) return "";
        java.util.LinkedHashMap<String, String> seen = new java.util.LinkedHashMap<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            seen.putIfAbsent(line.toLowerCase(java.util.Locale.ROOT), line);
        }
        return String.join("\n", seen.values());
    }

    /** Store (replace) the AUTO durable memory note for an owner. Blank notes are ignored so an empty
     *  compaction never wipes a curated note; use {@link #setNote} for manual edits that may clear it. */
    public void save(String owner, String note) {
        if (!db.available() || note == null || note.isBlank()) return;
        upsertNote(owner, note);
    }

    /** Manually set the AUTO note exactly (may be empty, to clear the auto part while keeping pins). */
    public void setNote(String owner, String note) {
        if (!db.available()) return;
        upsertNote(owner, note == null ? "" : note);
    }

    private void upsertNote(String owner, String note) {
        try {
            db.update("INSERT INTO memory(owner, note, updated_at) VALUES(?,?,?) "
                            + "ON CONFLICT(owner) DO UPDATE SET note=excluded.note, updated_at=excluded.updated_at",
                    key(owner), note, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("[memory] save failed: " + e.getMessage());
        }
    }

    /** Pin a fact with provenance (no-op if already pinned, case-insensitive on the fact). */
    public void addPin(String owner, String fact, String source) {
        if (!db.available() || fact == null || fact.isBlank()) return;
        String src = (source == null || source.isBlank()) ? "manual" : source.strip();
        try {
            db.update("INSERT OR IGNORE INTO memory_pins(scope, fact, source, created_at) VALUES(?,?,?,?)",
                    key(owner), fact.strip(), src, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("[memory] pin failed: " + e.getMessage());
        }
    }

    /** Remove a pinned fact (exact line match, case-insensitive). */
    public void removePin(String owner, String fact) {
        if (!db.available() || fact == null) return;
        try {
            db.update("DELETE FROM memory_pins WHERE scope=? AND lower(fact)=lower(?)", key(owner), fact.strip());
        } catch (Exception e) {
            log.warn("[memory] unpin failed: " + e.getMessage());
        }
    }

    /** Merge in a list of pins with provenance (used by bundle import); existing pins are kept. */
    public void importPins(String owner, java.util.List<Map<String, Object>> pins) {
        if (!db.available() || pins == null) return;
        for (Map<String, Object> p : pins) {
            Object f = p.get("fact");
            if (f == null || String.valueOf(f).isBlank()) continue;
            Object src = p.get("source");
            Object ts = p.get("createdAt");
            long created = (ts instanceof Number) ? ((Number) ts).longValue() : System.currentTimeMillis();
            try {
                db.update("INSERT OR IGNORE INTO memory_pins(scope, fact, source, created_at) VALUES(?,?,?,?)",
                        key(owner), String.valueOf(f).strip(),
                        src == null ? "imported" : String.valueOf(src), created);
            } catch (Exception e) {
                log.warn("[memory] import pin failed: " + e.getMessage());
            }
        }
    }

    /**
     * The memory to seed into a new session, RELEVANCE-RANKED to a query: all pinned facts (always kept),
     * plus the top auto-note facts scored by lexical overlap with the query, capped at
     * {@code agent.memory-inject-max} total and de-duplicated. Reuses {@link RetrievalService}'s pure
     * lexical scorer so it needs no embedding server. With a blank query (or no overlap) it keeps the
     * first auto facts in note order.
     */
    public String relevantSeed(String owner, String query) {
        java.util.List<String> pins = splitLines(pinned(owner));
        java.util.Set<String> pinSet = new java.util.HashSet<>();
        for (String p : pins) pinSet.add(p.toLowerCase(java.util.Locale.ROOT));

        java.util.List<String> auto = new java.util.ArrayList<>();
        for (String l : splitLines(get(owner))) {
            if (!pinSet.contains(l.toLowerCase(java.util.Locale.ROOT))) auto.add(l);
        }
        touchFacts(owner, auto); // register every auto fact (first_seen) so hygiene can age unused ones
        // rank auto facts by the current retrieval mode (embeddings if enabled, else lexical)
        auto = new java.util.ArrayList<>(retrieval.rankTexts(query, auto));
        int room = Math.max(0, injectMax - pins.size());
        java.util.List<String> top = auto.subList(0, Math.min(room, auto.size()));
        java.util.List<String> combined = new java.util.ArrayList<>(pins);
        combined.addAll(top);
        noteUse(owner, combined, "injected"); // analytics: which facts were seeded into this session
        return dedupeLines(String.join("\n", combined));
    }

    /**
     * Recall durable facts relevant to a query, on demand (the recall_memory tool): pins + auto notes,
     * ranked by the current retrieval mode, top-k returned as a readable list. Records recall analytics.
     */
    public String recall(String owner, String query, int k) {
        java.util.List<String> facts = new java.util.ArrayList<>(splitLines(pinned(owner)));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String f : facts) seen.add(f.toLowerCase(java.util.Locale.ROOT));
        for (String l : splitLines(get(owner))) {
            if (seen.add(l.toLowerCase(java.util.Locale.ROOT))) facts.add(l);
        }
        if (facts.isEmpty()) return "(no durable memory stored yet)";
        int want = k > 0 ? k : recallK;

        // stage 1: cheap rank to a shortlist
        java.util.List<String> ranked = retrieval.rankTexts(query, facts);
        java.util.List<String> shortlist =
                new java.util.ArrayList<>(ranked.subList(0, Math.min(Math.max(want, recallShortlist), ranked.size())));

        // stage 2 (optional): let the summary model pick/order the most relevant from the shortlist
        java.util.List<String> topList = (rerank && shortlist.size() > want)
                ? rerankRecall(query, shortlist, want)
                : new java.util.ArrayList<>(shortlist.subList(0, Math.min(want, shortlist.size())));

        noteUse(owner, topList, "recalled");
        StringBuilder sb = new StringBuilder("Relevant durable memory:\n");
        for (String f : topList) sb.append("- ").append(f).append('\n');
        return sb.toString().strip();
    }

    /** Ask the summary model to choose the {@code k} most relevant candidates for the query; falls back to
     *  the shortlist's own order if the model is unavailable or its answer can't be parsed. */
    private java.util.List<String> rerankRecall(String query, java.util.List<String> candidates, int k) {
        try {
            StringBuilder numbered = new StringBuilder();
            for (int i = 0; i < candidates.size(); i++) {
                numbered.append(i + 1).append(". ").append(candidates.get(i)).append('\n');
            }
            java.util.List<Map<String, Object>> req = new java.util.ArrayList<>();
            req.add(Map.of("role", "system", "content",
                    "You select the most relevant memory facts for a query. Given a numbered list, reply with "
                            + "ONLY the numbers of the up to " + k + " most relevant facts, most relevant first, "
                            + "comma-separated (e.g. \"3,1,5\"). No other text."));
            req.add(Map.of("role", "user", "content", "QUERY: " + query + "\n\nFACTS:\n" + numbered));
            Map<String, Object> resp = llama.summaryChat(req);
            Object c = resp == null ? null : resp.get("content");
            java.util.List<String> picked = parseRerankSelection(c == null ? "" : String.valueOf(c), candidates, k);
            return picked.isEmpty()
                    ? new java.util.ArrayList<>(candidates.subList(0, Math.min(k, candidates.size())))
                    : picked;
        } catch (Exception e) {
            log.warn("[memory] recall rerank failed (" + e.getMessage() + "); using shortlist order");
            return new java.util.ArrayList<>(candidates.subList(0, Math.min(k, candidates.size())));
        }
    }

    /** Parse a "3,1,5"-style selection into facts (1-based indices into candidates), capped at k, deduped. */
    static java.util.List<String> parseRerankSelection(String modelOut, java.util.List<String> candidates, int k) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (modelOut == null) return out;
        java.util.LinkedHashSet<Integer> idx = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(modelOut);
        while (m.find() && idx.size() < k) {
            int i = Integer.parseInt(m.group());
            if (i >= 1 && i <= candidates.size()) idx.add(i);
        }
        for (int i : idx) out.add(candidates.get(i - 1));
        return out;
    }

    /**
     * Hygiene pass: age out auto-note facts that have been observed for longer than {@code memory-decay-days}
     * yet were never injected into a session or recalled by the tool. Pinned facts are never pruned. Returns
     * a report {pruned:[...], kept:n, decayDays}.
     */
    public Map<String, Object> hygiene(String owner) {
        Map<String, Object> report = new java.util.LinkedHashMap<>();
        java.util.List<String> pruned = new java.util.ArrayList<>();
        java.util.List<String> autoLines = splitLines(get(owner));
        report.put("decayDays", decayDays);
        if (!db.available() || autoLines.isEmpty()) {
            report.put("pruned", pruned);
            report.put("kept", autoLines.size());
            return report;
        }
        long now = System.currentTimeMillis();
        long decayMs = (long) decayDays * 24L * 60L * 60L * 1000L;
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (String line : autoLines) {
            long[] stat = statFor(owner, line); // [injected, recalled, firstSeen]
            if (shouldDecay(stat[0], stat[1], stat[2], now, decayMs)) pruned.add(line);
            else kept.add(line);
        }
        if (!pruned.isEmpty()) {
            setNote(owner, String.join("\n", kept));
            for (String p : pruned) {
                try { db.update("DELETE FROM memory_stats WHERE scope=? AND fact=?", key(owner), p); }
                catch (Exception ignore) { }
            }
            log.info("[memory] hygiene pruned " + pruned.size() + " unused fact(s) older than "
                    + decayDays + "d");
        }
        report.put("pruned", pruned);
        report.put("kept", kept.size());
        return report;
    }

    private long[] statFor(String owner, String fact) {
        try {
            List<long[]> rows = db.query(
                    "SELECT injected, recalled, COALESCE(first_seen,0) FROM memory_stats WHERE scope=? AND fact=?",
                    rs -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}, key(owner), fact.strip());
            return rows.isEmpty() ? new long[]{0, 0, 0} : rows.get(0);
        } catch (Exception e) {
            return new long[]{0, 0, 0};
        }
    }

    /** Pure decay rule: never used (0 injected, 0 recalled) and observed longer ago than the decay window. */
    static boolean shouldDecay(long injected, long recalled, long firstSeen, long now, long decayMs) {
        if (injected > 0 || recalled > 0) return false; // it has earned its place
        if (firstSeen <= 0) return false;               // never observed/aged yet -> keep
        return (now - firstSeen) > decayMs;
    }

    /** A tool the agent can call mid-conversation to recall durable facts relevant to a query. */
    public Tool recallTool() {
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("query", Map.of("type", "string", "description",
                "What to recall from durable cross-session memory (a topic, preference, or past decision)."));
        props.put("k", Map.of("type", "integer", "description", "How many facts to return (optional)."));
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("query"));
        return new Tool("recall_memory",
                "Recall durable facts learned across earlier sessions (pinned facts and consolidated notes) "
                        + "that are relevant to a query. Use when you need a previously-learned preference, "
                        + "decision, fact, or convention that may not be in the current conversation.",
                schema, false, args -> {
            Object q = args.get("query");
            int k = args.get("k") instanceof Number n ? n.intValue() : recallK;
            return recall(DEFAULT_OWNER, q == null ? "" : String.valueOf(q), k);
        });
    }

    /** Increment an analytics counter ({@code injected} or {@code recalled}) for each fact. */
    private void noteUse(String owner, java.util.List<String> facts, String col) {
        if (!db.available() || facts == null || facts.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (String f : facts) {
            if (f == null || f.isBlank()) continue;
            try {
                // col is an internal constant ("injected"/"recalled"), never user input
                db.update("INSERT INTO memory_stats(scope, fact, injected, recalled, last_used, first_seen) "
                                + "VALUES(?,?,?,?,?,?) "
                                + "ON CONFLICT(scope, fact) DO UPDATE SET " + col + "=" + col
                                + "+1, last_used=excluded.last_used",
                        key(owner), f.strip(), col.equals("injected") ? 1 : 0, col.equals("recalled") ? 1 : 0,
                        now, now);
            } catch (Exception e) {
                log.warn("[memory] stat failed: " + e.getMessage());
            }
        }
    }

    /** Register facts as observed (first_seen) without bumping usage, so hygiene can age out unused ones. */
    private void touchFacts(String owner, java.util.List<String> facts) {
        if (!db.available() || facts == null || facts.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (String f : facts) {
            if (f == null || f.isBlank()) continue;
            try {
                db.update("INSERT OR IGNORE INTO memory_stats(scope, fact, injected, recalled, last_used, "
                                + "first_seen) VALUES(?,?,0,0,NULL,?)", key(owner), f.strip(), now);
            } catch (Exception e) { /* best-effort */ }
        }
    }

    /** Per-fact usage analytics: {@code [{fact, injected, recalled, lastUsed}]}, most-used first. */
    public java.util.List<Map<String, Object>> analytics(String owner) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (!db.available()) return out;
        try {
            return db.query("SELECT fact, injected, recalled, last_used FROM memory_stats WHERE scope=? "
                            + "ORDER BY (injected + recalled) DESC, last_used DESC",
                    rs -> {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("fact", rs.getString(1));
                        m.put("injected", rs.getInt(2));
                        m.put("recalled", rs.getInt(3));
                        m.put("lastUsed", rs.getLong(4));
                        return m;
                    }, key(owner));
        } catch (Exception e) {
            return out;
        }
    }

    private static java.util.List<String> splitLines(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null) return out;
        for (String l : text.split("\n")) {
            String s = l.strip();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** When the durable note for an owner was last updated (epoch ms), or 0 if none. */
    public long updatedAt(String owner) {
        if (!db.available()) return 0;
        try {
            List<Long> rows = db.query("SELECT updated_at FROM memory WHERE owner=?",
                    rs -> rs.getLong(1), key(owner));
            return rows.isEmpty() ? 0 : rows.get(0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Clear the durable note for an owner. */
    public void clear(String owner) {
        if (!db.available()) return;
        try {
            db.update("DELETE FROM memory WHERE owner=?", key(owner));
        } catch (Exception e) {
            log.warn("[memory] clear failed: " + e.getMessage());
        }
    }
}
