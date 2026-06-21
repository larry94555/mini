package com.example.imini;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the curated eval suite-file parser ({@link EvalHarness#parseCases}). No model needed, so
 * these run fully offline. Also confirms the shipped {@code eval/suite.txt} parses to usable cases.
 */
class EvalSuiteFileTest {

    @Test
    void parsesWellFormedLinesAndSkipsCommentsAndJunk() {
        String text = String.join("\n",
                "# a comment",
                "",
                "greet | contains | hello | Say hello.",
                "re    | regex    | (?i)\\bok\\b | Say OK.",
                "norm  | equals   | blue | One word for the sky.",
                "bad-no-prompt | contains | x |   ",       // empty prompt -> skipped
                "too | few | fields",                       // < 4 fields -> skipped
                "weird | nosuchmatch | y | prompt");        // bad match -> skipped
        List<EvalHarness.Case> cases = EvalHarness.parseCases(text);
        assertEquals(3, cases.size(), "only the three well-formed lines parse");
        assertEquals("greet", cases.get(0).id());
        assertEquals(EvalHarness.Match.CONTAINS, cases.get(0).match());
        assertEquals(EvalHarness.Match.REGEX, cases.get(1).match());
        assertEquals(EvalHarness.Match.EQUALS_NORMALIZED, cases.get(2).match());
        assertEquals("Say hello.", cases.get(0).prompt());
    }

    @Test
    void promptMayContainPipes() {
        List<EvalHarness.Case> cases = EvalHarness.parseCases("pipe | contains | a | choose a | b | c");
        assertEquals(1, cases.size());
        assertEquals("choose a | b | c", cases.get(0).prompt(), "only the first three delimiters are significant");
    }

    @Test
    void matchTokensAreCaseInsensitiveAndAliased() {
        assertEquals(EvalHarness.Match.CONTAINS, EvalHarness.parseMatch("Contains"));
        assertEquals(EvalHarness.Match.REGEX, EvalHarness.parseMatch("REGEX"));
        assertEquals(EvalHarness.Match.EQUALS_NORMALIZED, EvalHarness.parseMatch("equals"));
        assertEquals(EvalHarness.Match.EQUALS_NORMALIZED, EvalHarness.parseMatch("normalized"));
    }

    @Test
    void shippedSuiteFileParses() throws Exception {
        Path p = Path.of("eval/suite.txt");
        if (!Files.isRegularFile(p)) { System.out.println("[skip] eval/suite.txt not present"); return; }
        List<EvalHarness.Case> cases = EvalHarness.parseCases(Files.readString(p));
        assertTrue(cases.size() >= 4, "the shipped suite has several cases: " + cases.size());
        assertTrue(cases.stream().anyMatch(c -> c.id().equals("greet")), "includes the greet case");
    }

    /**
     * Fixture/case coupling: every {@code eval/fixtures/...} path named in a shipped suite prompt must exist
     * in the repo. This pins the harness-behavior cases to their fixtures so a rename/move breaks the build
     * offline — without needing a live model — the same way check-docs.sh self-checks the docs.
     */
    @Test
    void everyFixturePathNamedInTheSuiteExists() throws Exception {
        Path p = Path.of("eval/suite.txt");
        if (!Files.isRegularFile(p)) { System.out.println("[skip] eval/suite.txt not present"); return; }
        List<EvalHarness.Case> cases = EvalHarness.parseCases(Files.readString(p));

        // Match eval/fixtures and eval/fixtures/<segments> (letters, digits, _, -, ., /); trailing
        // punctuation like a comma or period is excluded by the character class.
        Pattern ref = Pattern.compile("eval/fixtures(?:/[A-Za-z0-9_.\\-/]+)?");
        List<String> referenced = new ArrayList<>();
        for (EvalHarness.Case c : cases) {
            Matcher m = ref.matcher(c.prompt());
            while (m.find()) {
                String path = m.group().replaceAll("[.]+$", "");  // drop any trailing dots
                if (!referenced.contains(path)) referenced.add(path);
            }
        }

        assertTrue(referenced.size() >= 2,
                "the harness-behavior cases reference fixture paths: " + referenced);
        for (String path : referenced) {
            assertTrue(Files.exists(Path.of(path)),
                    "suite references a fixture path that does not exist in the repo: " + path
                            + " (referenced=" + referenced + ")");
        }
    }
}
