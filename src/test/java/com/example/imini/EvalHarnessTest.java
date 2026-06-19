package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalHarnessTest {

    @Test
    void containsIsCaseInsensitive() {
        assertTrue(EvalHarness.scoreContains("The answer is PARIS.", "paris"));
        assertFalse(EvalHarness.scoreContains("The answer is London.", "paris"));
        assertFalse(EvalHarness.scoreContains(null, "x"));
    }

    @Test
    void regexMatchesAnywhereDotallCaseInsensitive() {
        assertTrue(EvalHarness.scoreRegex("Result:\n42 items", "\\b42\\b"));
        assertTrue(EvalHarness.scoreRegex("YES it is", "^yes"));
        assertFalse(EvalHarness.scoreRegex("nope", "^yes"));
        assertFalse(EvalHarness.scoreRegex("x", "(")); // invalid regex -> false, not crash
    }

    @Test
    void equalsNormalizedCollapsesWhitespaceAndCase() {
        assertTrue(EvalHarness.scoreEqualsNormalized("  Hello   World ", "hello world"));
        assertFalse(EvalHarness.scoreEqualsNormalized("hello", "hello world"));
    }

    @Test
    void scoreCaseDispatchesByMatchType() {
        assertTrue(EvalHarness.scoreCase(
                new EvalHarness.Case("c", "p", "paris", EvalHarness.Match.CONTAINS), "It is Paris"));
        assertTrue(EvalHarness.scoreCase(
                new EvalHarness.Case("c", "p", "\\d+", EvalHarness.Match.REGEX), "abc 42"));
        assertTrue(EvalHarness.scoreCase(
                new EvalHarness.Case("c", "p", "yes", EvalHarness.Match.EQUALS_NORMALIZED), " YES "));
    }

    @Test
    void aggregateComputesPassRate() {
        List<EvalHarness.Result> rs = List.of(
                new EvalHarness.Result("a", true, "", ""),
                new EvalHarness.Result("b", false, "", ""),
                new EvalHarness.Result("c", true, "", ""),
                new EvalHarness.Result("d", true, "", ""));
        Map<String, Object> agg = EvalHarness.aggregate(rs);
        assertEquals(4, agg.get("total"));
        assertEquals(3L, agg.get("passed"));
        assertEquals(1L, agg.get("failed"));
        assertEquals(0.75, (double) agg.get("passRate"));
    }

    @Test
    void defaultSuiteIsNonEmpty() {
        assertTrue(EvalHarness.defaultCases().size() >= 3);
    }
}
