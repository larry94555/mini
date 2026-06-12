package com.example.imini;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private static final Pattern STATUS_LINE = Pattern.compile("(?im)^\\s*STEP_STATUS\\s*[:=]\\s*(\\w+)");

    /** Outcome of a single executed step. */
    public enum StepOutcome { DONE, FAILED }

    /**
     * Classify a step's free-text result. An explicit trailing {@code STEP_STATUS: done|failed} line
     * (which the step prompt asks for) wins; otherwise a result that starts with our tools' ERROR
     * convention is a failure, and anything else is optimistically treated as done.
     */
    public static StepOutcome classify(String result) {
        if (result == null) return StepOutcome.FAILED;
        Matcher m = STATUS_LINE.matcher(result);
        String last = null;
        while (m.find()) last = m.group(1).toLowerCase(Locale.ROOT);
        if (last != null) {
            if (last.startsWith("done") || last.startsWith("ok") || last.startsWith("success")
                    || last.startsWith("complete")) return StepOutcome.DONE;
            if (last.startsWith("fail") || last.startsWith("error") || last.startsWith("block")
                    || last.startsWith("no")) return StepOutcome.FAILED;
        }
        if (result.strip().toUpperCase(Locale.ROOT).startsWith("ERROR")) return StepOutcome.FAILED;
        return StepOutcome.DONE;
    }

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

    /**
     * Outcome-aware execution: each step may be retried (up to {@code maxRetriesPerStep}); a step that
     * still fails is marked {@code failed}, and -- up to {@code maxReplans} times total -- the model is
     * asked (via {@code replanner}) to revise the REMAINING work, whose steps are appended and run.
     * Pure and dependency-free so it is unit-testable with fake runners.
     */
    public static String executeWithRecovery(String goal, List<String> initialSteps,
                                             Function<String, String> stepRunner,
                                             Function<String, List<String>> replanner,
                                             Consumer<List<TodoStore.Item>> onTodos,
                                             int maxRetriesPerStep, int maxReplans) {
        List<String> steps = new ArrayList<>(initialSteps);
        List<TodoStore.Item> items = toItems(steps);
        onTodos.accept(items);
        StringBuilder results = new StringBuilder();
        int replans = 0;

        for (int i = 0; i < steps.size(); i++) {
            items = withStatus(items, i, "in_progress");
            onTodos.accept(items);

            String result = "";
            String lastFailure = null;
            StepOutcome outcome = StepOutcome.FAILED;
            for (int attempt = 0; attempt <= Math.max(0, maxRetriesPerStep); attempt++) {
                result = stepRunner.apply(stepPrompt(goal, steps, i, results.toString(), attempt, lastFailure));
                outcome = classify(result);
                if (outcome == StepOutcome.DONE) break;
                lastFailure = result;
            }

            results.append("Step ").append(i + 1).append(" — ").append(steps.get(i))
                    .append(outcome == StepOutcome.DONE ? " [done]:\n" : " [failed]:\n")
                    .append(result == null ? "" : result.strip()).append("\n\n");

            if (outcome == StepOutcome.DONE) {
                items = withStatus(items, i, "completed");
                onTodos.accept(items);
                continue;
            }

            // step failed after retries
            items = withStatus(items, i, "failed");
            onTodos.accept(items);
            if (replans < maxReplans && i + 1 < MAX_STEPS) {
                replans++;
                List<String> revised = replanner.apply(replanPrompt(goal, results.toString(), steps.get(i)));
                if (revised != null && !revised.isEmpty()) {
                    int room = MAX_STEPS - (i + 1);
                    List<String> tail = revised.subList(0, Math.min(revised.size(), room));
                    List<String> head = new ArrayList<>(steps.subList(0, i + 1));
                    head.addAll(tail);
                    List<TodoStore.Item> rebuilt = new ArrayList<>(items.subList(0, i + 1));
                    for (String rs : tail) rebuilt.add(new TodoStore.Item(rs, "pending"));
                    steps = head;
                    items = rebuilt;
                    onTodos.accept(items);
                }
            }
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

    /** Step prompt for the recovery-aware executor: adds a required STEP_STATUS line and a retry note. */
    public static String stepPrompt(String goal, List<String> steps, int idx, String priorResults,
                                    int attempt, String lastFailure) {
        StringBuilder sb = new StringBuilder(stepPrompt(goal, steps, idx, priorResults));
        if (attempt > 0 && lastFailure != null) {
            sb.append("\n\nThe previous attempt did not succeed:\n").append(lastFailure.strip())
                    .append("\nTry a different approach.");
        }
        sb.append("\n\nEnd your report with a line \"STEP_STATUS: done\" if the step succeeded, or "
                + "\"STEP_STATUS: failed <brief reason>\" if it could not be completed.");
        return sb.toString();
    }

    public static String replanPrompt(String goal, String progress, String failedStep) {
        return "Overall goal: " + goal + "\n\nProgress so far:\n" + progress.strip()
                + "\n\nThe step \"" + failedStep + "\" failed. Write a revised numbered plan for the "
                + "REMAINING work only (do not repeat completed steps). Output ONLY the numbered list.";
    }

    public static String synthesisPrompt(String goal, String allResults) {
        return "Overall goal: " + goal + "\n\nThe plan has been carried out. Step results:\n\n"
                + allResults.strip() + "\n\nNow give the user a concise final answer for the goal. "
                + "Do not call any more tools.";
    }
}
