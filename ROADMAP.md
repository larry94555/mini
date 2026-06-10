# ROADMAP

This file now focuses on **what is next**, not on features that already landed.

imini already has:

- SQLite-backed persistence,
- retrieval,
- auth and rate limiting,
- metrics,
- a web UI,
- remote approvals,
- per-session-oriented controls,
- deterministic harness tests.

The next priorities are the areas still separating the project from production use.

---

## 1. Stronger execution sandboxing

This remains the most important production blocker.

Needed next:

- real container/jail execution for `run_command`,
- stronger filesystem isolation,
- symlink escape prevention,
- optional network isolation,
- clearer separation between screening policy and true containment.

---

## 2. Better codebase navigation

The harness needs stronger deterministic repo tools:

- `glob`,
- `grep`,
- `repo_tree`,
- `read_many`,
- `git_status`,
- `git_diff`.

This is one of the highest-leverage improvements for small local models.

---

## 3. Diff-first editing and verification

Next step:

- propose a patch,
- preview the diff,
- apply only after approval,
- run verification commands,
- summarize what passed and failed.

That moves the repo closer to a trustworthy coding workflow.

---

## 4. Stronger end-to-end evals

The repo now has deterministic unit tests.
The next layer is scripted multi-step harness evals for cases like:

- malformed tool args followed by recovery,
- denied approval followed by replanning,
- interrupted runs,
- remote approval timeout behavior,
- retrieval-assisted file selection,
- plan mode with no side effects.

---

## 5. Code intelligence

A later step would be a lightweight symbol or LSP-aware layer so the harness can navigate real repositories more efficiently than plain lexical retrieval.
