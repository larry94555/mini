package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan (de)serialization round-trip + resuming a saved plan from the first not-completed step. */
class PlanPersistenceTest {

    @Test
    void payloadRoundTripsThroughItems() {
        List<TodoStore.Item> items = List.of(
                new TodoStore.Item("a", "completed"),
                new TodoStore.Item("b", "failed"),
                new TodoStore.Item("c", "pending"));
        List<Map<String, String>> payload = Planner.planPayload(items);
        assertEquals(items, Planner.itemsFromPayload(payload));
    }

    @Test
    void itemsFromPayloadDefaultsAndSkips() {
        List<TodoStore.Item> r = Planner.itemsFromPayload(List.of(
                Map.of("text", "x"),                       // no status -> pending
                Map.of("status", "completed")));           // no text -> skipped
        assertEquals(1, r.size());
        assertEquals("x", r.get(0).content());
        assertEquals("pending", r.get(0).status());
    }

    @Test
    void resumeRunsOnlyFromFirstUnfinishedStep() {
        List<TodoStore.Item> saved = List.of(
                new TodoStore.Item("s0", "completed"),
                new TodoStore.Item("s1", "failed"),
                new TodoStore.Item("s2", "pending"));
        List<String> ranSteps = new ArrayList<>();
        List<List<TodoStore.Item>> snaps = new ArrayList<>();

        Planner.executeFrom("goal", saved,
                prompt -> { ranSteps.add(prompt); return "ok\nSTEP_STATUS: done"; },
                p -> List.of(), snaps::add, 0, 0, null);

        assertEquals(2, ranSteps.size(), "only s1 and s2 are (re)run; s0 stays completed");
        List<TodoStore.Item> last = snaps.get(snaps.size() - 1);
        assertEquals("completed", last.get(0).status());
        assertEquals("completed", last.get(1).status());
        assertEquals("completed", last.get(2).status());
        assertTrue(ranSteps.get(0).contains("s1") || ranSteps.get(0).contains("step 2"));
    }

    @Test
    void resumeOfAlreadyCompletePlanRunsNothing() {
        List<TodoStore.Item> done = List.of(
                new TodoStore.Item("s0", "completed"),
                new TodoStore.Item("s1", "completed"));
        int[] calls = {0};
        String results = Planner.executeFrom("goal", done,
                p -> { calls[0]++; return "x"; }, p -> List.of(), items -> {}, 0, 0, null);
        assertEquals(0, calls[0]);
        assertTrue(results.isEmpty());
    }
}
