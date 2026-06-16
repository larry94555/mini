package com.example.imini;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure helpers for Claude-like layered project memory. The candidate memory files (in load order) and
 * the {@code @path} import expansion live here so they can be unit-tested without touching the
 * filesystem; {@link ProjectContext} supplies the actual file I/O via a {@link Resolver}.
 *
 * <p>A memory file may pull in another with a line whose first token is {@code @<path>} (e.g.
 * {@code @./conventions.md}); the import is inlined, depth- and cycle-guarded. {@code @@} is an escape
 * for a literal leading at-sign.
 */
public final class MemoryLoader {

    private MemoryLoader() {}

    /** Memory files loaded (and concatenated) in this order; local overrides come last so they win. */
    public static final List<String> CANDIDATES = List.of(
            ".claude/CLAUDE.md", "CLAUDE.md", "IMINI.md", "AGENTS.md", "CLAUDE.local.md");

    /**
     * The full ordered load list: the fixed {@link #CANDIDATES}, with {@code .claude/rules/*.md} (already
     * sorted, supplied by the caller) inserted right before {@code CLAUDE.local.md} -- so project files
     * load first, then rules, then the local override wins last. Pure, so precedence is unit-testable.
     */
    public static List<String> candidateOrder(List<String> ruleFiles) {
        List<String> files = new java.util.ArrayList<>();
        for (String name : CANDIDATES) {
            if (name.equals("CLAUDE.local.md") && ruleFiles != null) files.addAll(ruleFiles);
            files.add(name);
        }
        return files;
    }

    /** One entry in the memory diagnostics: which file, why it loaded (or was skipped), size, depth. */
    public record Source(String path, String reason, int bytes, int depth) {}

    /** Resolves an {@code @path} import (relative to the importer) to its key + content, or null. */
    public interface Resolver {
        Resolved resolve(String importerKey, String importPath);
    }

    public record Resolved(String key, String content) {}

    /** Import target paths declared in a memory file (lines starting with a single {@code @}). Pure. */
    public static List<String> imports(String content) {
        List<String> out = new ArrayList<>();
        if (content == null) return out;
        for (String line : content.split("\n")) {
            String t = line.strip();
            if (t.length() > 1 && t.charAt(0) == '@' && t.charAt(1) != '@') {
                out.add(t.substring(1).trim());
            }
        }
        return out;
    }

    /**
     * Inline {@code @path} imports into {@code content}, recursively, recording a diagnostics entry for
     * every import (resolved, missing, cyclic, or past the depth cap). Pure given the resolver.
     */
    public static String expand(String key, String content, Resolver resolver, int maxDepth,
                                List<Source> diag, Set<String> visited, int depth) {
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            String t = line.strip();
            if (t.length() > 1 && t.charAt(0) == '@' && t.charAt(1) != '@') {
                String importPath = t.substring(1).trim();
                if (depth >= maxDepth) {
                    diag.add(new Source(importPath, "skipped: max import depth " + maxDepth, 0, depth + 1));
                    continue;
                }
                Resolved r = resolver.resolve(key, importPath);
                if (r == null) {
                    diag.add(new Source(importPath, "skipped: not found or outside workspace", 0, depth + 1));
                    continue;
                }
                if (visited.contains(r.key())) {
                    diag.add(new Source(r.key(), "skipped: import cycle", 0, depth + 1));
                    continue;
                }
                visited.add(r.key());
                diag.add(new Source(r.key(), "imported via @", r.content() == null ? 0 : r.content().length(), depth + 1));
                sb.append(expand(r.key(), r.content(), resolver, maxDepth, diag, visited, depth + 1)).append("\n");
            } else if (t.startsWith("@@")) {
                sb.append(line.replaceFirst("@@", "@")).append("\n"); // unescape literal @
            } else {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
