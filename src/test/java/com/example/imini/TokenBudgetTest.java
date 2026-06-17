package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure token-budget estimation + fit-to-budget logic. */
class TokenBudgetTest {

    // deterministic counter: 1 token per character
    private static final ToIntFunction<String> CHARS = t -> t == null ? 0 : t.length();

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static int total(List<Map<String, Object>> ms) {
        int t = 0;
        for (Map<String, Object> m : ms) t += CHARS.applyAsInt(String.valueOf(m.get("content"))) + 4;
        return t;
    }

    @Test
    void estimateIsAboutFourCharsPerToken() {
        assertEquals(0, TokenBudget.estimate(""));
        assertEquals(1, TokenBudget.estimate("a"));      // min 1 for non-empty
        assertEquals(4, TokenBudget.estimate("a".repeat(16)));
    }

    @Test
    void fitLeavesAFittingListUnchanged() {
        List<Map<String, Object>> ms = List.of(msg("system", "hi"), msg("user", "yo"));
        TokenBudget.Fitted f = TokenBudget.fit(ms, 1000, CHARS);
        assertFalse(f.changed());
        assertEquals(2, f.messages().size());
    }

    @Test
    void fitKeepsSystemAndLastAndGetsUnderCap() {
        List<Map<String, Object>> ms = new ArrayList<>(List.of(
                msg("system", "S".repeat(50)),
                msg("user", "a".repeat(400)),   // oversized middle -> truncated
                msg("assistant", "b".repeat(50)),
                msg("tool", "c".repeat(60)),
                msg("user", "Q".repeat(80))));   // the request -> kept
        TokenBudget.Fitted f = TokenBudget.fit(ms, 300, CHARS);
        assertTrue(total(f.messages()) <= 300, "must be at/under cap");
        assertEquals("system", f.messages().get(0).get("role"));
        assertEquals("user", f.messages().get(f.messages().size() - 1).get("role"));
        assertEquals("Q".repeat(80), f.messages().get(f.messages().size() - 1).get("content")); // last intact
        assertTrue(f.changed());
    }

    @Test
    void fitTruncatesAnOversizedSingleMessage() {
        List<Map<String, Object>> ms = List.of(msg("system", "S".repeat(20)), msg("user", "X".repeat(5000)));
        TokenBudget.Fitted f = TokenBudget.fit(ms, 200, CHARS);
        assertTrue(total(f.messages()) <= 200);
        assertTrue(String.valueOf(f.messages().get(1).get("content")).contains("trimmed to fit"));
    }

    @Test
    void truncateToTokensRespectsTarget() {
        String big = "word ".repeat(100);
        String cut = TokenBudget.truncateToTokens(big, 40, CHARS);
        assertTrue(CHARS.applyAsInt(cut) <= 40);
        assertTrue(cut.contains("trimmed to fit"));
    }
}
