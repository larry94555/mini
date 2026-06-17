package com.example.imini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure model + parsing for a plugin registry index -- a JSON document that lists installable packs so you
 * can <em>discover</em> them, not just install one by a direct URL. Mirrors {@link SkillManifest}: the
 * parsing/searching is dependency-free and unit-testable; fetching and installing live in
 * {@link PluginService}, which reuses the existing install-by-URL + SHA-256 verification.
 *
 * <p>Index shape (a top-level array, or an object with a {@code packs} array):
 * <pre>{ "format": "imini-registry/1", "name": "...", "packs": [
 *     { "name", "version", "description", "url", "sha256" } ] }</pre>
 */
public final class PluginRegistry {

    private PluginRegistry() {}

    public static final String FORMAT = "imini-registry/1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One advertised pack: where to get it ({@code url}) and its expected hash ({@code sha256}, optional). */
    public record Listing(String name, String version, String description, String url, String sha256) {}

    /** Parse a registry index. Entries without a name or url are skipped. Never throws. */
    public static List<Listing> parse(String json) {
        List<Listing> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode arr = root.isArray() ? root : root.get("packs");
            if (arr == null || !arr.isArray()) return out;
            for (JsonNode n : arr) {
                String name = text(n, "name");
                String url = text(n, "url");
                if (name == null || url == null) continue; // a listing is useless without both
                out.add(new Listing(name, orEmpty(text(n, "version")), orEmpty(text(n, "description")),
                        url, orEmpty(text(n, "sha256"))));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** The listing with the given name (case-insensitive), or null. */
    public static Listing byName(List<Listing> listings, String name) {
        if (listings == null || name == null) return null;
        for (Listing l : listings) if (l.name().equalsIgnoreCase(name.trim())) return l;
        return null;
    }

    /** Top-k listings whose name+description best match the query (lexical overlap; score &gt; 0). */
    public static List<Listing> search(List<Listing> listings, String query, int k) {
        if (listings == null) return List.of();
        if (query == null || query.isBlank()) {
            return listings.size() <= k ? new ArrayList<>(listings) : new ArrayList<>(listings.subList(0, Math.max(0, k)));
        }
        List<String> qt = RetrievalService.tokenize(query);
        List<Listing> ranked = new ArrayList<>(listings);
        ranked.sort((a, b) -> Double.compare(score(qt, b), score(qt, a)));
        List<Listing> out = new ArrayList<>();
        for (Listing l : ranked) {
            if (out.size() >= k) break;
            if (score(qt, l) > 0) out.add(l);
        }
        return out;
    }

    private static double score(List<String> qt, Listing l) {
        return RetrievalService.lexicalScore(qt, l.name() + " " + l.description());
    }

    /**
     * A deterministic, canonical string over the listings (sorted by name; name|version|url|sha256 per
     * line) suitable for signing the index. SHA-256 this to get the signing payload. Pure.
     */
    public static String signablePayload(List<Listing> listings) {
        if (listings == null || listings.isEmpty()) return "imini-registry/1\n";
        List<Listing> sorted = new ArrayList<>(listings);
        sorted.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        StringBuilder sb = new StringBuilder("imini-registry/1\n");
        for (Listing l : sorted) {
            sb.append(l.name()).append('|').append(l.version()).append('|')
              .append(l.url()).append('|').append(l.sha256()).append('\n');
        }
        return sb.toString();
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
