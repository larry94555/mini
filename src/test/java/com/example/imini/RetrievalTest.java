package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic checks for retrieval scoring (no model / DB needed). */
class RetrievalTest {

    @Test
    void lexicalScoreFavorsMatchingChunk() {
        List<String> q = RetrievalService.tokenize("workspace root configuration");
        double hit = RetrievalService.lexicalScore(q, "the workspace root is set in configuration here");
        double miss = RetrievalService.lexicalScore(q, "completely unrelated text about penguins");
        assertTrue(hit > miss, "hit=" + hit + " miss=" + miss);
        assertTrue(hit > 0);
    }

    @Test
    void lexicalScoreZeroWhenNoOverlap() {
        List<String> q = RetrievalService.tokenize("sandbox command screening");
        assertEquals(0.0, RetrievalService.lexicalScore(q, "nothing in common at all"));
    }

    @Test
    void tokenizeLowercasesSplitsAndDropsShort() {
        List<String> t = RetrievalService.tokenize("The Sandbox.java a 42");
        assertTrue(t.contains("the"));
        assertTrue(t.contains("sandbox"));
        assertTrue(t.contains("java"));
        assertTrue(t.contains("42"));
        assertFalse(t.contains("a")); // length < 2 dropped
    }

    @Test
    void cosineIdenticalVsOrthogonal() {
        float[] a = {1f, 0f};
        float[] b = {0f, 1f};
        assertTrue(RetrievalService.cosine(a, a) > 0.99);
        assertEquals(0.0, RetrievalService.cosine(a, b));
    }
}
