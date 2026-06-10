package com.example.imini;

/**
 * Optional, profile-specific guidance appended to the system prompt. The base prompt stays general;
 * setting {@code agent.profile=coding} adds an explicit "how to work on a codebase" workflow that
 * names the deterministic navigation tools and the recommended order. Small local models follow a
 * concrete, numbered loop much better than they infer one, so this measurably improves coding tasks.
 *
 * Pure and static so it is trivially unit-testable; {@code general} (the default) adds nothing.
 */
public final class AgentProfile {

    private AgentProfile() {}

    static final String CODING = """


            Coding workflow -- you have deterministic codebase tools; use them instead of guessing:
            1. ORIENT: call repo_tree to see the project layout before diving in.
            2. LOCATE: use glob to find files by name (e.g. "**/*.java") and grep to find where a
               symbol is defined or used. Search before you read; never invent file paths.
            3. READ: use view (line-numbered) to read a file before editing it; use read_many to read
               several related files together.
            4. EDIT: prefer edit_file (an exact, unique snippet replacement) over write_file; keep
               changes small and targeted. Rewind exists as a safety net, but aim to get the edit right.
            5. VERIFY: after changing files, use git_status and git_diff to review exactly what you
               changed, and run the project's build or tests via run_command when available, before you
               report that you are done.
            For exact code lookups prefer grep/glob over search_memory (search_memory is fuzzy keyword
            retrieval, better for "where is the config-ish stuff" than for finding precise call sites).
            """;

    /** Guidance for the given profile name; empty string for "general"/unknown/null. */
    public static String guidance(String profile) {
        String p = profile == null ? "" : profile.trim();
        return "coding".equalsIgnoreCase(p) ? CODING : "";
    }
}
