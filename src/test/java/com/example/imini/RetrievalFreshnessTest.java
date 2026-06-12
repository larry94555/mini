package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.imini.RetrievalService.IndexPlan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The incremental-index diff classifies files into new/changed (upsert) vs removed. */
class RetrievalFreshnessTest {

    private Map<String, Long> map(Object... kv) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).longValue());
        return m;
    }

    @Test
    void detectsNewChangedRemovedAndSkipsUnchanged() {
        Map<String, Long> indexed = map("A.java", 100, "B.java", 100, "gone.java", 100);
        Map<String, Long> current = map("A.java", 100, "B.java", 200, "new.java", 50);
        IndexPlan plan = RetrievalService.diff(indexed, current);

        assertTrue(plan.upsert().contains("B.java"), "changed mtime -> reindex");
        assertTrue(plan.upsert().contains("new.java"), "new file -> index");
        assertFalse(plan.upsert().contains("A.java"), "unchanged -> skipped");
        assertEquals(2, plan.upsert().size());

        assertTrue(plan.remove().contains("gone.java"), "deleted file -> removed");
        assertEquals(1, plan.remove().size());
    }

    @Test
    void coldStartIndexesEverything() {
        Map<String, Long> current = map("A.java", 1, "B.java", 2, "C.java", 3);
        IndexPlan plan = RetrievalService.diff(Map.of(), current);
        assertEquals(3, plan.upsert().size());
        assertTrue(plan.remove().isEmpty());
    }

    @Test
    void emptyWorkspaceRemovesAll() {
        Map<String, Long> indexed = map("A.java", 1, "B.java", 2);
        IndexPlan plan = RetrievalService.diff(indexed, Map.of());
        assertTrue(plan.upsert().isEmpty());
        assertEquals(2, plan.remove().size());
    }

    @Test
    void noChangesProducesEmptyPlan() {
        Map<String, Long> same = map("A.java", 7, "B.java", 8);
        IndexPlan plan = RetrievalService.diff(same, same);
        assertTrue(plan.upsert().isEmpty());
        assertTrue(plan.remove().isEmpty());
    }
}
