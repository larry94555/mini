---
name: debug
description: Diagnose a bug methodically, find the root cause, propose a minimal fix, and verify it.
---
Debug the problem described by the user (paste an error, a failing test, or a symptom): $ARGUMENTS

Work the problem in order; do not jump straight to a fix:

1. Restate the expected vs actual behaviour in one sentence each.
2. Reproduce it. Identify the smallest command or test that triggers the bug (use the check/test tools).
3. Localize. Use `grep` / `read_file` / `git_blame` to find the code on the failing path; read the
   exact lines, do not guess.
4. Form a single hypothesis for the root cause and state it explicitly.
5. Make the smallest change that addresses the root cause (not the symptom).
6. Verify by re-running the reproduction; confirm it passes and that you did not break neighbours.
7. Summarize: root cause, the fix, and how you verified it.

If a hypothesis is disproven, say so and form the next one rather than stacking speculative changes.
