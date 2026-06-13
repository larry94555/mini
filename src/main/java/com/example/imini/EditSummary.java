package com.example.imini;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds an "edit trust" block from git output: which files changed and a diff stat, so a coding run's
 * final answer states what was actually modified (verification the model cannot fake). All parsing is
 * pure and dependency-free; {@link GitInspector} supplies the raw git output.
 */
public final class EditSummary {

    private EditSummary() {}

    private static final int MAX_FILES = 25;

    public record FileChange(String code, String path) {}

    /** Parse {@code git status --porcelain} into (code, path) entries (ignores the branch header). */
    public static List<FileChange> parseStatus(String porcelain) {
        List<FileChange> out = new ArrayList<>();
        if (porcelain == null) return out;
        for (String line : porcelain.split("\\R")) {
            if (line.isBlank() || line.startsWith("##") || line.startsWith("ERROR")) continue;
            if (line.length() < 4) continue;
            String code = line.substring(0, 2).trim();
            String path = line.substring(3).trim();
            if (!path.isBlank()) out.add(new FileChange(code.isBlank() ? "?" : code, path));
        }
        return out;
    }

    /** The summary line of {@code git diff --stat} (e.g. "3 files changed, 10 insertions(+)"), or "". */
    public static String parseStat(String diffStat) {
        if (diffStat == null) return "";
        String last = "";
        for (String line : diffStat.split("\\R")) {
            String t = line.strip();
            if (t.contains("changed") && t.contains("file")) last = t;
        }
        return last;
    }

    /** A one-line summary for logs. */
    public static String oneLine(String statusPorcelain, String diffStat) {
        String stat = parseStat(diffStat);
        if (!stat.isBlank()) return stat;
        int n = parseStatus(statusPorcelain).size();
        return n == 0 ? "no changes" : n + " file(s) changed";
    }

    /**
     * The block appended to a final answer. Empty when nothing changed. Prefers git's view of changed
     * files; falls back to the paths this run touched when git can't see them (no repo / untracked).
     */
    public static String format(String statusPorcelain, String diffStat, Collection<String> runPaths) {
        List<FileChange> changes = parseStatus(statusPorcelain);
        String stat = parseStat(diffStat);
        boolean noRunPaths = (runPaths == null || runPaths.isEmpty());
        if (changes.isEmpty() && noRunPaths && stat.isBlank()) return "";

        StringBuilder sb = new StringBuilder("---\nEdits (verified with git):\n");
        if (!changes.isEmpty()) {
            List<FileChange> shown = changes.size() > MAX_FILES ? changes.subList(0, MAX_FILES) : changes;
            sb.append("- changed files: ")
                    .append(shown.stream().map(c -> c.path() + " (" + c.code() + ")")
                            .collect(Collectors.joining(", ")));
            if (changes.size() > MAX_FILES) sb.append(", … (+").append(changes.size() - MAX_FILES).append(" more)");
            sb.append("\n");
        } else if (!noRunPaths) {
            sb.append("- files this run touched: ").append(String.join(", ", runPaths)).append("\n");
        }
        if (!stat.isBlank()) {
            sb.append("- git diff --stat: ").append(stat).append("\n");
        } else {
            sb.append("- (no tracked diff — new/untracked files, or the workspace is not a git repo)\n");
        }
        return sb.toString().strip();
    }
}
