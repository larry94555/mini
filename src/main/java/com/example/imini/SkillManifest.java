package com.example.imini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * A skill REGISTRY manifest: a list of available skills with provenance ({@code name, description,
 * source, version, sha256}). Used to search for skills and to verify their content hash before
 * installing -- the provenance layer over the read-only remote repos. Parsing/searching/hashing are
 * pure and unit-testable; fetching/writing lives in {@link SkillService}.
 */
public final class SkillManifest {

    private SkillManifest() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Entry(String name, String description, String source, String version, String sha256) {}

    /** Parse a manifest: either a top-level JSON array, or an object with a {@code "skills"} array. */
    public static List<Entry> parse(String json) {
        List<Entry> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode arr = root.isArray() ? root : root.get("skills");
            if (arr == null || !arr.isArray()) return out;
            for (JsonNode n : arr) {
                String name = text(n, "name");
                if (name == null) continue;
                out.add(new Entry(name, orEmpty(text(n, "description")), orEmpty(text(n, "source")),
                        orEmpty(text(n, "version")), orEmpty(text(n, "sha256"))));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** Top-k entries whose name+description best match the query (lexical overlap; score &gt; 0). */
    public static List<Entry> search(List<Entry> entries, String query, int k) {
        List<String> qt = RetrievalService.tokenize(query == null ? "" : query);
        List<Entry> ranked = new ArrayList<>(entries);
        ranked.sort((a, b) -> Double.compare(score(qt, b), score(qt, a)));
        List<Entry> out = new ArrayList<>();
        for (Entry e : ranked) {
            if (out.size() >= k) break;
            if (score(qt, e) > 0) out.add(e);
        }
        return out;
    }

    private static double score(List<String> qt, Entry e) {
        return RetrievalService.lexicalScore(qt, e.name() + " " + e.description());
    }

    /** Lowercase hex SHA-256 of the (UTF-8) text. Pure. */
    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Does {@code content} match the entry's declared hash? An entry with no {@code sha256} is treated
     * as unpinned and accepted (the caller should warn). Comparison is case-insensitive.
     */
    public static boolean matches(Entry e, String content) {
        if (e == null) return false;
        if (e.sha256() == null || e.sha256().isBlank()) return true; // unpinned -> accept (warn elsewhere)
        return sha256(content).equalsIgnoreCase(e.sha256().trim());
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.isValueNode() ? v.asText() : v.toString();
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
