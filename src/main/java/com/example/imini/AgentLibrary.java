package com.example.imini;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure parsing/formatting for custom subagents -- named, tool-scoped helpers the main agent can delegate
 * a task to (each runs in its own isolated loop; only its final answer returns). An agent is an
 * {@code agents/<name>.md} with optional front-matter (name, description, tools, model) and a body that
 * is the agent's system prompt. {@link AgentRegistry} supplies the catalog + filesystem; this class is
 * dependency-free and unit-testable.
 */
public final class AgentLibrary {

    private AgentLibrary() {}

    /** A subagent definition: its name, one-line description, allowed tool names, model profile, prompt. */
    public record AgentDef(String name, String description, List<String> tools, String model, String body) {}

    /** A parsed {@code /agent <name> <task>} invocation. */
    public record Invocation(String name, String task) {}

    /** Parse an {@code agents/<name>.md}: optional `---` front-matter, then the body (system prompt). */
    public static AgentDef parse(String text, String fallbackName) {
        if (text == null) return new AgentDef(fallbackName, "", List.of(), "", "");
        String t = text.replace("\r\n", "\n");
        String name = fallbackName, desc = "", model = "", body = t.strip();
        List<String> tools = List.of();
        if (t.stripLeading().startsWith("---")) {
            int first = t.indexOf("---");
            int second = t.indexOf("\n---", first + 3);
            if (second > first) {
                String fm = t.substring(first + 3, second);
                body = t.substring(second + 4).strip();
                for (String line : fm.split("\n")) {
                    String l = line.strip();
                    String lower = l.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("name:")) name = l.substring(5).strip();
                    else if (lower.startsWith("description:")) desc = l.substring(12).strip();
                    else if (lower.startsWith("model:")) model = l.substring(6).strip();
                    else if (lower.startsWith("tools:")) tools = SkillLibrary.parseList(l.substring(6));
                }
            }
        }
        if (name == null || name.isBlank()) name = fallbackName;
        return new AgentDef(name, desc == null ? "" : desc, tools, model == null ? "" : model,
                body == null ? "" : body);
    }

    /** {@code /agents} listing: name, description, and tool scope. Pure. */
    public static String renderList(List<AgentDef> agents) {
        if (agents == null || agents.isEmpty()) {
            return "No subagents available. Add agents/<name>.md, or use the built-ins.";
        }
        StringBuilder sb = new StringBuilder("Available subagents (delegate with /agent <name> <task>):\n");
        for (AgentDef a : agents) {
            sb.append("  ").append(a.name());
            if (!a.description().isBlank()) sb.append(" - ").append(a.description());
            if (a.tools() != null && !a.tools().isEmpty()) sb.append("  [tools: ").append(String.join(", ", a.tools())).append("]");
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /** Parse {@code /agent <name> <task>}; null if not that command or missing a name. */
    public static Invocation parseCommand(String msg) {
        if (msg == null) return null;
        String t = msg.strip();
        if (!t.equals("/agent") && !t.startsWith("/agent ")) return null;
        String rest = t.length() > 6 ? t.substring(6).strip() : "";
        int sp = indexOfWhitespace(rest);
        String name = sp < 0 ? rest : rest.substring(0, sp);
        String task = sp < 0 ? "" : rest.substring(sp + 1).strip();
        if (name.isEmpty()) return null;
        return new Invocation(name, task);
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return i;
        return -1;
    }

    /** Merge built-ins with on-disk agents (disk overrides built-in by name, case-insensitive). Pure. */
    public static List<AgentDef> merge(List<AgentDef> builtins, List<AgentDef> onDisk) {
        java.util.Map<String, AgentDef> byName = new java.util.LinkedHashMap<>();
        if (builtins != null) for (AgentDef a : builtins) byName.put(a.name().toLowerCase(Locale.ROOT), a);
        if (onDisk != null) for (AgentDef a : onDisk) byName.put(a.name().toLowerCase(Locale.ROOT), a); // disk wins
        return new ArrayList<>(byName.values());
    }
}
