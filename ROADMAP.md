# ROADMAP

This roadmap tracks the next improvements for `imini` as a Claude Code-style learning harness.

`imini` already has the core educational harness pieces: agent loop, local llama.cpp model serving, tool
calling, file tools, `apply_patch`, codebase navigation, git tools, sessions, checkpoints, retrieval, a
simple project-memory loader, permissions, plan mode, todos, MCP, hooks, slash commands, web UI, remote
approvals, auth/rate limiting, metrics, Docker, and CI. The next work should not re-add those features.

## North-star priority

The goal is to make `imini` a complete *educational* representation of the high-value, frequently used
features of the Claude Code harness, while keeping the implementation small enough to read and
understand in a weekend.

When choosing the next task, prefer features that are:

- used frequently in day-to-day Claude Code workflows,
- educationally important for understanding the harness/model split,
- small enough to implement and test deterministically,
- useful even with a weak local llama.cpp model,
- not already represented elsewhere in the repo.

Do **not** prioritize admin polish, monetization, packaging, or enterprise hardening ahead of missing
core workflow representation -- unless the task is specifically about trust/security administration.

## High-value Claude Code feature coverage

This is the main priority section. These are the high-frequency Claude Code workflows that are still
missing or only partially represented in `imini`, in priority order.

### Priority 1: Claude-like memory and `/init`

The repo has a simple project-context loader (`ProjectContext` reads one top-level `IMINI.md` /
`CLAUDE.md` / `AGENTS.md`). Claude Code's everyday workflow depends on richer, layered memory.

- Add `/init` to inspect the repository and draft or update `CLAUDE.md`.
- Add `/memory` to show loaded memory files and the effective memory context.
- Support `CLAUDE.local.md`.
- Support `.claude/CLAUDE.md`.
- Support `.claude/rules/*.md`.
- Support simple `@path` imports inside memory files.
- Add diagnostics showing exactly which memory files loaded and why.

### Priority 2: Explicit context references

- Add `@file` and `@directory` prompt references.
- Resolve references safely inside the workspace (no path traversal).
- Add size/cap controls.
- Show referenced context in trace/UI output.
- Add MCP resource references later.

### Priority 3: Skills UX parity

The repo already has a substantial skills backend (local/remote `SKILL.md`, `load_skill`, `save_skill`,
registry with provenance, enable/disable, proposals, session overrides, bundle export). Skills are
**not** missing -- the remaining work is Claude-like *UX parity*:

- Add `/skills`.
- Add direct `/<skill-name>` invocation.
- Add bundled educational skills such as `code-review`, `debug`, `batch`, and `loop`.
- Add `$ARGUMENTS` substitution.
- Add frontmatter support for `when_to_use`, `argument-hint`, and `allowed_tools`.
- Add skill invocation trace entries.
- Add `context: fork` later, after a subagent registry exists.

### Priority 4: Custom subagent registry

A single constrained research sub-agent exists (`SubAgent`); there is no registry or delegation surface.

- Add `agents/*.md`.
- Add `/agents`.
- Add `/agent <name> <task>`.
- Add `delegate_agent(name, task)`.
- Provide built-in `explore`, `review`, `debug`, and `research` agents.
- Support allowed tools and a model profile per agent.

### Priority 5: Patch preview and review UX

- Add `preview_patch`.
- Add `apply_previewed_patch`.
- Add `discard_previewed_patch`.
- Add a browser diff viewer.
- Add hunk-level approval later.

### Later priorities

- LSP-backed code intelligence.
- Session fork/rename/export UX polish.
- `/loop` and scheduled local tasks.
- Image input.
- Plugin packaging.

## Current recommended priority

The highest-value next feature is **Claude-like memory and `/init`**.

The repo already has a simple project-context loader, but Claude Code's frequent workflow depends on
richer memory:

- `/init` to generate or improve `CLAUDE.md`,
- `/memory` to inspect loaded instructions,
- `CLAUDE.local.md`,
- `.claude/rules/*.md`,
- nested memory behavior,
- simple memory diagnostics.

After memory, implement `@file` / `@directory` references, then improve skill UX with `/skills` and
direct `/<skill-name>` invocation.

Do not prioritize activity-view polish, bundle metadata, monetization packaging, or cryptographic skill
provenance ahead of these workflow features -- unless the current task is explicitly about
trust/security administration.

## Guidance for AI implementers

This section helps future AI coding agents pick the next task correctly.

- Prefer missing high-frequency Claude Code workflow features.
- Prefer features that make the harness easier to learn from.
- Prefer features that help a weak local model succeed.
- Prefer deterministic, testable changes.
- Avoid broad rewrites unless explicitly requested.
- Keep formatting-only changes separate from behavior changes.
- Do not continue polishing recently completed areas unless they are blocking.
- Before implementing, check whether the feature already exists in `README.md`, the tests, and the
  source files.

Current top priorities:

1. `/init` and richer memory.
2. `@file` and `@directory` prompt references.
3. Skills UX parity: `/skills`, direct skill invocation, bundled skills.
4. Custom subagent registry.
5. Patch preview and UI diff review.

## Next 10 recommended PRs

