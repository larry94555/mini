package com.example.imini;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
}
