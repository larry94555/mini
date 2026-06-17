package com.example.imini;

/**
 * Pure decision logic for automatic plan-mode fallback. When a single normal (non-plan) turn's assembled
 * prompt would exceed the enforced token cap, answering it in one shot means the budget enforcer must
 * discard/condense context to fit -- often degrading the answer. Switching to plan mode instead lets the
 * agent decompose the work into steps, each of which is sent within the budget.
 *
 * <p>The trigger is intentionally simple and deterministic so it is easy to reason about and test: fall
 * back only when the feature is enabled, the caller is not already planning, and the measured prompt
 * exceeds the cap (optionally with a margin so we don't flip on a tiny overshoot the trimmer handles
 * cleanly).
 */
public final class PlanFallback {

    private PlanFallback() {}

    /**
     * @param promptTokens measured/estimated tokens of the would-be first model call
     * @param promptCap    the enforced prompt cap (see {@link TokenBudgetService#promptCap(int)})
     * @param enabled      the {@code agent.plan.auto-fallback} setting
     * @param alreadyPlanning true if this turn is already a plan run (never re-trigger)
     * @return true to route the turn through plan mode instead of a normal run
     */
    public static boolean shouldFallback(int promptTokens, int promptCap, boolean enabled,
                                         boolean alreadyPlanning) {
        if (!enabled || alreadyPlanning) return false;
        if (promptCap <= 0) return false;
        return promptTokens > promptCap;
    }
}
