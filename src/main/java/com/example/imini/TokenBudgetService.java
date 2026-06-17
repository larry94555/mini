package com.example.imini;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds the runtime-configurable token budget for a llama-server call. The budget is the maximum number
 * of tokens a single request may use (prompt + the reserved response); the enforced PROMPT cap is the
 * budget minus the response reservation, and is additionally clamped to the server's detected context
 * window when known -- so even a budget larger than the server's {@code n_ctx} cannot overflow it.
 *
 * <p>Default 8500 (from {@code agent.max-prompt-tokens}); settable at runtime from the web UI or
 * {@code POST /settings/token-budget}, and via the config file.
 */
@Component
public class TokenBudgetService {

    /** Don't let the budget be set so low the agent can't function. */
    public static final int MIN_BUDGET = 1024;

    @Value("${agent.max-prompt-tokens:8500}")
    private int configured;

    @Value("${agent.max-tokens:1024}")
    private int reservedResponse;

    private static final String KEY = "token.budget";

    private final SettingsStore settings;
    private final AtomicInteger budget = new AtomicInteger(-1);

    public TokenBudgetService(SettingsStore settings) {
        this.settings = settings;
    }

    private int current() {
        int b = budget.get();
        if (b < 0) {
            int persisted = settings.getInt(KEY, Math.max(MIN_BUDGET, configured)); // durable value wins
            budget.compareAndSet(-1, Math.max(MIN_BUDGET, persisted));
            b = budget.get();
        }
        return b;
    }

    /** The configured total-call budget (prompt + reserved response). */
    public int budget() { return current(); }

    /** Tokens reserved for the model's response (mirrors {@code agent.max-tokens}). */
    public int reservedResponse() { return reservedResponse; }

    /** Update the budget at runtime (floored at {@link #MIN_BUDGET}); returns the value actually set. */
    public int setBudget(int tokens) {
        int v = Math.max(MIN_BUDGET, tokens);
        budget.set(v);
        settings.setInt(KEY, v); // persist so the change survives a restart
        return v;
    }

    /**
     * The hard PROMPT cap to enforce, given the server's context window ({@code serverCtx <= 0} = unknown).
     * = min(budget, serverCtx) - reservedResponse, floored so there is always room to work.
     */
    public int promptCap(int serverCtx) {
        int effective = serverCtx > 0 ? Math.min(current(), serverCtx) : current();
        return Math.max(MIN_BUDGET / 2, effective - Math.max(0, reservedResponse));
    }
}
