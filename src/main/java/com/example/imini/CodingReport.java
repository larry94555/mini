package com.example.imini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * A structured final-answer report for coding tasks: changed files, commands run, verification, tests
 * not run, and known risks. The factual fields (changed files, commands run, diff stat) come from git
 * and the tool recorder; the soft fields (summary, verification, tests-not-run, risks) come from a
 * small model call whose JSON this class parses. All parsing/merging/rendering is pure and testable.
 */
public record CodingReport(String summary, List<String> changedFiles, List<String> commandsRun,
                           String diffStat, String verification, String testsNotRun, List<String> risks) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Prompt for the dedicated report call: JSON only, soft fields only (facts are filled by us). */
    public static String reportPrompt(String answer, List<String> changedFiles, List<String> commandsRun) {
        return "You just completed a coding task. Based on your answer and the changes, output ONLY a "
                + "JSON object (no prose, no code fence) with these keys:\n"
                + "  summary: one sentence on what you did\n"
                + "  verification: how you checked it works (or \"none\")\n"
                + "  tests_not_run: tests or checks you did NOT run (or \"none\")\n"
                + "  risks: array of short risk strings (or [])\n\n"
                + "Your answer:\n" + truncate(answer, 1500) + "\n\n"
                + "Files changed: " + String.join(", ", changedFiles) + "\n"
                + "Commands run: " + (commandsRun.isEmpty() ? "none" : String.join("; ", commandsRun));
    }

    /** Tolerantly parse the soft fields from a model JSON object (bare or ```json-fenced). */
    public static CodingReport parse(String modelText) {
        String json = extractJson(modelText);
        if (json == null) return new CodingReport(null, List.of(), List.of(), null, null, null, List.of());
        try {
            JsonNode n = MAPPER.readTree(json);
            return new CodingReport(
                    text(n, "summary"),
                    strList(n.get("changed_files")),
                    strList(n.get("commands_run")),
                    null,
                    text(n, "verification"),
                    text(n, "tests_not_run"),
                    strList(n.get("risks")));
        } catch (Exception e) {
            return new CodingReport(null, List.of(), List.of(), null, null, null, List.of());
        }
    }

    /** Overlay the authoritative facts (changed files, commands, diff stat) on a parsed soft report. */
    public static CodingReport withFacts(CodingReport parsed, List<String> changedFiles,
                                         List<String> commandsRun, String diffStat) {
        CodingReport p = parsed == null ? parse(null) : parsed;
        return new CodingReport(p.summary(), changedFiles, commandsRun,
                (diffStat == null || diffStat.isBlank()) ? null : diffStat,
                p.verification(), p.testsNotRun(), p.risks());
    }

    /** Schema gaps: things a complete coding report should state but doesn't. Empty == complete. Pure. */
    public List<String> validate() {
        List<String> w = new ArrayList<>();
        boolean changed = changedFiles != null && !changedFiles.isEmpty();
        if (changed && isBlankOrNone(verification)) {
            w.add("verification not reported for " + changedFiles.size() + " changed file(s)");
        }
        if (changed && (risks == null || risks.isEmpty())) {
            w.add("risks not reported");
        }
        if (summary == null || summary.isBlank()) {
            w.add("summary not reported");
        }
        return w;
    }

    private static boolean isBlankOrNone(String s) {
        if (s == null || s.isBlank()) return true;
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        return t.equals("none") || t.equals("n/a") || t.equals("na") || t.equals("nothing") || t.equals("-");
    }

    /** Render the report as the block appended to a coding answer. */
    public String render() {
        StringBuilder sb = new StringBuilder("---\nCoding report:\n");
        sb.append("- Summary: ").append(orNot(summary)).append("\n");
        sb.append("- Changed files: ").append(changedFiles == null || changedFiles.isEmpty()
                ? "(none)" : String.join(", ", changedFiles)).append("\n");
        sb.append("- Commands run: ").append(commandsRun == null || commandsRun.isEmpty()
                ? "(none recorded)" : String.join("; ", commandsRun)).append("\n");
        sb.append("- Verification: ").append(orNot(verification)).append("\n");
        sb.append("- Tests not run: ").append(orNot(testsNotRun)).append("\n");
        if (risks == null || risks.isEmpty()) {
            sb.append("- Risks: (none reported)\n");
        } else {
            sb.append("- Risks:\n");
            for (String r : risks) sb.append("  - ").append(r).append("\n");
        }
        if (diffStat != null && !diffStat.isBlank()) {
            sb.append("- git diff --stat: ").append(diffStat).append("\n");
        }
        return sb.toString().strip();
    }

    // ---- helpers (pure) ----------------------------------------------------

    static String extractJson(String text) {
        if (text == null) return null;
        int fence = text.indexOf("```json");
        if (fence >= 0) {
            int start = text.indexOf('\n', fence);
            int end = text.indexOf("```", fence + 7);
            if (start >= 0 && end > start) return text.substring(start + 1, end).trim();
        }
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open >= 0 && close > open) return text.substring(open, close + 1).trim();
        return null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.isValueNode() ? v.asText() : v.toString();
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static List<String> strList(JsonNode v) {
        List<String> out = new ArrayList<>();
        if (v == null || v.isNull()) return out;
        if (v.isArray()) {
            for (JsonNode e : v) {
                String s = e.isValueNode() ? e.asText() : e.toString();
                if (s != null && !s.isBlank()) out.add(s.trim());
            }
        } else {
            String s = v.isValueNode() ? v.asText() : v.toString();
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    private static String orNot(String s) {
        return (s == null || s.isBlank()) ? "(not reported)" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
