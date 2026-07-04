package com.example.imini;

import java.util.Map;

/**
 * A typed agent-loop lifecycle event delivered to {@link Extension#onEvent}. The in-process, typed
 * successor to the shell {@code hooks.json} events — but observe-only (it cannot block a tool).
 *
 * <p>Two event types are emitted today, both from the tool-dispatch path in {@link AgentEngine}:
 * <ul>
 *   <li>{@link Type#PRE_TOOL_USE} — just before a tool executes ({@code result} is null).
 *   <li>{@link Type#POST_TOOL_USE} — just after it returns ({@code result} carries the tool output).
 * </ul>
 * More event types (session start, turn stop) are a straightforward follow-up; the enum is kept small
 * so it only advertises what is actually emitted.
 */
public record LoopEvent(Type type, String sessionId, String tool, Map<String, Object> args, String result) {

    public enum Type {
        PRE_TOOL_USE,
        POST_TOOL_USE
    }

    /** A PRE_TOOL_USE event (no result yet). */
    public static LoopEvent preTool(String sessionId, String tool, Map<String, Object> args) {
        return new LoopEvent(Type.PRE_TOOL_USE, sessionId, tool, args, null);
    }

    /** A POST_TOOL_USE event carrying the tool's result. */
    public static LoopEvent postTool(String sessionId, String tool, Map<String, Object> args, String result) {
        return new LoopEvent(Type.POST_TOOL_USE, sessionId, tool, args, result);
    }
}
