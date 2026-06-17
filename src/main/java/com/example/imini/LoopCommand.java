package com.example.imini;

/**
 * Pure parsing + prompt-shaping for the {@code /loop} command: a bounded "iterate until green" runner.
 * The agent makes a focused change, runs a check, and repeats until the check passes or an attempt
 * budget is spent -- the agentic loop made first-class and, crucially, BOUNDED so it can never spin
 * forever. Syntax:
 *
 * <pre>/loop [check=&lt;command&gt;] [attempts=N] &lt;goal&gt;</pre>
 *
 * e.g. {@code /loop check=mvn -q test attempts=4 make UserServiceTest pass}. The check command is
 * optional (without it the goal runs once); attempts is clamped to a hard maximum.
 */
public final class LoopCommand {

    private LoopCommand() {}

    public record Spec(String goal, String check, int maxAttempts) {}

    public static boolean isLoop(String msg) {
        if (msg == null) return false;
        String t = msg.strip();
        return t.equals("/loop") || t.startsWith("/loop ");
    }

    /** Parse {@code /loop [check=...] [attempts=N] <goal>}; attempts clamped to [1, hardMax]. */
    public static Spec parse(String msg, int defaultAttempts, int hardMax) {
        String rest = msg == null ? "" : msg.strip();
        if (rest.startsWith("/loop")) rest = rest.substring(5).strip();
        String check = null;
        int attempts = defaultAttempts;

        // consume leading key=value tokens (check=..., attempts=...). check=... may be quoted.
        boolean consumed = true;
        while (consumed) {
            consumed = false;
            String lower = rest.toLowerCase();
            if (lower.startsWith("attempts=")) {
                int sp = indexOfWs(rest);
                String tok = sp < 0 ? rest : rest.substring(0, sp);
                try { attempts = Integer.parseInt(tok.substring("attempts=".length()).trim()); } catch (Exception ignore) {}
                rest = sp < 0 ? "" : rest.substring(sp + 1).strip();
                consumed = true;
            } else if (lower.startsWith("check=")) {
                String v = rest.substring("check=".length());
                if (v.startsWith("\"")) {                       // quoted command may contain spaces
                    int end = v.indexOf('"', 1);
                    check = end > 0 ? v.substring(1, end) : v.substring(1);
                    rest = end > 0 ? v.substring(end + 1).strip() : "";
                } else {
                    int sp = indexOfWs(v);
                    check = sp < 0 ? v : v.substring(0, sp);
                    rest = sp < 0 ? "" : v.substring(sp + 1).strip();
                }
                if (check != null && check.isBlank()) check = null;
                consumed = true;
            }
        }
        int clamped = Math.max(1, Math.min(hardMax, attempts));
        return new Spec(rest.strip(), check, clamped);
    }

    /** The prompt for a given attempt: the goal, plus the last check failure to fix on retries. */
    public static String nextPrompt(String goal, int attempt, String lastFailure) {
        if (attempt <= 1 || lastFailure == null || lastFailure.isBlank()) {
            return goal;
        }
        return goal + "\n\nThe verification check is still failing (attempt " + attempt + "). Latest output:\n"
                + lastFailure + "\n\nMake the smallest change that fixes this, then it will be re-checked.";
    }

    /** Continue looping while we have a check, it has not passed, and attempts remain. */
    public static boolean shouldContinue(int attempt, int maxAttempts, boolean passed, boolean hasCheck) {
        if (!hasCheck) return false;     // nothing to iterate against -> single pass
        if (passed) return false;
        return attempt < maxAttempts;
    }

    private static int indexOfWs(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return i;
        return -1;
    }
}
