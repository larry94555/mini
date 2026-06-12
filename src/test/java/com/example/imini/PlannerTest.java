package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan parsing + the step-sequencing executor (with a fake runner -- no model needed). */
class PlannerTest {

    @Test
    void parsesNumberedBulletedAndStepKeywordLines() {
        String text = """
                Here is the plan:
                1. Read the config file
                2) Update the port
                - Restart the server
                Step 4: verify it responds
                some trailing prose that is not a step
                """;
        List<String> steps = Planner.parsePlan(text);
        assertEquals(List.of("Read the config file", "Update the port", "Restart the server",
                "verify it responds"), steps);
    }

    @Test
    void emptyOrProseOnlyTextYieldsNoSteps() {
        assertTrue(Planner.parsePlan("I will just do it directly, no list here.").isEmpty());
        assertTrue(Planner.parsePlan("").isEmpty());
        assertTrue(Planner.parsePlan(null).isEmpty());
    }

    @Test
    void parseIsCappedAtMaxSteps() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) sb.append(i).append(". step ").append(i).append("\n");
        assertEquals(Planner.MAX_STEPS, Planner.parsePlan(sb.toString()).size());
    }

    @Test
    void executeRunsEveryStepInOrderAndChecksThemOff() {
        List<String> steps = List.of("alpha", "beta", "gamma");
        List<String> ran = new ArrayList<>();
        List<List<TodoStore.Item>> snaps = new ArrayList<>();

        String results = Planner.execute("the goal", steps,
                prompt -> { ran.add(prompt); return "did it"; },
                items -> snaps.add(items));

        assertEquals(3, ran.size(), "each step's runner was invoked once, in order");
        assertTrue(ran.get(0).contains("step 1") || ran.get(0).contains("alpha"));
        // final todo snapshot: everything completed
        List<TodoStore.Item> last = snaps.get(snaps.size() - 1);
        assertEquals(-1, Planner.nextPending(last));
        for (TodoStore.Item it : last) assertEquals("completed", it.status());
        // aggregated results mention all three steps
        assertTrue(results.contains("alpha") && results.contains("beta") && results.contains("gamma"));
    }

    @Test
    void stepPromptIsFocusedOnTheCurrentStep() {
        List<String> steps = List.of("first", "second");
        String prompt = Planner.stepPrompt("goal", steps, 1, "Step 1 — first:\nok");
        assertTrue(prompt.contains("Do ONLY step 2"));
        assertTrue(prompt.contains("Progress so far"));
    }

    @Test
    void nextPendingFindsFirstUnfinished() {
        List<TodoStore.Item> items = List.of(
                new TodoStore.Item("a", "completed"),
                new TodoStore.Item("b", "pending"),
                new TodoStore.Item("c", "pending"));
        assertEquals(1, Planner.nextPending(items));
    }
}
