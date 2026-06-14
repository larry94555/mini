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
}
