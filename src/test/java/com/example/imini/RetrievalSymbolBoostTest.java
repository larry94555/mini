package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The symbol boost lifts the chunk that DECLARES a queried name above one that merely mentions it. */
class RetrievalSymbolBoostTest {

    @Test
    void boostFiresOnlyOnExactSymbolNameMatch() {
        List<String> qt = RetrievalService.tokenize("where is decide defined");
        assertEquals(2.0, RetrievalService.symbolBoost(qt, "Approvals decide await", 2.0));
        assertEquals(0.0, RetrievalService.symbolBoost(qt, "foo bar baz", 2.0));
    }

    @Test
    void boostCountsEachQueryTermOnce() {
        List<String> qt = RetrievalService.tokenize("decide decide approvals");
        // "decide" (dedup -> once) + "approvals" both match -> 2 * weight
        assertEquals(4.0, RetrievalService.symbolBoost(qt, "decide approvals other", 2.0));
    }

    @Test
    void weightZeroOrEmptySymbolsDisablesBoost() {
        List<String> qt = RetrievalService.tokenize("decide");
        assertEquals(0.0, RetrievalService.symbolBoost(qt, "decide", 0.0));
        assertEquals(0.0, RetrievalService.symbolBoost(qt, "", 2.0));
        assertEquals(0.0, RetrievalService.symbolBoost(qt, null, 2.0));
    }

    @Test
    void definingChunkOutranksIncidentalMention() {
        List<String> q = RetrievalService.tokenize("decide");
        double mention = RetrievalService.lexicalScore(q, "we simply call decide() here once")
                + RetrievalService.symbolBoost(q, "", 2.0);
        double defining = RetrievalService.lexicalScore(q, "Decision decide(String t) {")
                + RetrievalService.symbolBoost(q, "decide", 2.0);
        assertTrue(defining > mention, "the chunk that declares 'decide' should rank higher");
    }
}
