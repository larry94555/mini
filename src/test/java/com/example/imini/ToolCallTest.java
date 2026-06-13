package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure tool-call summary/outcome formatting + the in-progress step locator. */
class ToolCallTest {

    @Test
    void summarizesByToolType() {
        assertEquals("src/App.java",
                ToolCall.summarize("write_file", Map.of("path", "src/App.java", "content", "x")));
        assertEquals("src/App.java",
                ToolCall.summarize("edit_file", Map.of("path", "src/App.java")));
        assertEquals("$ mvn -q test",
                ToolCall.summarize("run_command", Map.of("command", "mvn -q test")));
        assertEquals("patch", ToolCall.summarize("apply_patch", Map.of("patch", "...")));
        assertEquals("build/x.txt", ToolCall.summarize("apply_patch", Map.of("path", "build/x.txt")));
        assertEquals("", ToolCall.summarize("write_file", Map.of()));
    }

    @Test
    void classifiesOutcomeFromResultPrefix() {
        assertEquals("ok", ToolCall.outcome("wrote 10 lines"));
        assertEquals("ok", ToolCall.outcome(null));
        assertEquals("error", ToolCall.outcome("ERROR: boom"));
        assertEquals("error", ToolCall.outcome("DENIED: outside sandbox"));
        assertEquals("error", ToolCall.outcome("INVALID_ARGS: missing path"));
        assertEquals("error", ToolCall.outcome("BLOCKED: by hook"));
    }

    @Test
    void rendersAOneLineTranscriptEntry() {
        assertEquals("write_file src/App.java [ok]",
                new ToolCall(0L, "write_file", "src/App.java", "ok").line());
        assertEquals("todo_write [ok]", new ToolCall(0L, "todo_write", "", "ok").line());
    }

    @Test
    void activeStepFindsTheInProgressItem() {
        assertEquals(1, RunRecorder.activeStep(List.of(
                new TodoStore.Item("a", "completed"),
                new TodoStore.Item("b", "in_progress"),
                new TodoStore.Item("c", "pending"))));
        assertEquals(-1, RunRecorder.activeStep(List.of(new TodoStore.Item("a", "completed"))));
        assertEquals(-1, RunRecorder.activeStep(null));
    }
}
