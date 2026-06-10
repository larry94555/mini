package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The coding profile adds workflow guidance; general/unknown/null add nothing. */
class AgentProfileTest {

    @Test
    void codingProfileNamesToolsAndSteps() {
        String g = AgentProfile.guidance("coding");
        assertTrue(g.contains("repo_tree"));
        assertTrue(g.contains("glob"));
        assertTrue(g.contains("grep"));
        assertTrue(g.contains("read_many"));
        assertTrue(g.contains("git_diff"));
        assertTrue(g.contains("edit_file"));
        assertTrue(g.toLowerCase().contains("orient"));
        assertTrue(g.toLowerCase().contains("verify"));
    }

    @Test
    void codingIsCaseInsensitive() {
        assertEquals(AgentProfile.guidance("coding"), AgentProfile.guidance("CODING"));
        assertTrue(AgentProfile.guidance("  Coding ").contains("repo_tree"));
    }

    @Test
    void generalAndUnknownAddNothing() {
        assertEquals("", AgentProfile.guidance("general"));
        assertEquals("", AgentProfile.guidance("research"));
        assertEquals("", AgentProfile.guidance(null));
        assertEquals("", AgentProfile.guidance(""));
    }
}
