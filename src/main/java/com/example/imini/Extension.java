package com.example.imini;

import java.util.List;

/**
 * The in-process extension point: a small user application that plugs into the harness without forking
 * core Java. Implement this interface on a Spring {@code @Component} in the {@code com.example.imini}
 * component-scan path (drop a file in and rebuild) and the harness discovers it via {@link
 * ExtensionRegistry} — no wiring, no restart-of-anything-else.
 *
 * <p>Every method has an empty default, so an extension implements only what it needs. Contributions
 * are collected once and cached, each call is isolated (a throwing extension is logged and skipped, the
 * harness keeps running), and everything an extension contributes goes through the SAME guardrails as a
 * built-in: contributed tools are schema-validated and permission-gated exactly like {@code write_file}.
 *
 * <p>What an extension can contribute today:
 * <ul>
 *   <li>{@link #tools(ExtensionContext)} — in-process {@link Tool}s the model can call (the thing MCP
 *       and shell hooks cannot do: a validated, permission-gated tool that runs in-process and can see
 *       harness state). This is the load-bearing extension point.
 *   <li>{@link #agents(ExtensionContext)} — named, tool-scoped subagents (same shape as {@code
 *       agents/<name>.md}, but built in code).
 *   <li>{@link #commands(ExtensionContext)} — slash-command templates (same shape as {@code
 *       commands/<name>.md}).
 *   <li>{@link #onEvent(LoopEvent, ExtensionContext)} — observe loop lifecycle events (the typed,
 *       in-process successor to shell hooks). Observe-only; it cannot block a tool.
 * </ul>
 *
 * <p>Because an extension is itself a Spring bean, it can inject any harness service it needs through
 * its own constructor — so {@link ExtensionContext} stays deliberately small.
 *
 * <p>See {@code docs/EXTENDING_GETTING_STARTED.md} and the {@code examples/} directory for runnable
 * samples of each use case.
 */
public interface Extension {

    /** A stable, human-readable name for this extension (shown in {@code GET /admin/extensions}). */
    default String name() {
        return getClass().getSimpleName();
    }

    /** In-process tools the model may call. Validated + permission-gated like any built-in tool. */
    default List<Tool> tools(ExtensionContext ctx) {
        return List.of();
    }

    /** Named, tool-scoped subagents (delegated to with {@code /agent <name>}). */
    default List<AgentLibrary.AgentDef> agents(ExtensionContext ctx) {
        return List.of();
    }

    /** Slash-command templates ({@code $ARGS}/{@code $ARGUMENTS} is replaced by the text after the name). */
    default List<Command> commands(ExtensionContext ctx) {
        return List.of();
    }

    /**
     * Observe a loop lifecycle event (tool use, etc.). Observe-only: unlike a {@code preToolUse} shell
     * hook this cannot block a tool — it is for metrics, logging, and side-channel reactions. Keep it
     * fast and exception-safe; a throw is caught and logged, not propagated into the run.
     */
    default void onEvent(LoopEvent event, ExtensionContext ctx) {
    }

    /** A slash command contributed in code: {@code /name args} expands to {@code template}. */
    record Command(String name, String description, String template) {
    }
}
