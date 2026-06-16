package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure subagent-registry logic: agent parsing, /agent command parsing, merge, and listing render. */
class AgentLibraryTest {

    @Test
    void parseReadsNameToolsModelAndBody() {
        AgentLibrary.AgentDef d = AgentLibrary.parse(
                "---\nname: explore\ndescription: Map the code\ntools: grep, repo_tree, read_file\nmodel: fast\n---\nYou are an explorer.",
                "fallback");
        assertEquals("explore", d.name());
        assertEquals("Map the code", d.description());
        assertEquals(List.of("grep", "repo_tree", "read_file"), d.tools());
        assertEquals("fast", d.model());
        assertTrue(d.body().contains("explorer"));
    }

    @Test
    void parseCommandSplitsNameAndTask() {
        AgentLibrary.Invocation inv = AgentLibrary.parseCommand("/agent explore find the auth flow");
        assertEquals("explore", inv.name());
        assertEquals("find the auth flow", inv.task());
        assertNull(AgentLibrary.parseCommand("/agents"));       // listing, not delegation
        assertNull(AgentLibrary.parseCommand("hello"));
        assertNull(AgentLibrary.parseCommand("/agent"));         // no name
    }

    @Test
    void mergeLetsDiskOverrideBuiltinByName() {
        List<AgentLibrary.AgentDef> merged = AgentLibrary.merge(
                List.of(new AgentLibrary.AgentDef("explore", "builtin", List.of(), "", "b")),
                List.of(new AgentLibrary.AgentDef("explore", "disk", List.of(), "", "d")));
        assertEquals(1, merged.size());
        assertEquals("disk", merged.get(0).description());
    }

    @Test
    void renderListShowsNamesAndToolScopes() {
        String out = AgentLibrary.renderList(List.of(
                new AgentLibrary.AgentDef("research", "Search the web", List.of("web_search", "web_fetch"), "", "r")));
        assertTrue(out.contains("research - Search the web"));
        assertTrue(out.contains("[tools: web_search, web_fetch]"));
        assertTrue(AgentLibrary.renderList(List.of()).startsWith("No subagents"));
    }
}
