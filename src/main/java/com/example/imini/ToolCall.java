package com.example.imini;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One recorded tool invocation in a run. Pure helpers turn a tool name + arguments into a short,
 * human-readable summary and classify the result as ok/error, so the audit log and the per-step
 * transcript can show WHAT the agent did (not just that a run happened).
 */
public record ToolCall(long ts, String tool, String summary, String outcome) {

    /** Render as a one-line transcript entry, e.g. {@code write_file src/App.java [ok]}. */
    public String line() {
        return tool + (summary == null || summary.isBlank() ? "" : " " + summary) + " [" + outcome + "]";
    }

    /** A concise, safe summary of the tool's arguments (the bit worth recording). */
    public static String summarize(String tool, Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";
        switch (tool == null ? "" : tool) {
            case "write_file":
            case "edit_file":
            case "read_file":
            case "view":
                return str(args.get("path"));
            case "apply_patch":
                return args.containsKey("path") ? str(args.get("path")) : "patch";
            case "run_command":
                return "$ " + truncate(str(args.get("command")), 120);
            case "todo_write":
                return "todos";
            default:
                return truncate(args.toString(), 120);
        }
    }

    /** Classify a tool result string as {@code error} (our error conventions) or {@code ok}. */
    public static String outcome(String result) {
        if (result == null) return "ok";
        String r = result.stripLeading().toUpperCase(Locale.ROOT);
        if (r.startsWith("ERROR") || r.startsWith("DENIED") || r.startsWith("INVALID_ARGS")
                || r.startsWith("BLOCKED")) return "error";
        return "ok";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
