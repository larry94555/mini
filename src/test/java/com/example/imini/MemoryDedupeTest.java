package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure de-duplication used when seeding a session from pinned + auto durable memory. */
class MemoryDedupeTest {

    @Test
    void dropsBlankAndDuplicateLinesCaseInsensitive() {
        String in = "Prefers metric units\n\nproject is imini\nPrefers Metric Units\n  project is imini  \nnew fact";
        String out = MemoryStore.dedupeLines(in);
        assertEquals("Prefers metric units\nproject is imini\nnew fact", out);
    }

    @Test
    void emptyAndNullSafe() {
        assertEquals("", MemoryStore.dedupeLines(""));
        assertEquals("", MemoryStore.dedupeLines(null));
        assertEquals("", MemoryStore.dedupeLines("   \n  \n"));
    }

    @Test
    void preservesFirstOccurrenceOrder() {
        String out = MemoryStore.dedupeLines("b\na\nb\nc\na");
        assertEquals("b\na\nc", out);
        assertTrue(out.indexOf("b") < out.indexOf("a"));
    }
}
