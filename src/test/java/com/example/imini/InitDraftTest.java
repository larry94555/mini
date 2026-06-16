package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure /init rendering + merge-not-replace logic. */
class InitDraftTest {

    private static RepoScan.Facts facts() {
        return new RepoScan.Facts("maven", List.of("java"), List.of("pom.xml"),
                List.of("src/"), "mvn -q -DskipTests package", "mvn -q test", 42);
    }

    @Test
    void headingsAndMissingSections() {
        String draft = InitDraft.render("P", facts());
        assertTrue(InitDraft.headings(draft).contains("Build and test"));
        String existing = "# P\n\n## Conventions\n\n- x\n";
        List<String> missing = InitDraft.missingSections(existing, draft);
        assertTrue(missing.contains("Build and test"));
        assertFalse(missing.contains("Conventions")); // already present (case-insensitive)
    }

    @Test
    void sectionBlocksSplitByHeading() {
        Map<String, String> blocks = InitDraft.sectionBlocks(InitDraft.render("P", facts()));
        assertTrue(blocks.containsKey("Build and test"));
        assertTrue(blocks.get("Build and test").startsWith("## Build and test"));
    }

    @Test
    void augmentAppendsMissingSectionsPreservingUserContent() {
        String existing = "# MyProj\n\nHand-written intro.\n\n## Conventions\n\n- Use tabs.\n";
        String merged = InitDraft.augment(existing, InitDraft.render("MyProj", facts()));
        assertTrue(merged.contains("Hand-written intro."));   // preamble preserved
        assertTrue(merged.contains("- Use tabs."));           // user's Conventions preserved
        assertTrue(merged.contains("## Build and test"));     // missing section appended
        assertEquals(1, merged.split("## Conventions").length - 1); // not duplicated
        assertTrue(merged.contains("Added by `imini /init`")); // clear marker
    }

    @Test
    void augmentIsNoOpWhenNothingMissing() {
        String full = InitDraft.render("P", facts());
        assertEquals(full, InitDraft.augment(full, InitDraft.render("P", facts())));
    }
}
