package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structured coding report: JSON extraction/parse, fact merge, and rendering. */
class CodingReportTest {

    @Test
    void extractsJsonFromFenceOrBareOrNothing() {
        assertEquals("{\"summary\":\"x\"}",
                CodingReport.extractJson("blah\n```json\n{\"summary\":\"x\"}\n```\nmore"));
        assertEquals("{\"a\":1}", CodingReport.extractJson("text {\"a\":1} tail"));
        assertNull(CodingReport.extractJson("no json at all"));
        assertNull(CodingReport.extractJson(null));
    }

    @Test
    void parsesSoftFieldsTolerantly() {
        CodingReport r = CodingReport.parse(
                "{\"summary\":\"did x\",\"verification\":\"ran tests\",\"tests_not_run\":\"e2e\","
                        + "\"risks\":[\"r1\",\"r2\"]}");
        assertEquals("did x", r.summary());
        assertEquals("ran tests", r.verification());
        assertEquals("e2e", r.testsNotRun());
        assertEquals(List.of("r1", "r2"), r.risks());
    }

    @Test
    void parsesRisksGivenAsAStringAndHandlesMissingFields() {
        CodingReport r = CodingReport.parse("{\"summary\":\"y\",\"risks\":\"single risk\"}");
        assertEquals("y", r.summary());
        assertEquals(List.of("single risk"), r.risks());
        assertNull(r.verification());
        CodingReport empty = CodingReport.parse("not json");
        assertNull(empty.summary());
        assertTrue(empty.risks().isEmpty());
    }

    @Test
    void withFactsOverlaysAuthoritativeFactsOnSoftFields() {
        CodingReport parsed = new CodingReport("added endpoint", List.of("ignored.java"),
                List.of("ignored-cmd"), null, "compiled", "none", List.of("risk a"));
        CodingReport merged = CodingReport.withFacts(parsed,
                List.of("src/App.java"), List.of("mvn -q test"), "1 file changed");

        // facts win for files/commands/diffStat
        assertEquals(List.of("src/App.java"), merged.changedFiles());
        assertEquals(List.of("mvn -q test"), merged.commandsRun());
        assertEquals("1 file changed", merged.diffStat());
        // soft fields preserved
        assertEquals("added endpoint", merged.summary());
        assertEquals("compiled", merged.verification());
        assertEquals(List.of("risk a"), merged.risks());
    }

    @Test
    void rendersFactsOnlyWithNotReportedPlaceholders() {
        String out = CodingReport.withFacts(null, List.of("a.java"), List.of(), "1 file changed").render();
        assertTrue(out.startsWith("---\nCoding report:"));
        assertTrue(out.contains("Changed files: a.java"));
        assertTrue(out.contains("Commands run: (none recorded)"));
        assertTrue(out.contains("Summary: (not reported)"));
        assertTrue(out.contains("git diff --stat: 1 file changed"));
    }
}
