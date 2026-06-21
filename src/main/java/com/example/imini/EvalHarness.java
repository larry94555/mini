package com.example.imini;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small agent-evaluation harness: run a fixed suite of {@link Case}s through the live agent and score
 * each answer, so prompt/model/refactor changes can be checked for quality regressions — not just that the
 * code compiles. This is the gap between "the tests pass" and "the agent is still any good".
 *
 * <p>The SCORING is pure and fully unit-testable ({@link #scoreContains}, {@link #scoreRegex},
 * {@link #scoreCase}); the RUNNER ({@link #runSuite}) needs a live model, so it self-skips when the model
 * is unreachable, mirroring the integration tests. A run produces an aggregate pass-rate plus per-case
 * detail, suitable for storing and comparing across runs.
 *
 * <p>The default suite ({@link #defaultCases()}) is deliberately tiny and model-agnostic (greetings, simple
 * arithmetic, a factual recall) — enough to catch gross regressions on a 3B local model. Real deployments
 * would supply a larger, domain-specific suite.
 */
@Component
public class EvalHarness {

    private static final Logger log = LoggerFactory.getLogger(EvalHarness.class);

    @Value("${eval.enabled:true}") private boolean enabled;
    @Value("${eval.suite-file:eval/suite.txt}") private String suiteFile;

    private final AgentLoop loop;
    private final LlamaClient llama;

    public EvalHarness(AgentLoop loop, LlamaClient llama) {
        this.loop = loop;
        this.llama = llama;
    }

    public boolean enabled() { return enabled; }

    /** How an expected answer is matched against the model's output. */
    public enum Match { CONTAINS, REGEX, EQUALS_NORMALIZED }

    /** One evaluation case: a prompt and an expectation. */
    public record Case(String id, String prompt, String expected, Match match) {}

    /** The outcome of scoring one case. */
    public record Result(String id, boolean passed, String expected, String actual) {}

    // --- pure scoring (no model) --------------------------------------------

    /** True if {@code actual} contains {@code needle}, case-insensitively. */
    static boolean scoreContains(String actual, String needle) {
        if (actual == null || needle == null) return false;
        return actual.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    /** True if {@code actual} matches the regex {@code pattern} anywhere (DOTALL, case-insensitive). */
    static boolean scoreRegex(String actual, String pattern) {
        if (actual == null || pattern == null) return false;
        try {
            return java.util.regex.Pattern.compile(pattern,
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                    .matcher(actual).find();
        } catch (Exception e) {
            return false;
        }
    }

    /** True if normalized (trimmed, collapsed whitespace, lowercased) strings are equal. */
    static boolean scoreEqualsNormalized(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
    }

    static String normalize(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Pure: score one case's actual answer against its expectation. */
    static boolean scoreCase(Case c, String actual) {
        return switch (c.match()) {
            case CONTAINS -> scoreContains(actual, c.expected());
            case REGEX -> scoreRegex(actual, c.expected());
            case EQUALS_NORMALIZED -> scoreEqualsNormalized(actual, c.expected());
        };
    }

    /** Pure: aggregate a list of results into {total, passed, passRate}. */
    static Map<String, Object> aggregate(List<Result> results) {
        long passed = results.stream().filter(Result::passed).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", results.size());
        out.put("passed", passed);
        out.put("failed", results.size() - passed);
        out.put("passRate", results.isEmpty() ? 0.0 : (double) passed / results.size());
        return out;
    }

    // --- model-dependent runner ---------------------------------------------

    /** The built-in smoke suite. */
    public static List<Case> defaultCases() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("greet", "Say hello in exactly one short sentence.", "hello", Match.CONTAINS));
        cases.add(new Case("arith", "What is 17 plus 25? Reply with just the number.", "42", Match.CONTAINS));
        cases.add(new Case("capital", "What is the capital of France? One word.", "paris", Match.CONTAINS));
        cases.add(new Case("yesno", "Is water wet? Answer yes or no.", "yes", Match.CONTAINS));
        return cases;
    }

    /**
     * The cases to run: the curated suite file ({@code eval.suite-file}, default {@code eval/suite.txt}) if
     * it exists and parses to at least one case, otherwise the built-in {@link #defaultCases()}. So shipping
     * a richer suite is a matter of editing a text file — no recompile.
     */
    public List<Case> loadCases() {
        try {
            Path p = Path.of(suiteFile);
            if (Files.isRegularFile(p)) {
                List<Case> parsed = parseCases(Files.readString(p));
                if (!parsed.isEmpty()) {
                    log.info("[eval] loaded " + parsed.size() + " case(s) from " + suiteFile);
                    return parsed;
                }
            }
        } catch (IOException e) {
            log.warn("[eval] could not read " + suiteFile + ": " + e.getMessage() + "; using built-in suite.");
        }
        return defaultCases();
    }

    /**
     * Pure: parse the curated suite format — one case per line, {@code id | match | expected | prompt}, where
     * {@code match} is contains/regex/equals (case-insensitive). Blank lines and {@code #} comments are
     * skipped; malformed lines are ignored. The {@code prompt} may contain pipes (only the first three
     * delimiters are significant). No external dependency, so it is unit-testable offline.
     */
    public static List<Case> parseCases(String text) {
        List<Case> cases = new ArrayList<>();
        if (text == null) return cases;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\|", 4);
            if (parts.length < 4) continue;
            String id = parts[0].trim();
            Match match = parseMatch(parts[1].trim());
            String expected = parts[2].trim();
            String prompt = parts[3].trim();
            if (id.isEmpty() || match == null || prompt.isEmpty()) continue;
            cases.add(new Case(id, prompt, expected, match));
        }
        return cases;
    }

    /** Pure: map a suite-file match token to a {@link Match}; null if unrecognized. */
    static Match parseMatch(String token) {
        if (token == null) return null;
        switch (token.trim().toLowerCase(Locale.ROOT)) {
            case "contains": return Match.CONTAINS;
            case "regex": return Match.REGEX;
            case "equals": case "equals_normalized": case "normalized": return Match.EQUALS_NORMALIZED;
            default: return null;
        }
    }

    /**
     * Run a suite against the live agent. Returns an aggregate map with per-case detail. If the model is
     * unreachable (or eval is disabled), returns {@code {skipped:true, reason:...}} rather than failing —
     * the same self-skip contract the integration tests use, so this is safe to call offline.
     */
    public Map<String, Object> runSuite(List<Case> cases) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled) { out.put("skipped", true); out.put("reason", "eval disabled"); return out; }
        if (llama.serverContext() <= 0) { // model not reachable
            out.put("skipped", true); out.put("reason", "model unreachable"); return out;
        }
        List<Result> results = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (Case c : cases) {
            String actual;
            try {
                String sid = "eval-" + c.id();
                actual = loop.run(sid, c.prompt(), PermissionService.Mode.ASK, RunSink.NOOP);
            } catch (Exception e) {
                actual = "ERROR: " + e.getMessage();
            }
            results.add(new Result(c.id(), scoreCase(c, actual), c.expected(), actual));
        }
        out.putAll(aggregate(results));
        out.put("elapsedMs", System.currentTimeMillis() - t0);
        List<Map<String, Object>> detail = new ArrayList<>();
        for (Result r : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("passed", r.passed());
            m.put("expected", r.expected());
            m.put("actual", truncate(r.actual(), 200));
            detail.add(m);
        }
        out.put("cases", detail);
        log.info("[eval] suite complete: " + out.get("passed") + "/" + out.get("total") + " passed");
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
