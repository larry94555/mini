---
name: loop
description: Iterate make-a-change / run-a-check until the goal is met or a bounded number of attempts is spent.
---
Iterate toward a goal with a clear stop condition: $ARGUMENTS

Run a bounded improve-and-check loop:

1. State the goal and the exact success check (e.g. "all tests pass", "no lint errors", "output matches").
2. Run the check once to get a baseline; capture what is failing.
3. If it already passes, stop and report success.
4. Otherwise make ONE focused change aimed at the most informative failure, then re-run the check.
5. Repeat step 4, but stop after a small fixed number of attempts (default 5) even if not yet green.
6. Report each iteration briefly (what you changed, what the check said) and the final state. If you hit
   the attempt budget without success, summarize what is still failing and the most likely next step.

Never loop unbounded. One change per iteration so cause and effect stay clear; if a change makes things
worse, revert it before trying the next idea.
