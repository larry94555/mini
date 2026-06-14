package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure parsing/formatting of git output into an edit-trust block. */
class EditSummaryTest {

    private static final String STATUS = "## main...origin/main\n M src/App.java\nA  src/New.java\n?? notes.txt\n";
    private static final String STAT = " src/App.java | 4 ++--\n src/New.java | 10 +++++\n "
            + "2 files changed, 12 insertions(+), 2 deletions(-)\n";

    @Test
    void parsesStatusIgnoringBranchHeader() {
        List<EditSummary.FileChange> c = EditSummary.parseStatus(STATUS);
        assertEquals(3, c.size());
        assertEquals("M", c.get(0).code());
        assertEquals("src/App.java", c.get(0).path());
        assertEquals("??", c.get(2).code());
    }

    @Test
    void parsesAndSummarizesTheDiffStat() {
        assertEquals("2 files changed, 12 insertions(+), 2 deletions(-)", EditSummary.parseStat(STAT));
        assertEquals("2 files changed, 12 insertions(+), 2 deletions(-)", EditSummary.oneLine(STATUS, STAT));
        assertEquals("no changes", EditSummary.oneLine("", ""));
    }

    @Test
    void formatsAVerifiedBlockWithFilesAndStat() {
        String block = EditSummary.format(STATUS, STAT, Set.of("src/App.java"));
        assertTrue(block.startsWith("---\nEdits (verified with git):"));
        assertTrue(block.contains("src/App.java (M)"));
        assertTrue(block.contains("git diff --stat: 2 files changed"));
    }

    @Test
    void emptyWhenNothingChanged() {
        assertEquals("", EditSummary.format("", "", Set.of()));
        assertEquals("", EditSummary.format("## main", "", null));
    }

    @Test
    void fallsBackToRunPathsWhenGitSeesNothing() {
        String block = EditSummary.format("", "", Set.of("scratch/out.txt"));
        assertTrue(block.contains("files this run touched: scratch/out.txt"));
        assertTrue(block.contains("not a git repo"));
    }

    @Test
    void stepNoteSummarizesAStepsEditsForLaterContext() {
        String note = EditSummary.stepNote(List.of("src/App.java", "src/New.java"),
                "2 files changed, 12 insertions(+)");
        assertTrue(note.startsWith("files changed this step: src/App.java, src/New.java"));
        assertTrue(note.contains("diff so far: 2 files changed"));

        assertEquals("files changed this step: only.java", EditSummary.stepNote(List.of("only.java"), ""));
        assertEquals("", EditSummary.stepNote(List.of(), "x"));
        assertEquals("", EditSummary.stepNote(null, "x"));
    }

    @Test
    void stepNoteHonorsACustomDiffLabel() {
        String note = EditSummary.stepNote(List.of("b.txt"), "1 file changed", "diff this step");
        assertTrue(note.contains("diff this step: 1 file changed"));
        assertFalse(note.contains("diff so far"));
    }

    @Test
    void parseNamesSplitsAndTrimsDroppingBlanks() {
        assertEquals(List.of("src/A.java", "src/B.java", "src/C.java"),
                EditSummary.parseNames("src/A.java\nsrc/B.java\n\n  src/C.java  \n"));
        assertTrue(EditSummary.parseNames("").isEmpty());
        assertTrue(EditSummary.parseNames(null).isEmpty());
    }
}
