package com.example.imini;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, dependency-free unified-diff rendering for patch previews. Trims the common leading/trailing
 * lines and shows the changed middle as a single hunk ({@code -old} / {@code +new}), which matches the
 * shape of the small, targeted edits {@code apply_patch} produces. Good enough to review a change before
 * applying it; not a full LCS diff.
 */
public final class DiffRender {

    private DiffRender() {}

    /** A rendered per-file diff plus counts, for previews. */
    public record FileDiff(String path, String kind, int added, int removed, String diff) {}

    /** Render a single-hunk unified diff for one file (kind: create | modify | unchanged). */
    public static FileDiff unified(String path, String oldText, String newText) {
        if (oldText == null) {
            List<String> add = lines(newText);
            String body = header(path, true) + body(add, '+');
            return new FileDiff(path, "create", add.size(), 0, body);
        }
        if (oldText.equals(newText)) {
            return new FileDiff(path, "unchanged", 0, 0, "(" + path + ": no change)");
        }
        List<String> o = lines(oldText), n = lines(newText);
        int pre = 0;
        while (pre < o.size() && pre < n.size() && o.get(pre).equals(n.get(pre))) pre++;
        int suf = 0;
        while (suf < (o.size() - pre) && suf < (n.size() - pre)
                && o.get(o.size() - 1 - suf).equals(n.get(n.size() - 1 - suf))) suf++;

        List<String> removed = o.subList(pre, o.size() - suf);
        List<String> added = n.subList(pre, n.size() - suf);

        StringBuilder sb = new StringBuilder(header(path, false));
        sb.append("@@ -").append(pre + 1).append(",").append(removed.size())
          .append(" +").append(pre + 1).append(",").append(added.size()).append(" @@\n");
        if (pre > 0) sb.append("  ").append(o.get(pre - 1)).append("\n"); // one line of context
        for (String l : removed) sb.append("- ").append(l).append("\n");
        for (String l : added) sb.append("+ ").append(l).append("\n");
        if (suf > 0) sb.append("  ").append(o.get(o.size() - suf)).append("\n");
        return new FileDiff(path, "modify", added.size(), removed.size(), sb.toString());
    }

    private static String header(String path, boolean create) {
        return (create ? "+++ " : "--- ") + path + (create ? " (new file)" : "") + "\n";
    }

    private static String body(List<String> lines, char sign) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(sign).append(" ").append(l).append("\n");
        return sb.toString();
    }

    private static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String l : s.split("\n", -1)) out.add(l);
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1); // trailing newline
        return out;
    }

    /** A short one-line summary across several file diffs. Pure. */
    public static String summary(List<FileDiff> diffs) {
        int files = 0, add = 0, rem = 0;
        for (FileDiff d : diffs) {
            if ("unchanged".equals(d.kind())) continue;
            files++;
            add += d.added();
            rem += d.removed();
        }
        return files + " file(s), +" + add + " -" + rem;
    }
}
