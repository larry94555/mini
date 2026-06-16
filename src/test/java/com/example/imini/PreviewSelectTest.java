package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure hunk-selection parsing for hunk-level approval. */
class PreviewSelectTest {

    @Test
    void blankAllOrStarSelectsEverything() {
        assertEquals(Set.of(0, 1, 2), new LinkedHashSet<>(PreviewSelect.parse("", 3)));
        assertEquals(Set.of(0, 1, 2), new LinkedHashSet<>(PreviewSelect.parse("all", 3)));
        assertEquals(Set.of(0, 1, 2), new LinkedHashSet<>(PreviewSelect.parse(null, 3)));
        assertEquals(Set.of(0, 1, 2), new LinkedHashSet<>(PreviewSelect.parse("*", 3)));
    }

    @Test
    void listsAndRanges() {
        assertEquals(Set.of(0, 2), new LinkedHashSet<>(PreviewSelect.parse("0,2", 3)));
        assertEquals(Set.of(1, 2, 3), new LinkedHashSet<>(PreviewSelect.parse("1-3", 5)));
        assertEquals(Set.of(0, 1, 4), new LinkedHashSet<>(PreviewSelect.parse("0 1 4", 5)));
    }

    @Test
    void outOfRangeAndGarbageIgnored() {
        assertEquals(Set.of(0), new LinkedHashSet<>(PreviewSelect.parse("0,9,foo,-1", 3)));
        assertTrue(PreviewSelect.parse("99", 3).isEmpty());
    }

    @Test
    void pickReturnsSelectedInOrder() {
        assertEquals(List.of("a", "c"),
                PreviewSelect.pick(List.of("a", "b", "c"), new LinkedHashSet<>(List.of(2, 0))));
    }
}
