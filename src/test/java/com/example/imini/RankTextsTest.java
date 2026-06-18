package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetrievalService.rankTexts is the shared ranker reused by durable-memory injection and recall_memory.
 * With embeddings off (the default), it ranks by lexical overlap; this verifies that ordering behavior.
 */
class RankTextsTest {

    @Test
    void ranksMostRelevantFirstLexically() {
        RetrievalService r = new RetrievalService(null); // embeddings default off -> lexical, no DB needed
        List<String> facts = List.of(
                "the user prefers metric units for distance",
                "the database connection lives in application.properties",
                "the project ships a CLI and a web UI");
        List<String> ranked = r.rankTexts("where is the database connection configured", facts);
        assertEquals(3, ranked.size());
        assertTrue(ranked.get(0).contains("database connection"),
                "expected the db fact first, got: " + ranked.get(0));
    }

    @Test
    void emptyInputYieldsEmpty() {
        RetrievalService r = new RetrievalService(null);
        assertTrue(r.rankTexts("anything", List.of()).isEmpty());
        assertTrue(r.rankTexts("anything", null).isEmpty());
    }

    @Test
    void blankQueryPreservesOrder() {
        RetrievalService r = new RetrievalService(null);
        List<String> facts = List.of("alpha", "beta", "gamma");
        assertEquals(facts, r.rankTexts("", facts));
    }
}
