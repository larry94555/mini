---
name: code-review
description: Review a diff or named files for correctness, clarity, and safety, returning prioritized findings.
---
Review the target described by the user (e.g. a `@file`, a directory, or "the last change"): $ARGUMENTS

Steps:

1. Gather the code. If the user referenced files with `@path`, that content is already in context; for
   uncommitted work use the `git_diff` / `git_status` tools; otherwise read the relevant files.
2. Review for, in priority order:
   - correctness and edge cases (off-by-one, null/empty, error handling, concurrency),
   - security and input handling (injection, path traversal, secrets, unsafe deserialization),
   - clarity and naming (would a new reader understand it?),
   - tests (are the changed paths covered?),
   - style and consistency with the surrounding code.
3. Report findings as a short, prioritized list. For each: the file/line, what is wrong, and a concrete
   fix. Lead with the most important issues; do not pad the list.
4. End with a one-line verdict: approve, approve-with-nits, or request-changes.

Be specific and factual. Do not invent issues; if the code looks fine, say so briefly.
