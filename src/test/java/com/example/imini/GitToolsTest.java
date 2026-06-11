package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic checks for the git_log / git_blame argument builders. */
class GitToolsTest {

    @Test
    void logArgsWithoutPath() {
        assertEquals(List.of("log", "--pretty=format:%h %ad %an: %s", "--date=short", "-n", "5"),
                CodebaseTools.gitLogArgs("", 5));
    }

    @Test
    void logArgsWithPathAppendsSeparator() {
        List<String> a = CodebaseTools.gitLogArgs("src/Foo.java", 20);
        assertTrue(a.contains("--"));
        assertEquals("src/Foo.java", a.get(a.size() - 1));
        assertEquals("20", a.get(a.indexOf("-n") + 1));
    }

    @Test
    void logArgsClampsMaxCountToAtLeastOne() {
        assertEquals("1", CodebaseTools.gitLogArgs("", 0).get(CodebaseTools.gitLogArgs("", 0).indexOf("-n") + 1));
    }

    @Test
    void blameArgsRange() {
        assertEquals(List.of("blame", "--date=short", "-L", "10,20", "--", "F.java"),
                CodebaseTools.gitBlameArgs("F.java", 10, 20));
    }

    @Test
    void blameArgsStartOnlyBoundsWindow() {
        List<String> a = CodebaseTools.gitBlameArgs("F.java", 10, 0);
        assertTrue(a.contains("-L"));
        assertTrue(a.contains("10,+200"));
    }

    @Test
    void blameArgsFullFileHasNoRange() {
        List<String> a = CodebaseTools.gitBlameArgs("F.java", 0, 0);
        assertFalse(a.contains("-L"));
        assertEquals(List.of("blame", "--date=short", "--", "F.java"), a);
    }
}
