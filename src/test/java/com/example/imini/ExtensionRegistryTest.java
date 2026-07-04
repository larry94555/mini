package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.example.imini.ScriptedAgent.prop;
import static com.example.imini.ScriptedAgent.schema;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure, offline unit tests for {@link ExtensionRegistry}: discovery of tools/agents/commands, the
 * master kill-switch, empty=no-op, event dispatch, name-collision handling among extensions, and
 * per-extension isolation. No Spring, no model — constructs the registry directly.
 */
class ExtensionRegistryTest {

    /** A sample extension contributing one of each and recording the events it observes. */
    static final class SampleExt implements Extension {
        final List<LoopEvent> seen = new ArrayList<>();

        @Override public String name() { return "sample"; }

        @Override public List<Tool> tools(ExtensionContext ctx) {
            return List.of(new Tool("reverse_text", "Reverse a string.",
                    schema(Map.of("text", prop("string")), "text"), false,
                    args -> new StringBuilder(String.valueOf(args.get("text"))).reverse().toString()));
        }

        @Override public List<AgentLibrary.AgentDef> agents(ExtensionContext ctx) {
            return List.of(new AgentLibrary.AgentDef("greeter", "Say hi.",
                    List.of("read_file"), "", "You greet."));
        }

        @Override public List<Command> commands(ExtensionContext ctx) {
            return List.of(new Command("shout", "Shout the args.", "SHOUT: $ARGS"));
        }

        @Override public void onEvent(LoopEvent e, ExtensionContext ctx) { seen.add(e); }
    }

    @Test
    void discoversToolsAgentsCommandsAndRunsTheTool() {
        ExtensionRegistry r = new ExtensionRegistry(List.of(new SampleExt()), true);
        assertEquals(List.of("reverse_text"), r.tools().stream().map(t -> t.name).toList());
        assertEquals(List.of("greeter"), r.agents().stream().map(AgentLibrary.AgentDef::name).toList());
        assertTrue(r.commands().containsKey("shout"));
        // the contributed tool actually executes
        assertEquals("cba", r.tools().get(0).executor.apply(Map.of("text", "abc")));
    }

    @Test
    void emptyRegistryIsANoOp() {
        ExtensionRegistry r = new ExtensionRegistry(List.of(), true);
        assertTrue(r.tools().isEmpty());
        assertTrue(r.agents().isEmpty());
        assertTrue(r.commands().isEmpty());
        assertEquals(0, r.diagnostics().get("count"));
    }

    @Test
    void killSwitchDisablesContributionsAndEvents() {
        SampleExt ext = new SampleExt();
        ExtensionRegistry r = new ExtensionRegistry(List.of(ext), false);
        assertTrue(r.tools().isEmpty(), "disabled: no tools");
        assertTrue(r.agents().isEmpty(), "disabled: no agents");
        assertTrue(r.commands().isEmpty(), "disabled: no commands");
        r.emit(LoopEvent.preTool("s", "reverse_text", Map.of()));
        assertTrue(ext.seen.isEmpty(), "disabled registry must not deliver events");
        assertFalse((Boolean) r.diagnostics().get("enabled"));
    }

    @Test
    void emitFansOutPreAndPostEvents() {
        SampleExt ext = new SampleExt();
        ExtensionRegistry r = new ExtensionRegistry(List.of(ext), true);
        r.emit(LoopEvent.preTool("s1", "reverse_text", Map.of("text", "x")));
        r.emit(LoopEvent.postTool("s1", "reverse_text", Map.of("text", "x"), "x"));
        assertEquals(2, ext.seen.size());
        assertEquals(LoopEvent.Type.PRE_TOOL_USE, ext.seen.get(0).type());
        assertEquals(LoopEvent.Type.POST_TOOL_USE, ext.seen.get(1).type());
        assertEquals("reverse_text", ext.seen.get(0).tool());
    }

    @Test
    void duplicateToolNameAmongExtensionsFirstWins() {
        Extension a = new Extension() {
            @Override public String name() { return "a"; }
            @Override public List<Tool> tools(ExtensionContext c) {
                return List.of(new Tool("dup", "A", schema(Map.of()), false, x -> "A"));
            }
        };
        Extension b = new Extension() {
            @Override public String name() { return "b"; }
            @Override public List<Tool> tools(ExtensionContext c) {
                return List.of(new Tool("dup", "B", schema(Map.of()), false, x -> "B"));
            }
        };
        ExtensionRegistry r = new ExtensionRegistry(List.of(a, b), true);
        assertEquals(1, r.tools().size(), "duplicate names collapse to one");
        assertEquals("A", r.tools().get(0).executor.apply(Map.of()), "first extension wins");
    }

    @Test
    void aThrowingExtensionIsIsolatedAndOthersStillLoad() {
        Extension bad = new Extension() {
            @Override public String name() { return "bad"; }
            @Override public List<Tool> tools(ExtensionContext c) { throw new RuntimeException("boom"); }
        };
        ExtensionRegistry r = new ExtensionRegistry(List.of(bad, new SampleExt()), true);
        assertEquals(List.of("reverse_text"), r.tools().stream().map(t -> t.name).toList(),
                "a throwing extension is skipped; good ones still load");
    }

    @Test
    void diagnosticsSummarizeEachExtensionsContributions() {
        ExtensionRegistry r = new ExtensionRegistry(List.of(new SampleExt()), true);
        Map<String, Object> d = r.diagnostics();
        assertEquals(1, d.get("count"));
        assertTrue((Boolean) d.get("enabled"));
        assertTrue(d.get("extensions").toString().contains("reverse_text"),
                "diagnostics list the contributed tools: " + d.get("extensions"));
    }
}
