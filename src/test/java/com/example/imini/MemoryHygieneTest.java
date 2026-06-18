package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure decay rule behind the hygiene pass. */
class MemoryHygieneTest {
    private static final long DAY = 24L * 60 * 60 * 1000;

    @Test
    void prunesOnlyStaleAndNeverUsedFacts() {
        long now = 100L * DAY;
        long decay = 30 * DAY;
        // never used, observed 40 days ago -> prune
        assertTrue(MemoryStore.shouldDecay(0, 0, now - 40 * DAY, now, decay));
        // never used but only 10 days old -> keep (give it time)
        assertFalse(MemoryStore.shouldDecay(0, 0, now - 10 * DAY, now, decay));
        // used at least once -> never prune, even if old
        assertFalse(MemoryStore.shouldDecay(3, 0, now - 90 * DAY, now, decay));
        assertFalse(MemoryStore.shouldDecay(0, 1, now - 90 * DAY, now, decay));
        // no first_seen recorded -> keep
        assertFalse(MemoryStore.shouldDecay(0, 0, 0, now, decay));
    }
}
