package com.example.imini;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helpers for LSP-style "find references". {@code find_symbol} already locates DECLARATIONS;
 * this adds USAGE matching: whole-identifier occurrences of a name (so {@code user} does not match
 * {@code username} or {@code user_id}), with the declaration site flagged. Heuristic and regex-based --
 * it sees identifiers, not scopes -- so it can over-match a name reused in unrelated files; good enough
 * to jump around a small codebase, not a typed resolver. The filesystem walk lives in
 * {@link CodebaseTools}; everything here is deterministic and testable.
 */
public final class SymbolRefs {

    private SymbolRefs() {}

    /** One reference (usage or declaration) of a symbol. */
    public record Ref(String path, int line, boolean def, String text) {}

    /** A whole-identifier matcher for {@code name} (boundaries = anything not a letter/digit/_/$). */
    public static Pattern referencePattern(String name) {
        return Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(name) + "(?![A-Za-z0-9_$])");
    }

    /** True if {@code line} contains {@code name} as a whole identifier. */
    public static boolean references(String line, String name) {
        if (line == null || name == null || name.isEmpty()) return false;
        return referencePattern(name).matcher(line).find();
    }

    /** Number of whole-identifier occurrences of {@code name} in {@code line}. */
    public static int count(String line, String name) {
        if (line == null || name == null || name.isEmpty()) return 0;
        Matcher m = referencePattern(name).matcher(line);
        int c = 0;
        while (m.find()) c++;
        return c;
    }

    /** Render references grouped, marking declaration sites with {@code [def]}. Pure. */
    public static String render(List<Ref> refs, String name, int cap, boolean truncated) {
        if (refs == null || refs.isEmpty()) return "(no references to '" + name + "' found)";
        long defs = refs.stream().filter(Ref::def).count();
        StringBuilder sb = new StringBuilder();
        sb.append(refs.size()).append(" reference(s) to '").append(name).append("'");
        if (defs > 0) sb.append(" (").append(defs).append(" declaration").append(defs == 1 ? "" : "s").append(")");
        sb.append(":\n");
        for (Ref r : refs) {
            sb.append(r.path()).append(":").append(r.line()).append(": ")
              .append(r.def() ? "[def] " : "").append(r.text()).append("\n");
        }
        if (truncated) sb.append("...[stopped at ").append(cap).append(" matches]\n");
        return sb.toString().stripTrailing();
    }
}