1. Add `/memory` diagnostics.
2. Add `/init` to draft/update `CLAUDE.md`.
3. Add `CLAUDE.local.md` and `.claude/rules/*.md` loading.
4. Add `@file` references.
5. Add `@directory` references.
6. Add `/skills` and direct `/<skill-name>` invocation.
7. Add bundled `code-review`, `debug`, `batch`, and `loop` skills.
8. Add `agents/*.md` registry and `/agents`.
9. Add `delegate_agent(name, task)`.
10. Add `preview_patch` and a browser diff viewer.

## Recently completed (summary)

Short summary only; see the git history / PR notes for full detail.

- **Skills:** local/remote `SKILL.md`, `load_skill`, `save_skill`, registry with provenance,
  enable/disable, member proposals, per-session overrides, and bundle export.
- **Plan mode:** execution, retry, re-planning, step verification, live streaming, persistence/resume,
  history, and a per-step tool transcript.
- **Edit trust:** git-backed edit summaries, structured coding reports, schema validation, and per-step
  diff deltas.
- **Sessions:** export/import, integrity checks, migration, import preview, sharing, and ownership
  transfer.
- **UI/ops:** plan-history viewer, activity view (filter/paginate/export), per-session activity,
  sharing surface, and audit entries.

## Supporting tracks (lower priority)

These remain valuable but should come after the high-value Claude Code feature coverage above. They are
not the immediate next priority.

### Educational completeness

Goal: a developer should be able to learn Claude Code-style harness architecture from this repo in a
weekend.

- Keep `docs/LEARNING_PATH.md`, `docs/TRACE_EDIT.md`, and `docs/CONCEPT_MAP.md` current.
- Add more trace documents: plan-mode, remote-approval, bad-tool-call-recovery, mcp-tool-call.
- Add small exercises at the end of each learning module and a short demo script.
- Later: diagrams for the agent loop / approval / persistence flows; a glossary; a
  `docs/PRODUCTION_NOTES.md` (educational vs production-grade).

### Source readability

Goal: the most important classes should be readable without a formatter or IDE magic.

- Continue manually reformatting high-value teaching files (`AgentEngine`, `BuiltinTools`,
  `CodebaseTools`, `ContextManager`, `CheckpointStore`, `SessionStore`, `AgentController`,
  `LlamaClient`).
- Keep formatting-only changes separate from behavior changes; avoid huge one-line files.
- Later: reintroduce automated formatting once JDK/formatter versions are pinned, then add a CI check.

### Deterministic harness tests

Goal: test harness behavior without a live model.

- Add scripted traces for the real `AgentEngine` using a fake `LlamaClient`.
- Test denied-approval recovery, interrupt/steer behavior, and checkpoint grouping for multi-file
  patches.
- Later: a small offline eval suite over fake model scripts; a live smoke suite when a local
  `llama-server` is available.

### Edit trust and verification

Goal: file changes should be easy to review and hard to misrepresent. (Edit summaries, coding reports,
schema enforcement, and per-step diff deltas are done; explicit patch preview tooling is now Priority 5
above.)

- Add a final-answer schema for coding tasks.
- Add project-specific verification commands in `IMINI.md` or `.imini/config`.

### Codebase understanding

Goal: improve repo-understanding quality on top of the existing deterministic navigation tools.

- Improve `repo_tree` for larger repos; better caps/paging for `grep`, `read_many`, `git_log`,
  `git_blame`.
- Make symbol extraction easier to extend by language; add edge-case tests.
- Add a repository-map summary (tree + key files + top symbols).
- Later: optional LSP-backed symbol lookup, dependency-graph summaries, call-site search, smarter
  retrieval refresh after mutation.

### Production safety

Goal: move from educational safety controls to enforceable execution boundaries. The biggest gap is
still real command/tool isolation -- policy checks and path screening are not a complete boundary.

- Document the difference between policy checks and real isolation.
- Make `sandbox.command-mode=allowlist` the recommended shared-deployment setting.
- Add a `/doctor` or startup check that warns when running without containerized command execution.
- Later: per-run/per-session container/jail, network off by default, workspace-only mounts, CPU/memory/
  process limits, hardened MCP execution, and append-only audit of prompts/diffs/results.

### Multi-user and operations

Goal: make the app safer to run for more than one user.

- Clarify that API-key auth is app-level auth, not full identity/RBAC; add per-key attribution where
  missing; document running behind a reverse proxy; add backup/restore notes for `.imini/imini.db`.
- Later: per-user workspaces and permission policies, OAuth/OIDC, Prometheus/OpenTelemetry metrics, an
  admin audit dashboard.

### Trust/security administration

These are legitimate when the current task is explicitly about trust/security administration, but they
should not jump ahead of the missing core workflow features above.

- Cryptographic provenance for skills and bundles (signing + a trust root) building on the existing
  SHA-256 integrity hashing.
- Record per-session skill toggles against the session target (so they appear in the per-session
  activity tab) and add a `detail` column to audit entries.
- Scheduled/rotating audit export to a file path or webhook for long-term retention.

### Monetization and packaging

Goal: package the project as a learning asset before selling it as a developer tool. Lowest priority.

- Add a course outline, a five-minute demo script, a README "who this is for" section, and a clear
  license if missing.
- Later: workshop materials, optional enterprise hardening modules, paid support/consulting only after
  the learning path is polished.
