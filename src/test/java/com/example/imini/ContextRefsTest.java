package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure @file/@directory parsing + referenced-context block assembly. */
class ContextRefsTest {

    @Test
    void parsePullsPathTokensIgnoringMentionsAndEscapes() {
        List<String> refs = ContextRefs.parse("see @src/A.java and @docs/, also email me@host and @@escaped.");
        assertEquals(List.of("src/A.java", "docs/"), refs); // me@host (no leading space) + @@ ignored
    }

    @Test
    void parseDedupesAndTrimsTrailingPunctuation() {
        assertEquals(List.of("a/b.txt"), ContextRefs.parse("@a/b.txt, @a/b.txt; (@a/b.txt)"));
    }

    @Test
    void blockRendersFilesAndDirectories() {
        String out = ContextRefs.block(List.of(
                new ContextRefs.Resolved("src/A.java", "file", "class A{}", 9, 0),
                new ContextRefs.Resolved("docs", "dir", "- a.md\n- b.md", 0, 2)));
        assertTrue(out.contains("<referenced-context>") && out.contains("</referenced-context>"));
        assertTrue(out.contains("--- @src/A.java (file, 9 bytes) ---"));
        assertTrue(out.contains("--- @docs (directory, 2 entries) ---"));
        assertTrue(out.contains("class A{}") && out.contains("- a.md"));
    }

    @Test
    void emptyBlockIsBlank() {
        assertEquals("", ContextRefs.block(List.of()));
        assertEquals(List.of(), ContextRefs.parse("no references here"));
    }
}
