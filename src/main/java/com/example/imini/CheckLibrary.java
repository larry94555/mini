package com.example.imini;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Suggests a verification command for a plan step when the model didn't supply its own {@code CHECK:}.
 * Pure heuristics over (project type, step text): for a known build system the default check is a cheap
 * compile (or the test command when the step is about tests); otherwise it falls back to asserting that
 * a file the step names exists. Returns null when nothing confident can be suggested.
 */
public final class CheckLibrary {

    private CheckLibrary() {}

    private static final Pattern FILE_TOKEN = Pattern.compile(
            "\\b([\\w./-]+\\.(?:java|kt|py|js|ts|jsx|tsx|json|xml|yml|yaml|md|txt|html|css|properties))\\b");

    /** Suggest a check command for a step, or null. {@code projectType}: maven|gradle|node|python|unknown. */
    public static String suggest(String projectType, String stepText) {
        if (stepText == null) return null;
        String t = stepText.toLowerCase(Locale.ROOT);
        boolean mentionsTest = t.contains("test") || t.contains("verify") || t.contains("assert");
        String type = projectType == null ? "unknown" : projectType;
        switch (type) {
            case "maven":
                return mentionsTest ? "mvn -q test" : "mvn -q -DskipTests compile";
            case "gradle":
                return mentionsTest ? "gradle -q test" : "gradle -q compileJava";
            case "node":
                return mentionsTest ? "npm test --silent" : "npm run build --silent";
            case "python":
                if (mentionsTest) return "pytest -q";
                String py = firstFile(stepText, ".py");
                return py != null ? "python -m py_compile " + py : null;
            default:
                String f = firstFile(stepText, null);
                return f != null ? "test -f " + f : null;
        }
    }

    /** First filename-like token in the text (optionally constrained to an extension like ".py"), or null. */
    public static String firstFile(String stepText, String ext) {
        if (stepText == null) return null;
        Matcher m = FILE_TOKEN.matcher(stepText);
        while (m.find()) {
            String tok = m.group(1);
            if (ext == null || tok.toLowerCase(Locale.ROOT).endsWith(ext.toLowerCase(Locale.ROOT))) {
                return tok;
            }
        }
        return null;
    }
}
