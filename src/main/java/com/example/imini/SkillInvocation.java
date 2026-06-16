package com.example.imini;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure helpers for Claude-like skill UX: parsing a {@code /<skill-name> [args]} invocation, substituting
 * {@code $ARGUMENTS} in a skill body, and rendering the {@code /skills} listing. {@link SkillService}
 * supplies the actual skill catalog and enabled-state; everything here is deterministic and testable.
 */
public final class SkillInvocation {

    private SkillInvocation() {}

    /** Slash names that are built-in commands, never treated as skill invocations. */
    public static final Set<String> RESERVED = Set.of(
            "help", "commands", "memory", "init", "skills");

    /** A parsed slash invocation: the name after {@code /} and the remaining argument text. */
    public record Parsed(String name, String args) {}

    /** Parse {@code /<name> [args]}; null if the message is not a single-token slash command. */
    public static Parsed parse(String msg) {
        if (msg == null) return null;
        String t = msg.strip();
        if (t.length() < 2 || t.charAt(0) != '/') return null;
        String rest = t.substring(1);
        int sp = indexOfWhitespace(rest);
        String name = sp < 0 ? rest : rest.substring(0, sp);
        String args = sp < 0 ? "" : rest.substring(sp + 1).strip();
        if (name.isEmpty()) return null;
        return new Parsed(name, args);
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return i;
        return -1;
    }

    public static boolean isReserved(String name) {
        return name != null && RESERVED.contains(name.toLowerCase());
    }

    /** Replace {@code $ARGUMENTS} / {@code $ARGS} in a skill body with the invocation arguments. Pure. */
    public static String substitute(String body, String args) {
        String a = args == null ? "" : args;
        String out = (body == null ? "" : body).replace("$ARGUMENTS", a).replace("$ARGS", a);
        // If the body declares no placeholder but args were given, append them so they aren't lost.
        if (!a.isEmpty() && body != null && !body.contains("$ARGUMENTS") && !body.contains("$ARGS")) {
            out = out + "\n\nArguments: " + a;
        }
        return out;
    }

    /** Render the {@code /skills} listing from {@link SkillService#listForUi}. Pure. */
    public static String renderList(List<Map<String, Object>> skills) {
        if (skills == null || skills.isEmpty()) {
            return "No skills available. Add a SKILL.md under the skills directory, or propose one.";
        }
        StringBuilder sb = new StringBuilder("Available skills (invoke with /<name> [args]):\n");
        for (Map<String, Object> s : skills) {
            boolean enabled = Boolean.TRUE.equals(s.get("enabled"));
            sb.append(enabled ? "  /" : "  (disabled) /").append(s.get("name"));
            Object d = s.get("description");
            if (d != null && !String.valueOf(d).isBlank()) sb.append(" - ").append(d);
            sb.append("\n");
        }
        sb.append("\nLoad a skill's full instructions by invoking it, or ask and let auto-load pick one.");
        return sb.toString().stripTrailing();
    }
}
