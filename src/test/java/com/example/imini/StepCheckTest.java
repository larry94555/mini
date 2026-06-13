package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import com.example.imini.Planner.CheckResult;
import com.example.imini.Planner.StepOutcome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Step verification: a CHECK command's result is authoritative over the model's self-report. */
class StepCheckTest {

    @Test
    void parseCheckExtractsCommandAndIsNullWhenAbsent() {
        assertEquals("grep -q foo bar.txt",
                Planner.parseCheck("did it\nCHECK: grep -q foo bar.txt\nSTEP_STATUS: done"));
        assertEquals("mvn -q -DskipTests compile",
                Planner.parseCheck("CHECK = mvn -q -DskipTests compile"));
        assertNull(Planner.parseCheck("no directive here\nSTEP_STATUS: done"));
        assertNull(Planner.parseCheck(null));
    }

    @Test
    void checkResultOverridesSelfReport() {
        assertEquals(StepOutcome.DONE, Planner.verdict("STEP_STATUS: failed", new CheckResult(true, "exit 0")));
        assertEquals(StepOutcome.FAILED, Planner.verdict("STEP_STATUS: done", new CheckResult(false, "exit 1")));
    }

    @Test
    void withoutACheckFallsBackToClassify() {
        assertEquals(StepOutcome.DONE, Planner.verdict("STEP_STATUS: done", null));
        assertEquals(StepOutcome.FAILED, Planner.verdict("STEP_STATUS: failed", null));
        assertEquals(StepOutcome.FAILED, Planner.verdict("ERROR: nope", null));
    }

    @Test
    void aFailedCheckRetriesEvenWhenTheModelClaimsSuccess() {
        Deque<String> runs = new ArrayDeque<>(List.of(
                "made it\nCHECK: test -f out.txt\nSTEP_STATUS: done",       // claims done; check will FAIL
                "really made it\nCHECK: test -f out.txt\nSTEP_STATUS: done")); // check will PASS
        Deque<Boolean> checks = new ArrayDeque<>(List.of(false, true));
        List<List<TodoStore.Item>> snaps = new ArrayList<>();

        Planner.executeWithRecovery("goal", List.of("create out.txt"),
                p -> runs.poll(),
                p -> List.of(),
                snaps::add,
                1, 0,
                cmd -> new CheckResult(checks.poll(), "fake"));

        assertEquals("completed", snaps.get(snaps.size() - 1).get(0).status());
        assertEquals(0, checks.size(), "both attempts were verified");
    }

    @Test
    void aPersistentlyFailingCheckLeavesTheStepFailed() {
        List<List<TodoStore.Item>> snaps = new ArrayList<>();
        Function<String, String> runner = p -> "claims done\nCHECK: false\nSTEP_STATUS: done";

        Planner.executeWithRecovery("goal", List.of("do it"),
                runner, p -> List.of(), snaps::add, 1, 0,
                cmd -> new CheckResult(false, "exit 1"));

        assertEquals("failed", snaps.get(snaps.size() - 1).get(0).status());
    }
}
