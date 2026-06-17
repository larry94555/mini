package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure /loop parsing + prompt/continue logic. */
class LoopCommandTest {

    @Test
    void detectsLoopCommand() {
        assertTrue(LoopCommand.isLoop("/loop fix it"));
        assertTrue(LoopCommand.isLoop("/loop"));
        assertFalse(LoopCommand.isLoop("/loopy"));
        assertFalse(LoopCommand.isLoop("loop fix it"));
    }

    @Test
    void parsesGoalCheckAndAttempts() {
        LoopCommand.Spec s = LoopCommand.parse("/loop check=\"mvn -q test\" attempts=4 make the test pass", 5, 20);
        assertEquals("mvn -q test", s.check());
        assertEquals(4, s.maxAttempts());
        assertEquals("make the test pass", s.goal());
    }

    @Test
    void unquotedCheckIsSingleToken_andAttemptsClamped() {
        LoopCommand.Spec s = LoopCommand.parse("/loop attempts=999 check=build.sh do the thing", 5, 20);
        assertEquals("build.sh", s.check());
        assertEquals(20, s.maxAttempts());          // clamped to hardMax
        assertEquals("do the thing", s.goal());
    }

    @Test
    void noCheckLeavesNullAndUsesDefaultAttempts() {
        LoopCommand.Spec s = LoopCommand.parse("/loop just do it", 5, 20);
        assertNull(s.check());
        assertEquals(5, s.maxAttempts());
        assertEquals("just do it", s.goal());
    }

    @Test
    void nextPromptAddsFailureOnRetries() {
        assertEquals("g", LoopCommand.nextPrompt("g", 1, "boom"));
        assertTrue(LoopCommand.nextPrompt("g", 2, "boom").contains("boom"));
        assertEquals("g", LoopCommand.nextPrompt("g", 2, null));
    }

    @Test
    void shouldContinueRespectsCheckPassAndBudget() {
        assertFalse(LoopCommand.shouldContinue(1, 5, true, true));   // passed
        assertFalse(LoopCommand.shouldContinue(1, 5, false, false)); // no check
        assertTrue(LoopCommand.shouldContinue(1, 5, false, true));   // failing, attempts remain
        assertFalse(LoopCommand.shouldContinue(5, 5, false, true));  // budget spent
    }
}
