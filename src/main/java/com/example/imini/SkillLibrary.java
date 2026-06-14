package com.example.imini;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure parsing/selection/formatting for "skills" -- reusable instruction bundles the agent loads on
 * demand. A skill is a SKILL.md with optional front-matter (name, description) and a body of
 * instructions. The agent always sees a short INDEX of names+descriptions, and loads a full body when a
 * task matches (progressive disclosure). Selection reuses {@link RetrievalService}'s lexical scorer, so
 * no new ML is needed. Dependency-free and unit-testable.
 */
public final class SkillLibrary {

    private SkillLibrary() {}

    public record Skill(String name, String description, String body) {}

    /** Parse a SKILL.md: optional `---` front-matter (name/description), then the body. */
    public static Skill parse(String text, String fallbackName) {
        if (text == null) return new Skill(fallbackName, "", "");
        String t = text.replace("\r\n", "\n");
        String name = fallbackName, desc = "", body = t.strip();
        if (t.stripLeading().startsWith("---")) {
            int first = t.indexOf("---");
            int second = t.indexOf("\n---", first + 3);
            if (second > first) {
                String fm = t.substring(first + 3, second);
                body = t.substring(second + 4).strip();
                for (String line : fm.split("\n")) {
                    String l = line.strip();
                    String lower = l.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("name:")) {
                        name = l.substring(5).strip();
                    } else if (lower.startsWith("description:")) {
                        desc = l.substring(12).strip();
                    }
                }
            }
        }
        if (name == null || name.isBlank()) name = fallbackName;
        return new Skill(name, desc == null ? "" : desc, body == null ? "" : body);
    }

    /** Short index of names + descriptions, for the always-in-context skill list. */
    public static String index(List<Skill> skills) {
        StringBuilder sb = new StringBuilder();
        for (Skill s : skills) {
            sb.append("- ").append(s.name()).append(": ")
                    .append(s.description().isBlank() ? "(no description)" : s.description()).append("\n");
        }
        return sb.toString().strip();
    }

    /** Top-k skills whose name+description best match the query (lexical overlap; score &gt; 0). */
    public static List<Skill> select(List<Skill> skills, String query, int k) {
        List<String> qt = RetrievalService.tokenize(query == null ? "" : query);
        List<Skill> ranked = new ArrayList<>(skills);
        ranked.sort((a, b) -> Double.compare(score(qt, b), score(qt, a)));
        List<Skill> out = new ArrayList<>();
        for (Skill s : ranked) {
            if (out.size() >= k) break;
            if (score(qt, s) > 0) out.add(s);
        }
        return out;
    }

    private static double score(List<String> queryTokens, Skill s) {
        return RetrievalService.lexicalScore(queryTokens, s.name() + " " + s.description());
    }

    /** Wrap a skill body for injection into the prompt / a load_skill result (optionally capped). */
    public static String format(Skill s, int maxBody) {
        String body = s.body();
        if (maxBody > 0 && body.length() > maxBody) body = body.substring(0, maxBody) + "\n...(truncated)";
        return "--- Skill: " + s.name() + " ---\n" + body;
    }

    /**
     * Merge skill sources in PRECEDENCE order (highest precedence first, e.g. local before remote),
     * de-duplicating by name (case-insensitive) so a local skill overrides a remote one of the same
     * name. Order within the result follows first-seen. Pure.
     */
    public static List<Skill> merge(List<List<Skill>> sourcesHighestFirst) {
        java.util.Map<String, Skill> byName = new java.util.LinkedHashMap<>();
        for (List<Skill> src : sourcesHighestFirst) {
            if (src == null) continue;
            for (Skill s : src) {
                byName.putIfAbsent(s.name().toLowerCase(Locale.ROOT), s); // first (higher precedence) wins
            }
        }
        return new ArrayList<>(byName.values());
    }

    /** Derive a safe cache-directory name from a git URL (e.g. .../foo/bar.git -&gt; "bar"). Pure. */
    public static String repoSlug(String url) {
        if (url == null || url.isBlank()) return "repo";
        String u = url.trim();
        if (u.endsWith(".git")) u = u.substring(0, u.length() - 4);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        int cut = Math.max(u.lastIndexOf('/'), u.lastIndexOf(':'));
        String last = cut >= 0 ? u.substring(cut + 1) : u;
        String slug = last.replaceAll("[^A-Za-z0-9_-]", "-");
        if (slug.isBlank()) slug = "repo";
        return slug.length() > 64 ? slug.substring(0, 64) : slug;
    }

    /** Split a repo spec {@code url#ref} into {@code [url, ref]} for commit/branch/tag pinning. Pure. */
    public static String[] splitRepoSpec(String spec) {
        if (spec == null) return new String[]{"", ""};
        String s = spec.trim();
        int h = s.indexOf('#');
        if (h < 0) return new String[]{s, ""};
        return new String[]{s.substring(0, h).trim(), s.substring(h + 1).trim()};
    }
}
