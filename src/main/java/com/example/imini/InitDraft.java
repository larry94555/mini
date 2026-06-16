package com.example.imini;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure rendering for {@code /init}: turn {@link RepoScan.Facts} into a {@code CLAUDE.md} draft, and
 * compare an existing file's section headings against the draft's so {@code /init} can suggest what is
 * missing without overwriting user content.
 */
public final class InitDraft {

    private InitDraft() {}

    /** Render a deterministic CLAUDE.md scaffold from the scan (no model call). */
    public static String render(String projectName, RepoScan.Facts f) {
        String name = (projectName == null || projectName.isBlank()) ? "Project" : projectName;
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(name).append("\n\n");
        sb.append("> Scaffolded by `imini /init` from a repository scan. Edit freely -- this file is your\n");
        sb.append("> project memory and is loaded into the agent's system prompt (see `/memory`).\n\n");

        sb.append("## Project overview\n\n");
        sb.append("- Build system: ").append(f.buildSystem()).append("\n");
        sb.append("- Primary languages: ")
          .append(f.languages().isEmpty() ? "(none detected)" : String.join(", ", f.languages())).append("\n");
        sb.append("- Files scanned: ").append(f.fileCount()).append("\n\n");

        sb.append("## Build and test\n\n");
        sb.append("- Build: `").append(f.buildCmd()).append("`\n");
        sb.append("- Test: `").append(f.testCmd()).append("`\n");
        if (!f.buildFiles().isEmpty()) {
            sb.append("- Build files: ").append(String.join(", ", f.buildFiles())).append("\n");
        }
        sb.append("\n");

        sb.append("## Layout\n\n");
        if (f.keyDirs().isEmpty()) {
            sb.append("- (Describe the important directories.)\n");
        } else {
            for (String d : f.keyDirs()) sb.append("- `").append(d).append("`\n");
        }
        sb.append("\n");

        sb.append("## Conventions\n\n");
        sb.append("- (Describe code style, naming, and patterns the agent should follow.)\n\n");

        sb.append("## Notes for the agent\n\n");
        sb.append("- (Anything the agent should always keep in mind: gotchas, do-nots, preferred libraries.)\n");
        return sb.toString();
    }

    /** The {@code ## } section headings (text after the marker), in order. Pure. */
    public static List<String> headings(String md) {
        List<String> out = new ArrayList<>();
        if (md == null) return out;
        for (String line : md.split("\n")) {
            String t = line.strip();
            if (t.startsWith("## ")) out.add(t.substring(3).strip());
        }
        return out;
    }

    /** Draft headings not already present (case-insensitive) in the existing file. Pure. */
    public static List<String> missingSections(String existing, String draft) {
        List<String> have = new ArrayList<>();
        for (String h : headings(existing)) have.add(h.toLowerCase());
        List<String> missing = new ArrayList<>();
        for (String h : headings(draft)) if (!have.contains(h.toLowerCase())) missing.add(h);
        return missing;
    }

    /**
     * Split a markdown document into {@code ## } section blocks, keyed by heading text (in order). Each
     * block is the {@code ## heading} line through to just before the next {@code ## } (or end). Content
     * before the first {@code ## } (the title/preamble) is not returned. Pure.
     */
    public static java.util.LinkedHashMap<String, String> sectionBlocks(String md) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        if (md == null) return out;
        String[] lines = md.split("\n", -1);
        String current = null;
        StringBuilder block = new StringBuilder();
        for (String line : lines) {
            String t = line.strip();
            if (t.startsWith("## ")) {
                if (current != null) out.put(current, block.toString());
                current = t.substring(3).strip();
                block = new StringBuilder(line).append("\n");
            } else if (current != null) {
                block.append(line).append("\n");
            }
        }
        if (current != null) out.put(current, block.toString());
        return out;
    }

    /**
     * Improve an existing CLAUDE.md WITHOUT replacing user content: append only the draft sections whose
     * headings are missing (case-insensitive), under a clear marker. Returns the merged text, or the
     * original unchanged when nothing is missing. Pure -- the actual write lives in {@link InitService}.
     */
    public static String augment(String existing, String draft) {
        if (existing == null) return draft;
        List<String> missing = missingSections(existing, draft);
        if (missing.isEmpty()) return existing;
        java.util.LinkedHashMap<String, String> draftBlocks = sectionBlocks(draft);
        StringBuilder sb = new StringBuilder(existing);
        if (!existing.endsWith("\n")) sb.append("\n");
        sb.append("\n<!-- Added by `imini /init`: sections missing from this file. Edit freely. -->\n\n");
        for (String h : missing) {
            String block = draftBlocks.get(h);
            if (block != null) sb.append(block).append("\n");
        }
        return sb.toString().stripTrailing() + "\n";
    }
}
