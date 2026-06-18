package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing the model's "3,1,5" selection in two-stage recall. */
class MemoryRerankParseTest {

    private final List<String> cands = List.of("alpha", "bravo", "charlie", "delta", "echo");

    @Test
    void parsesCommaSeparatedIndicesInOrderCappedAtK() {
        List<String> out = MemoryStore.parseRerankSelection("3,1,5", cands, 3);
        assertEquals(List.of("charlie", "alpha", "echo"), out);
    }

    @Test
    void ignoresOutOfRangeAndDuplicateIndices() {
        List<String> out = MemoryStore.parseRerankSelection("the best are 2, 2, 9, 4", cands, 10);
        assertEquals(List.of("bravo", "delta"), out);
    }

    @Test
    void emptyOrGarbageYieldsEmpty() {
        assertTrue(MemoryStore.parseRerankSelection("", cands, 3).isEmpty());
        assertTrue(MemoryStore.parseRerankSelection("none relevant", cands, 3).isEmpty());
        assertTrue(MemoryStore.parseRerankSelection(null, cands, 3).isEmpty());
    }

    @Test
    void capsAtK() {
        assertEquals(2, MemoryStore.parseRerankSelection("1,2,3,4,5", cands, 2).size());
    }
}
