package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import com.example.imini.Planner.StepOutcome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Outcome classification + retry/re-plan recovery in the plan executor (fake runners, no model). */
class PlanRecoveryTest {

    private static Function<String, String> scripted(String... results) {
        Deque<String> q = new ArrayDeque<>(List.of(results));
        return prompt -> q.isEmpty() ? "STEP_STATUS: done" : q.poll();
    }

    private static String status(List<List<TodoStore.Item>> snaps, int idx) {
        List<TodoStore.Item> last = snaps.get(snaps.size() - 1);
        return last.get(idx).content() + ":" + last.get(idx).status();
    }

    @Test
    void classifyReadsExplicitStatusThenFallsBack() {
        assertEquals(StepOutcome.DONE, Planner.classify("ok\nSTEP_STATUS: done"));
        assertEquals(StepOutcome.FAILED, Planner.classify("oops\nSTEP_STATUS: failed no perms"));
        assertEquals(StepOutcome.FAILED, Planner.classify("ERROR: not found"));
        assertEquals(StepOutcome.DONE, Planner.classify("looks fine to me"));
        assertEquals(StepOutcome.FAILED, Planner.classify(null));
    }

    @Test
    void retriesAFailedStepThenSucceeds() {
        List<String> ran = new ArrayList<>();
        List<List<TodoStore.Item>> snaps = new ArrayList<>();
        Function<String, String> runner = scripted("nope\nSTEP_STATUS: failed", "fixed\nSTEP_STATUS: done");
        Function<String, String> tracked = p -> { ran.add(p); return runner.apply(p); };

        Planner.executeWithRecovery("goal", List.of("alpha"), tracked, p -> List.of(), snaps::add, 1, 0);

        assertEquals(2, ran.size(), "one retry");
        assertEquals("alpha:completed", status(snaps, 0));
    }

    @Test
    void givesUpAfterRetriesAndMarksFailedWhenNoReplans() {
        List<List<TodoStore.Item>> snaps = new ArrayList<>();
        boolean[] replanned = {false};
        Planner.executeWithRecovery("goal", List.of("alpha"),
                p -> "STEP_STATUS: failed", p -> { replanned[0] = true; return List.of(); }, snaps::add, 1, 0);
        assertEquals("alpha:failed", status(snaps, 0));
        assertFalse(replanned[0], "no replans allowed");
    }

    @Test
    void replansRemainingWorkAfterAFailure() {
        List<List<TodoStore.Item>> snaps = new ArrayList<>();
        boolean[] replanned = {false};
        Function<String, String> runner = scripted("cant\nSTEP_STATUS: failed", "did beta\nSTEP_STATUS: done");

        Planner.executeWithRecovery("goal", List.of("alpha"),
                runner,
                p -> { replanned[0] = true; return List.of("beta"); },
                snaps::add, 0, 1);

        assertTrue(replanned[0]);
        List<TodoStore.Item> last = snaps.get(snaps.size() - 1);
        assertEquals(2, last.size());
        assertEquals("alpha:failed", status(snaps, 0));
        assertEquals("beta:completed", status(snaps, 1));
    }

    @Test
    void replanCountIsBounded() {
        int[] replans = {0};
        // every step fails; replanner always offers one more step -> would loop without the cap
        Planner.executeWithRecovery("goal", List.of("s0"),
                p -> "STEP_STATUS: failed",
                p -> { replans[0]++; return List.of("s" + replans[0]); },
                items -> {}, 0, 2);
        assertEquals(2, replans[0], "re-planning stops at maxReplans");
    }
}
