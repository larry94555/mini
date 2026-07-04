package com.example.imini;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.imini.ScriptedAgent.answer;
import static com.example.imini.ScriptedAgent.call;
import static com.example.imini.ScriptedAgent.prop;
import static com.example.imini.ScriptedAgent.schema;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that an in-process {@link Extension}'s tool is callable through the REAL
 * {@link AgentEngine} — validated + dispatched exactly like a built-in — and that the extension
 * observes the PRE/POST tool lifecycle events. Model-free (scripted), fully offline.
 */
class ExtensionToolTraceTest {

    /** An extension that contributes a read-only tool and records the loop events it observes. */
    static final class RecordingExt implements Extension {
        final List<LoopEvent> seen = new ArrayList<>();

        @Override public String name() { return "recorder"; }

        @Override public List<Tool> tools(ExtensionContext ctx) {
            return List.of(new Tool("reverse_text", "Reverse a string.",
                    schema(Map.of("text", prop("string")), "text"), false,
                    args -> new StringBuilder(String.valueOf(args.get("text"))).reverse().toString()));
        }

        @Override public void onEvent(LoopEvent e, ExtensionContext ctx) { seen.add(e); }
    }

    @Test
    void extensionToolIsCallableAndLifecycleEventsFire() throws Exception {
        Path dir = Files.createTempDirectory("imini-ext-");
        RecordingExt ext = new RecordingExt();
        ExtensionRegistry registry = new ExtensionRegistry(List.of(ext), true);

        // The engine is handed the extension's tools exactly as ToolRegistry would publish them.
        Map<String, Tool> tools = new LinkedHashMap<>();
        for (Tool t : registry.tools()) tools.put(t.name, t);

        ScriptedAgent.ScriptedLlama model = new ScriptedAgent.ScriptedLlama(
                call("reverse_text", Map.of("text", "abc")),
                answer("done"));
        ScriptedAgent.Harness h = ScriptedAgent.harness(model, dir);
        // Wire the extension registry into the engine so lifecycle events are emitted (prod: @Autowired).
        ScriptedAgent.setField(h.engine, AgentEngine.class, "extensions", registry);

        String out = h.engine.run(ScriptedAgent.systemPrompt(), "Reverse abc.",
                tools, PermissionService.Mode.AUTO, "main", "ext-1", RunSink.NOOP);

        assertEquals("done", out);
        // the reversed output flowed back to the model as a tool result
        assertTrue(model.toolResults().contains("cba"), "tool results=" + model.toolResults());
        // the extension observed both a PRE and a POST event for the tool
        assertTrue(ext.seen.stream().anyMatch(
                        e -> e.type() == LoopEvent.Type.PRE_TOOL_USE && "reverse_text".equals(e.tool())),
                "expected a PRE_TOOL_USE for reverse_text, saw " + ext.seen);
        assertTrue(ext.seen.stream().anyMatch(
                        e -> e.type() == LoopEvent.Type.POST_TOOL_USE && "cba".equals(e.result())),
                "expected a POST_TOOL_USE carrying the result, saw " + ext.seen);
    }
}
