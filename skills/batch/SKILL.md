---
name: batch
description: Apply the same change consistently across many files or items, verifying each one.
---
Apply a repetitive change across multiple targets: $ARGUMENTS

Steps:

1. Enumerate the targets explicitly first (use `grep` / `repo_tree` to find every file or call site).
   List them so the scope is clear before editing anything.
2. Do ONE target as a template; get that single edit correct and confirm it compiles/passes.
3. Apply the identical transformation to each remaining target, one at a time, keeping each edit small.
4. After each edit, sanity-check it; after all of them, run the build/tests once over the whole set.
5. Report a checklist of every target and its status (changed / skipped + why), and note any that need
   manual attention because they did not fit the pattern.

Prefer correctness over speed: it is better to skip an ambiguous case and flag it than to apply a wrong
mechanical edit. Do not change targets outside the enumerated list.
