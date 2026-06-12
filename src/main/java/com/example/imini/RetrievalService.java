package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Retrieval / RAG memory over the workspace. index_workspace walks the project's text files, chunks
 * them, and stores the chunks (persisted in SQLite via {@link Database}, or in-memory as fallback).
 * search_memory returns the top-k relevant snippets for a query so the agent can find where something
 * lives before reading whole files.
 *
 * Scoring is LEXICAL by default (term overlap) -- zero setup, deterministic, good enough for code/doc
 * lookup. Set retrieval.embeddings=true to score by cosine similarity using a llama-server embedding
 * endpoint (best pointed at a SECOND server started with --embeddings); see README for the caveat.
 */
@Component
public class RetrievalService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RetrievalService.class);


    public record Chunk(String id, String source, int ordinal, String text, float[] embedding, String symbols) {}

    private final Database db;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<Chunk> mem = new ArrayList<>(); // fallback index when DB unavailable
    private final Map<String, Long> memMtime = new HashMap<>(); // source -> mtime (fallback)

    public RetrievalService(Database db) {
        this.db = db;
    }

    @Value("${agent.workspace-root:}") private String workspaceRootCfg;
    @Value("${retrieval.chunk-size:1000}") private int chunkSize;
    @Value("${retrieval.max-file-kb:200}") private int maxFileKb;
    @Value("${retrieval.top-k:5}") private int topK;
    @Value("${retrieval.extensions:.java,.md,.txt,.json,.xml,.yml,.yaml,.properties,.csv,.html,.js,.ts,.py,.kt,.gradle,.sql,.sh,.bat}")
    private String extensionsCfg;
    @Value("${retrieval.embeddings:false}") private boolean useEmbeddings;
    @Value("${retrieval.embed-base-url:}") private String embedBaseUrl;
    @Value("${retrieval.embed-model:nomic-embed-text}") private String embedModel;
    @Value("${llama.port:8081}") private int llamaPort;
    @Value("${llama.client-host:localhost}") private String clientHost;
    @Value("${retrieval.symbol-boost-weight:2.0}") private double symbolBoostWeight;
    @Value("${retrieval.auto-reindex:true}") private boolean autoReindex;

    private static final Set<String> SKIP_DIRS =
            Set.of(".git", "target", "build", "node_modules", ".imini", ".maven", ".idea", "out");

    private Path root;
    private Set<String> exts;

    @PostConstruct
    public void init() {
        root = (workspaceRootCfg == null || workspaceRootCfg.isBlank()
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(workspaceRootCfg)).toAbsolutePath().normalize();
        exts = new LinkedHashSet<>();
        for (String e : extensionsCfg.split(",")) if (!e.isBlank()) exts.add(e.trim().toLowerCase(Locale.ROOT));
        log.info("[retrieval] root=" + root + "; mode=" + (useEmbeddings ? "embeddings" : "lexical"));
    }

    // --- tools ---------------------------------------------------------------

    public Tool indexTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("full", Map.of("type", "boolean",
                "description", "Rebuild from scratch instead of an incremental refresh (default false)."));
        return new Tool("index_workspace",
                "Update the retrieval index over the project's text files. INCREMENTAL by default (only "
                        + "new/changed/removed files since last time); pass full=true to rebuild from scratch. "
                        + "Run before search_memory.",
                objectSchema(props, List.of()), false, args -> {
            Object f = args.get("full");
            boolean full = f instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(f));
            return full ? index() : refresh();
        });
    }

    public Tool searchTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of("type", "string", "description", "What to look for in the project files."));
        props.put("k", Map.of("type", "integer", "description", "How many snippets to return (optional)."));
        return new Tool("search_memory",
                "Search the indexed project files for snippets relevant to a query; returns the top "
                        + "matches with their file paths. Use this to find where something is defined or "
                        + "discussed before reading whole files.",
                objectSchema(props, List.of("query")), false, args -> {
            Object q = args.get("query");
            int k = args.get("k") instanceof Number n ? n.intValue() : topK;
            return search(q == null ? "" : String.valueOf(q), k);
        });
    }

    // --- indexing ------------------------------------------------------------

    /** Full rebuild: clear everything and index every included file. */
    public synchronized String index() {
        clear();
        int files = 0, chunks = 0;
        long now = System.currentTimeMillis();
        if (!Files.isDirectory(root)) return "Workspace root not found: " + root;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> candidates = walk.filter(Files::isRegularFile).filter(this::included).toList();
            for (Path p : candidates) {
                try {
                    int n = indexOneFile(p, now);
                    if (n >= 0) { chunks += n; files++; }
                } catch (Exception perFile) {
                    // skip unreadable/binary files
                }
            }
        } catch (Exception e) {
            return "ERROR indexing: " + e.getMessage();
        }
        return "Indexed " + chunks + " chunk(s) from " + files + " file(s)"
                + (useEmbeddings ? " with embeddings." : " (lexical).");
    }

    /** Incremental refresh: only (re)index new/changed files and drop removed ones. */
    public synchronized String refresh() {
        if (!Files.isDirectory(root)) return "Workspace root not found: " + root;
        Map<String, Long> indexed = indexedMtimes();
        Map<String, Long> current = currentFiles();
        IndexPlan plan = diff(indexed, current);
        long now = System.currentTimeMillis();
        int reindexed = 0, removed = 0;
        for (String src : plan.remove()) { deleteSource(src); removed++; }
        for (String src : plan.upsert()) {
            deleteSource(src); // clear stale chunks before re-adding
            try { indexOneFile(root.resolve(src), now); reindexed++; } catch (Exception ignore) { }
        }
        int unchanged = current.size() - plan.upsert().size();
        return "Refreshed index: " + reindexed + " new/changed, " + removed + " removed, "
                + unchanged + " unchanged" + (useEmbeddings ? " (embeddings)." : " (lexical).");
    }

    /** Index a single file; returns chunk count, or -1 if skipped (too large). */
    private int indexOneFile(Path p, long now) throws IOException {
        if (Files.size(p) > maxFileKb * 1024L) return -1;
        String content = Files.readString(p);
        String rel = root.relativize(p.toAbsolutePath().normalize()).toString();
        String fileName = p.getFileName().toString();
        long mtime = Files.getLastModifiedTime(p).toMillis();
        int ord = 0, n = 0;
        for (String chunk : chunk(content, chunkSize)) {
            if (chunk.isBlank()) continue;
            store(new Chunk(UUID.randomUUID().toString(), rel, ord++, chunk,
                    useEmbeddings ? embed(chunk) : null, symbolsOf(fileName, chunk)), now, mtime);
            n++;
        }
        return n;
    }

    /**
     * Auto-reindex a single file after it is written/edited. Best-effort and quiet: does nothing if
     * auto-reindex is off or the index is empty (so a write never silently builds the whole index).
     */
    public synchronized void reindexFile(Path file) {
        if (!autoReindex) return;
        try {
            if (count() == 0) return;
            String rel = root.relativize(file.toAbsolutePath().normalize()).toString();
            deleteSource(rel);
            if (Files.exists(file) && included(file)) {
                indexOneFile(file, System.currentTimeMillis());
            }
        } catch (Exception e) {
            log.debug("[retrieval] auto-reindex skipped " + file + ": " + e.getMessage());
        }
    }

    /** source -> latest indexed mtime. */
    private Map<String, Long> indexedMtimes() {
        Map<String, Long> m = new HashMap<>();
        if (db.available()) {
            record SM(String s, long m) {}
            List<SM> rows = db.query("SELECT source, MAX(mtime) FROM mem_chunks GROUP BY source",
                    rs -> new SM(rs.getString(1), rs.getLong(2)));
            for (SM r : rows) m.put(r.s(), r.m());
        } else {
            m.putAll(memMtime);
        }
        return m;
    }

    /** Included files currently on disk -> mtime. */
    private Map<String, Long> currentFiles() {
        Map<String, Long> cur = new HashMap<>();
        if (!Files.isDirectory(root)) return cur;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).filter(this::included).toList()) {
                try {
                    if (Files.size(p) > maxFileKb * 1024L) continue;
                    String rel = root.relativize(p.toAbsolutePath().normalize()).toString();
                    cur.put(rel, Files.getLastModifiedTime(p).toMillis());
                } catch (Exception ignore) { }
            }
        } catch (Exception ignore) { }
        return cur;
    }

    /** What an incremental refresh would do, given indexed vs current file->mtime. Pure + testable. */
    public record IndexPlan(Set<String> upsert, Set<String> remove) {}

    public static IndexPlan diff(Map<String, Long> indexed, Map<String, Long> current) {
        Set<String> upsert = new LinkedHashSet<>();
        for (Map.Entry<String, Long> e : current.entrySet()) {
            Long was = indexed.get(e.getKey());
            if (was == null || !was.equals(e.getValue())) upsert.add(e.getKey());
        }
        Set<String> remove = new LinkedHashSet<>();
        for (String src : indexed.keySet()) if (!current.containsKey(src)) remove.add(src);
        return new IndexPlan(upsert, remove);
    }

    private void deleteSource(String source) {
        if (db.available()) {
            db.update("DELETE FROM mem_chunks WHERE source=?", source);
        } else {
            mem.removeIf(c -> c.source().equals(source));
            memMtime.remove(source);
        }
    }

    private boolean included(Path p) {
        Path rel = root.relativize(p.toAbsolutePath().normalize());
        for (Path part : rel) if (SKIP_DIRS.contains(part.toString())) return false;
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && exts.contains(name.substring(dot));
    }

    private void clear() {
        if (db.available()) db.update("DELETE FROM mem_chunks");
        else { mem.clear(); memMtime.clear(); }
    }

    private void store(Chunk c, long now, long mtime) {
        if (db.available()) {
            String emb = c.embedding() == null ? null : floatsToJson(c.embedding());
            db.update("INSERT INTO mem_chunks(id, source, ordinal, text, embedding, symbols, indexed_at, mtime) "
                    + "VALUES(?,?,?,?,?,?,?,?)",
                    c.id(), c.source(), c.ordinal(), c.text(), emb, c.symbols(), now, mtime);
        } else {
            mem.add(c);
            memMtime.put(c.source(), mtime);
        }
    }

    private long count() {
        if (db.available()) {
            List<Long> n = db.query("SELECT COUNT(*) FROM mem_chunks", rs -> rs.getLong(1));
            return n.isEmpty() ? 0 : n.get(0);
        }
        return mem.size();
    }

    private List<Chunk> allChunks() {
        if (db.available()) {
            return db.query("SELECT id, source, ordinal, text, embedding, symbols FROM mem_chunks",
                    rs -> new Chunk(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                            jsonToFloats(rs.getString(5)), rs.getString(6)));
        }
        return new ArrayList<>(mem);
    }

    // --- search --------------------------------------------------------------

    public synchronized String search(String query, int k) {
        if (query == null || query.isBlank()) return "Provide a non-empty query.";
        if (count() == 0) {
            String idx = index();
            if (count() == 0) return "Nothing indexed. (" + idx + ")";
        }
        int want = k > 0 ? k : topK;
        List<Chunk> chunks = allChunks();

        record Scored(Chunk c, double score) {}
        List<Scored> scored = new ArrayList<>();
        if (useEmbeddings) {
            float[] qv = embed(query);
            for (Chunk c : chunks) {
                if (c.embedding() != null) scored.add(new Scored(c, cosine(qv, c.embedding())));
            }
        } else {
            List<String> qt = tokenize(query);
            for (Chunk c : chunks) {
                double s = lexicalScore(qt, c.text()) + symbolBoost(qt, c.symbols(), symbolBoostWeight);
                if (s > 0) scored.add(new Scored(c, s));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        if (scored.isEmpty()) return "No relevant snippets found for: " + query;
        StringBuilder sb = new StringBuilder("Top matches for \"" + query + "\":\n");
        for (int i = 0; i < Math.min(want, scored.size()); i++) {
            Chunk c = scored.get(i).c();
            sb.append("\n--- ").append(c.source()).append(" #").append(c.ordinal()).append(" ---\n");
            sb.append(c.text().strip()).append("\n");
        }
        return sb.toString().strip();
    }

    /** Pure lexical score: distinct query terms found in the text, weighted by occurrence. */
    public static double lexicalScore(List<String> queryTokens, String text) {
        if (text == null || text.isBlank()) return 0;
        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> distinct = new LinkedHashSet<>(queryTokens);
        double score = 0;
        for (String q : distinct) {
            int idx = 0, count = 0;
            while ((idx = lower.indexOf(q, idx)) >= 0) {
                count++;
                idx += q.length();
            }
            if (count > 0) score += 1 + Math.log(1 + count);
        }
        return score;
    }

    /** Declaration names found in a chunk (via CodebaseTools), space-joined; "" if none/unsupported. */
    private static String symbolsOf(String fileName, String chunk) {
        List<CodebaseTools.Symbol> syms = CodebaseTools.extractSymbols(fileName, List.of(chunk.split("\n", -1)));
        if (syms.isEmpty()) return "";
        Set<String> names = new LinkedHashSet<>();
        for (CodebaseTools.Symbol sym : syms) names.add(sym.name());
        return String.join(" ", names);
    }

    /**
     * Extra score when a query term exactly matches a declaration name in the chunk (class/method/
     * function/...), so the file that DEFINES a symbol ranks above files that merely mention it.
     * weight &lt;= 0 disables it. Pure + static for unit testing.
     */
    public static double symbolBoost(List<String> queryTokens, String symbols, double weight) {
        if (weight <= 0 || symbols == null || symbols.isBlank() || queryTokens.isEmpty()) return 0;
        Set<String> symSet = new LinkedHashSet<>();
        for (String sName : symbols.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!sName.isBlank()) symSet.add(sName);
        }
        double boost = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (String q : queryTokens) {
            if (seen.add(q) && symSet.contains(q)) boost += weight;
        }
        return boost;
    }

    public static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() >= 2) out.add(t);
        }
        return out;
    }

    private List<String> chunk(String content, int size) {
        List<String> out = new ArrayList<>();
        if (size <= 0) size = 1000;
        String[] lines = content.split("\n", -1);
        StringBuilder cur = new StringBuilder();
        for (String line : lines) {
            if (cur.length() + line.length() + 1 > size && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(line).append("\n");
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    // --- embeddings (optional) ----------------------------------------------

    @SuppressWarnings("unchecked")
    private float[] embed(String text) {
        try {
            String base = (embedBaseUrl == null || embedBaseUrl.isBlank())
                    ? "http://" + clientHost + ":" + llamaPort : embedBaseUrl;
            String body = mapper.writeValueAsString(Map.of("model", embedModel, "input", text));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/v1/embeddings"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return new float[0];
            Map<String, Object> json = mapper.readValue(resp.body(), Map.class);
            List<Map<String, Object>> data = (List<Map<String, Object>>) json.get("data");
            if (data == null || data.isEmpty()) return new float[0];
            List<Number> vec = (List<Number>) data.get(0).get("embedding");
            float[] out = new float[vec.size()];
            for (int i = 0; i < out.length; i++) out[i] = vec.get(i).floatValue();
            return out;
        } catch (Exception e) {
            return new float[0];
        }
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private String floatsToJson(float[] v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            return null;
        }
    }

    private float[] jsonToFloats(String json) {
        if (json == null) return null;
        try {
            float[] v = mapper.readValue(json, float[].class);
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> objectSchema(Map<String, Object> props, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", required);
        return schema;
    }
}
