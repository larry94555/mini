package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Relevance-ranked memory injection reuses RetrievalService's pure lexical scorer to order durable facts
 * against a query. This verifies the scoring/tokenizing primitives the seeding relies on.
 */
class MemoryRankTest {

    @Test
    void lexicalScoreRanksByQueryOverlap() {
        List<String> q = RetrievalService.tokenize("how do I configure the database connection");
        double dbFact = RetrievalService.lexicalScore(q, "the database connection is configured in application.properties");
        double unrelated = RetrievalService.lexicalScore(q, "the user prefers metric units for distance");
        assertTrue(dbFact > unrelated, "db fact (" + dbFact + ") should outrank unrelated (" + unrelated + ")");
        assertTrue(dbFact > 0);
    }

    @Test
    void tokenizeIsCaseInsensitiveAndSplitsWords() {
        List<String> t = RetrievalService.tokenize("Database, Connection!");
        assertTrue(t.contains("database"));
        assertTrue(t.contains("connection"));
    }

    @Test
    void blankQueryYieldsNoTokens() {
        assertEquals(0, RetrievalService.tokenize("").size());
        assertEquals(0, RetrievalService.tokenize("   ").size());
    }
}
