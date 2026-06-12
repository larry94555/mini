package com.example.imini;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plan-then-execute orchestration for multi-step goals. Instead of one long free-form run, the agent
 * first drafts a short plan, those steps become the session's todo list, and each is worked in turn
 * (checked off as it goes) before a final synthesis. This keeps small/local models on track.
 *
 * The text parsing and the step sequencing live here as PURE, dependency-free logic so they can be
 * unit-tested with a fake step runner; {@link AgentLoop#runPlan} supplies the real model-backed runner
 * and the todo sink.
 */
public final class Planner {

    private Planner() {}

    public static final int MAX_STEPS = 12;

    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\(?\\d+[.)\\]]\\s+(.*\\S)\\s*$");
    private static final Pattern STEP_KW = Pattern.compile("^\\s*step\\s*\\d+\\s*[:.)\\-]\\s*(.*\\S)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*\u2022]\\s+(.*\\S)\\s*$");

    /** Extract ordered steps from a model's plan text (numbered, "Step N:", or bulleted lines). */
    public static List<String> parsePlan(String text) {
        List<String> steps = new ArrayList<>();
        if (text == null) return steps;
        for (String raw : text.split("\\R")) {
            String step = matchStep(raw);
            if (step != null && !step.isBlank()) {
                steps.add(step.trim());
                if (steps.size() >= MAX_STEPS) break;
            }
        }
        return steps;
    }

    private static String matchStep(String line) {
        Matcher m = NUMBERED.matcher(line);
        if (m.matches()) return m.group(1);
        m = STEP_KW.matcher(line);
        if (m.matches()) return m.group(1);
        m = BULLET.matcher(line);
        if (m.matches()) return m.group(1);
        return null;
    }

    public static List<TodoStore.Item> toItems(List<String> steps) {
        List<TodoStore.Item> items = new ArrayList<>();
        for (String s : steps) items.add(new TodoStore.Item(s, "pending"));
        return items;
    }

    /** Index of the first not-completed item, or -1 if all are completed. */
    public static int nextPending(List<TodoStore.Item> items) {
        for (int i = 0; i < items.size(); i++) {
            if (!"completed".equals(items.get(i).status())) return i;
        }
        return -1;
    }

    public static List<TodoStore.Item> withStatus(List<TodoStore.Item> items, int idx, String status) {
        List<TodoStore.Item> copy = new ArrayList<>(items);
        if (idx >= 0 && idx < copy.size()) {
            copy.set(idx, new TodoStore.Item(copy.get(idx).content(), status));
        }
        return copy;
    }

    /**
     * Run each step in order, checking it off via {@code onTodos}, and return the aggregated per-step
     * results (the caller does the final synthesis). The {@code stepRunner} receives a ready-made
     * per-step prompt and returns that step's result. Pure: no model, no Spring -- unit-testable.
     */
    public static String execute(String goal, List<String> steps,
                                 Function<String, String> stepRunner,
                                 Consumer<List<TodoStore.Item>> onTodos) {
        List<TodoStore.Item> items = toItems(steps);
        onTodos.accept(items);
        StringBuilder results = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            items = withStatus(items, i, "in_progress");
            onTodos.accept(items);
            String result = stepRunner.apply(stepPrompt(goal, steps, i, results.toString()));
            results.append("Step ").append(i + 1).append(" — ").append(steps.get(i)).append(":\n")
                    .append(result == null ? "" : result.strip()).append("\n\n");
            items = withStatus(items, i, "completed");
            onTodos.accept(items);
        }
        return results.toString().strip();
    }

    // ---- prompts -----------------------------------------------------------

    public static final String PLAN_SYSTEM_PROMPT = """

            PLANNING MODE: produce a short numbered plan (3-7 concrete steps) to accomplish the goal.
            Output ONLY the numbered list, one step per line, no preamble and no tool calls.
            """;

    public static String planRequest(String goal) {
        return "Goal: " + goal + "\n\nWrite the numbered plan now.";
    }

    public static String stepPrompt(String goal, List<String> steps, int idx, String priorResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("Overall goal: ").append(goal).append("\n\n");
        sb.append("Full plan:\n");
        for (int i = 0; i < steps.size(); i++) {
            sb.append(i == idx ? "-> " : "   ").append(i + 1).append(". ").append(steps.get(i)).append("\n");
        }
        if (!priorResults.isBlank()) {
            sb.append("\nProgress so far:\n").append(priorResults.strip()).append("\n");
        }
        sb.append("\nDo ONLY step ").append(idx + 1).append(" now: ").append(steps.get(idx))
                .append("\nUse tools as needed, then briefly report what you did. Do not start later steps.");
        return sb.toString();
    }

    public static String synthesisPrompt(String goal, String allResults) {
        return "Overall goal: " + goal + "\n\nThe plan has been carried out. Step results:\n\n"
                + allResults.strip() + "\n\nNow give the user a concise final answer for the goal. "
                + "Do not call any more tools.";
    }
}
