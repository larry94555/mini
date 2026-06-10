package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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

    public record Chunk(String id, String source, int ordinal, String text, float[] embedding) {}

    private final Database db;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<Chunk> mem = new ArrayList<>(); // fallback index when DB unavailable

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
        System.out.println("[retrieval] root=" + root + "; mode=" + (useEmbeddings ? "embeddings" : "lexical"));
    }

    // --- tools ---------------------------------------------------------------

    public Tool indexTool() {
        return new Tool("index_workspace",
                "(Re)build the retrieval index over the project's text files. Run once before "
                        + "search_memory, or after large changes.",
                objectSchema(Map.of(), List.of()), false, args -> index());
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

    public synchronized String index() {
        clear();
        int files = 0, chunks = 0;
        long now = System.currentTimeMillis();
        if (!Files.isDirectory(root)) return "Workspace root not found: " + root;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> candidates = walk.filter(Files::isRegularFile)
                    .filter(this::included)
                    .toList();
            for (Path p : candidates) {
                try {
                    if (Files.size(p) > maxFileKb * 1024L) continue;
                    String content = Files.readString(p);
                    String rel = root.relativize(p.toAbsolutePath().normalize()).toString();
                    int ord = 0;
                    for (String chunk : chunk(content, chunkSize)) {
                        if (chunk.isBlank()) continue;
                        store(new Chunk(UUID.randomUUID().toString(), rel, ord++, chunk,
                                useEmbeddings ? embed(chunk) : null), now);
                        chunks++;
                    }
                    files++;
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

    private boolean included(Path p) {
        Path rel = root.relativize(p.toAbsolutePath().normalize());
        for (Path part : rel) if (SKIP_DIRS.contains(part.toString())) return false;
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && exts.contains(name.substring(dot));
    }

    private void clear() {
        if (db.available()) db.update("DELETE FROM mem_chunks");
        else mem.clear();
    }

    private void store(Chunk c, long now) {
        if (db.available()) {
            String emb = c.embedding() == null ? null : floatsToJson(c.embedding());
            db.update("INSERT INTO mem_chunks(id, source, ordinal, text, embedding, indexed_at) VALUES(?,?,?,?,?,?)",
                    c.id(), c.source(), c.ordinal(), c.text(), emb, now);
        } else {
            mem.add(c);
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
            return db.query("SELECT id, source, ordinal, text, embedding FROM mem_chunks",
                    rs -> new Chunk(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                            jsonToFloats(rs.getString(5))));
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
                double s = lexicalScore(qt, c.text());
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
