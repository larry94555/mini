---
name: commit-message
description: Write a clear conventional-commits git commit message from a diff or change summary.
---
When asked to write a commit message:

1. Use the Conventional Commits format: `<type>(<scope>): <subject>`.
   - type is one of: feat, fix, docs, refactor, test, chore, perf, build, ci.
   - scope is optional and names the area changed (e.g. `auth`, `parser`).
   - subject is imperative mood, lower case, no trailing period, <= 50 chars.
2. Leave a blank line, then a body that explains WHAT changed and WHY (wrap at 72 cols).
3. If the change is breaking, add a `BREAKING CHANGE:` footer describing the impact.
4. Keep it factual; do not invent changes that are not in the diff.

Example:

feat(parser): support trailing commas in arrays

Arrays now tolerate a trailing comma before the closing bracket, matching
the behaviour of the object parser. Reduces friction when editing lists.
