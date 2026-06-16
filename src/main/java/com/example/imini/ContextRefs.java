package com.example.imini;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helpers for {@code @file} / {@code @directory} prompt references, like Claude Code's. When a user
 * message mentions {@code @path}, the harness inlines that file's content (or a directory's listing)
 * into what the model sees. Parsing and block assembly live here so they are deterministic and testable;
 * {@link ContextRefService} supplies the workspace-confined, size-capped filesystem resolution.
 *
 * <p>A reference is an {@code @} (at the start or after whitespace) followed by a path-like token. Tokens
 * that don't resolve to a real file/dir in the workspace are left untouched, so ordinary {@code @name}
 * mentions are not mangled. {@code @@} is treated as an escape (not a reference).
 */
public final class ContextRefs {

    private ContextRefs() {}

    // @ at start-or-after-whitespace, then a path-ish run (letters, digits, / . _ -)
    private static final Pattern REF = Pattern.compile("(?:^|\\s)@([A-Za-z0-9_./\\-]+)");

    /** A resolved reference ready to render: a file's content or a directory's listing. */
    public record Resolved(String ref, String kind, String body, int bytes, int entries) {}

    /** Candidate reference paths in a message, de-duplicated, order-preserving, trailing punctuation trimmed. */
    public static List<String> parse(String text) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (text == null) return out;
        Matcher m = REF.matcher(text);
        while (m.find()) {
            String tok = trimTrailing(m.group(1));
            if (!tok.isEmpty() && seen.add(tok)) out.add(tok);
        }
        return out;
    }

    private static String trimTrailing(String s) {
        int end = s.length();
        while (end > 0 && ".,;:)".indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(0, end);
    }

    /** Assemble the {@code <referenced-context>} block appended to the user message. Pure. */
    public static String block(List<Resolved> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n<referenced-context>\n");
        for (Resolved r : items) {
            if ("dir".equals(r.kind())) {
                sb.append("--- @").append(r.ref()).append(" (directory, ").append(r.entries())
                  .append(" entr").append(r.entries() == 1 ? "y" : "ies").append(") ---\n");
            } else {
                sb.append("--- @").append(r.ref()).append(" (file, ").append(r.bytes())
                  .append(" bytes) ---\n");
            }
            sb.append(r.body());
            if (!r.body().endsWith("\n")) sb.append("\n");
        }
        sb.append("</referenced-context>\n");
        return sb.toString();
    }
}
