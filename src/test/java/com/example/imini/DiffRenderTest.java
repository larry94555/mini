package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unified-diff rendering for patch previews. */
class DiffRenderTest {

    @Test
    void modifyShowsTrimmedHunkWithCounts() {
        DiffRender.FileDiff d = DiffRender.unified("A.java", "a\nb\nc\n", "a\nB\nc\n");
        assertEquals("modify", d.kind());
        assertEquals(1, d.added());
        assertEquals(1, d.removed());
        assertTrue(d.diff().contains("- b"));
        assertTrue(d.diff().contains("+ B"));
        assertTrue(d.diff().contains("@@ -2,1 +2,1 @@"));
    }

    @Test
    void createCountsAllLines() {
        DiffRender.FileDiff d = DiffRender.unified("New.java", null, "x\ny\n");
        assertEquals("create", d.kind());
        assertEquals(2, d.added());
        assertEquals(0, d.removed());
        assertTrue(d.diff().contains("(new file)"));
    }

    @Test
    void unchangedIsReported() {
        assertEquals("unchanged", DiffRender.unified("S.java", "x\n", "x\n").kind());
    }

    @Test
    void summaryAggregatesAndIgnoresUnchanged() {
        DiffRender.FileDiff mod = DiffRender.unified("A", "a\nb\n", "a\nB\n");
        DiffRender.FileDiff cre = DiffRender.unified("B", null, "x\ny\n");
        DiffRender.FileDiff same = DiffRender.unified("C", "z\n", "z\n");
        assertEquals("2 file(s), +3 -1", DiffRender.summary(List.of(mod, cre, same)));
    }
}
