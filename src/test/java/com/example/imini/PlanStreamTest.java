package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The plan SSE payload mirrors the todo checklist (text + status), in order. */
class PlanStreamTest {

    @Test
    void payloadMapsTextAndStatusInOrder() {
        List<TodoStore.Item> items = List.of(
                new TodoStore.Item("alpha", "completed"),
                new TodoStore.Item("beta", "in_progress"),
                new TodoStore.Item("gamma", "pending"),
                new TodoStore.Item("delta", "failed"));

        List<Map<String, String>> payload = Planner.planPayload(items);

        assertEquals(4, payload.size());
        assertEquals("alpha", payload.get(0).get("text"));
        assertEquals("completed", payload.get(0).get("status"));
        assertEquals("in_progress", payload.get(1).get("status"));
        assertEquals("pending", payload.get(2).get("status"));
        assertEquals("failed", payload.get(3).get("status"));
    }

    @Test
    void nullStatusDefaultsToPending() {
        List<Map<String, String>> payload = Planner.planPayload(List.of(new TodoStore.Item("x", null)));
        assertEquals("pending", payload.get(0).get("status"));
    }

    @Test
    void payloadWithToolsAttachesPerStepTranscript() {
        List<TodoStore.Item> items = List.of(
                new TodoStore.Item("Add endpoint", "completed"),
                new TodoStore.Item("Test it", "in_progress"));
        Map<Integer, List<String>> tx = Map.of(
                0, List.of("write_file src/App.java [ok]", "run_command $ mvn -q test [ok]"));

        List<Map<String, Object>> payload = Planner.planPayload(items, tx);

        assertEquals("completed", payload.get(0).get("status"));
        assertEquals(List.of("write_file src/App.java [ok]", "run_command $ mvn -q test [ok]"),
                payload.get(0).get("tools"));
        assertEquals(List.of(), payload.get(1).get("tools"), "steps with no recorded tools get an empty list");
    }

    @Test
    void payloadWithNullTranscriptGivesEmptyToolLists() {
        List<Map<String, Object>> payload =
                Planner.planPayload(List.of(new TodoStore.Item("a", "pending")), null);
        assertEquals(List.of(), payload.get(0).get("tools"));
    }
}
