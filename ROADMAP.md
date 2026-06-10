# Roadmap: from learning harness to stronger local coding agent

`imini` is intentionally a teaching kit: local-first, small-model friendly, and designed to make the harness visible.

This roadmap focuses on what should be improved **next**, without re-listing features that are already present.

---

## Current baseline

The repository already includes:

- a real agent loop with tools, streaming, retries, and guards,
- ask / auto / plan modes,
- approvals, session persistence, checkpoints, and rewind,
- project memory loading, compaction, and retrieval,
- MCP, hooks, slash commands, and a sub-agent,
- auth, rate limiting, metrics, a web UI, and remote approvals,
- deterministic tests for key harness behaviors.

The next work should build on that baseline rather than restating it.

---

## 1. Code readability and maintainability

This is the highest-leverage repo-quality improvement.

- Reformat the source consistently so the project is easy to read, review, and teach from.
- Keep docs synchronized with the actual implementation.
- Add simple contribution rules for formatting, test expectations, and documentation updates.
- Prefer smaller methods and clearer type boundaries in core classes such as the loop, controller, and permission/sandbox layers.

This does not make the agent smarter, but it makes the project much more useful as a learning repo.

---

## 2. Codebase navigation and diff-first editing

This is the highest-leverage agent-quality improvement.

Add deterministic repo tools such as:

- `glob`
- `grep`
- `repo_tree`
- `read_many`
- `git_status`
- `git_diff`

Then make the default coding flow look more like:

1. inspect,
2. plan,
3. patch,
4. verify,
5. summarize.

For a small local model, better harness-provided navigation is usually more valuable than a more complicated prompt.

---

## 3. Verification and edit review

The next trust improvement is better verification around edits.

- Show diffs before or alongside edits.
- Add patch-oriented editing and diff previews.
- Run verification commands or hooks after changes when a project supports them.
- Summarize what changed and what was verified in the final answer.
- Track verification outcomes in session history.

This pushes the harness from “it can edit files” toward “it can make reviewable changes.”

---

## 4. Stronger sandboxing and isolation

This remains the biggest production blocker.

- Move from command screening toward real execution isolation for `run_command`.
- Prevent path escape and symlink escape robustly.
- Isolate per-session working state more strongly.
- Reduce default privileges for risky actions.
- Treat secrets and external tool credentials more carefully end-to-end.

The current sandbox/policy layer is useful for learning, but a production-safe system needs a stronger execution boundary.

---

## 5. Better session and multi-user discipline

The repo now has broader session-oriented behavior, but this area still deserves hardening.

- Keep mutable state clearly scoped by session.
- Make long-running jobs, approvals, interrupts, and progress easier to reason about under concurrency.
- Ensure persistence and recovery are consistent across restart and failure cases.
- Expand tests around session concurrency and cancellation.

This is important if the project evolves from a single-user learning harness into a small-team tool.

---

## 6. Retrieval and memory quality

The retrieval layer is already useful, but it can become much stronger.

- Improve indexing quality and refresh behavior.
- Add better query/result inspection for learning and debugging.
- Consider stronger ranking or optional embedding-backed retrieval where it helps.
- Make memory use more transparent in the UI and logs.

The goal is not “more memory everywhere,” but “better selection of the right context.”

---

## 7. Observability and evaluation

The repo already has metrics; the next step is to make evaluation more systematic.

- Add more deterministic harness tests for bad model behavior and recovery paths.
- Add a small eval set for tool selection, confinement, retry behavior, and plan-mode correctness.
- Improve structured logs so debugging long runs is easier.
- Expose enough run metadata to understand failures without digging through code.

For a harness project, evaluation matters as much as feature count.

---

## 8. Product surface and UX

The web UI already helps a lot. The next step is refinement rather than invention.

- Improve plan review and diff review flows.
- Make approvals, job progress, and session history easier to inspect.
- Expose retrieval/debug information only where useful.
- Keep the UI aligned with the learning goals of the repo rather than turning it into a heavy product shell too early.

---

## Suggested order

1. source readability + doc synchronization,
2. codebase navigation tools,
3. diff-first editing + verification,
4. stronger sandboxing,
5. session/concurrency hardening,
6. retrieval improvements,
7. evaluation and observability refinement,
8. UX polish.

That order keeps the repo useful as a teaching project while steadily making it more capable.
