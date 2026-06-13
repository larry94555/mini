package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure status roll-up used in plan-history summaries. */
class PlanHistoryTest {

    @Test
    void summarizesMixedStatuses() {
        assertEquals("4 steps: 2 done, 1 failed, 1 pending", PlanHistory.summarize(List.of(
                new TodoStore.Item("a", "completed"),
                new TodoStore.Item("b", "completed"),
                new TodoStore.Item("c", "failed"),
                new TodoStore.Item("d", "pending"))));
    }

    @Test
    void summarizesAllDone() {
        assertEquals("2 steps: 2 done", PlanHistory.summarize(List.of(
                new TodoStore.Item("a", "completed"),
                new TodoStore.Item("b", "completed"))));
    }

    @Test
    void countsInProgressAndNullAsPending() {
        assertEquals("2 steps: 0 done, 2 pending", PlanHistory.summarize(List.of(
                new TodoStore.Item("a", "in_progress"),
                new TodoStore.Item("b", null))));
    }
}
