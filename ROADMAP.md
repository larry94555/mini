# ROADMAP

This roadmap tracks the next improvements for `imini` as a Claude Code-style
learning harness.

`imini` already has the core educational harness pieces:

- agent loop
- local llama.cpp model serving
- tool calling
- file tools
- `apply_patch`
- codebase navigation
- git tools
- sessions
- checkpoints
- retrieval
- a simple project-memory loader
- permissions
- plan mode
- todos
- MCP
- hooks
- slash commands
- web UI
- remote approvals
- auth/rate limiting
- metrics
- Docker
- CI

The next work should not re-add those features. It should fill the remaining
high-value Claude Code workflow gaps and make the existing implementation
easier to learn from, easier to trust, and safer to run.

## North-star priority

The goal is to make `imini` a complete educational representation of the
high-value, frequently used features of the Claude Code harness, while keeping
the implementation small enough to read and understand in a weekend.

When choosing the next task, prefer features that are:

- used frequently in day-to-day Claude Code workflows
- educationally important for understanding the harness/model split
- small enough to implement and test deterministically
- useful even with a weak local llama.cpp model
- not already represented elsewhere in the repo

Do not prioritize admin polish, monetization, packaging, or enterprise hardening
ahead of missing core workflow representation unless the task is specifically
about trust/security administration.

## High-value Claude Code feature coverage

This is the main priority section. These are the high-frequency Claude Code
workflows that are still missing or only partially represented in `imini`, in
priority order.

### Priority 1: Claude-like memory and `/init`

The repo has a simple project-context loader. `ProjectContext` reads one
top-level `IMINI.md`, `CLAUDE.md`, or `AGENTS.md`. Claude Code's everyday
workflow depends on richer, layered memory.

Add:

- `/init` to inspect the repository and draft or update `CLAUDE.md`
- `/memory` to show loaded memory files and the effective memory context
- `CLAUDE.local.md`
- `.claude/CLAUDE.md`
- `.claude/rules/*.md`
- simple `@path` imports inside memory files
- diagnostics showing exactly which memory files loaded and why

### Priority 2: Explicit context references

Add user-controlled context references so the user can directly point the
agent at relevant files and directories.

Add:

- `@file` prompt references
- `@directory` prompt references
- safe workspace-confined reference resolution
- size and token caps for referenced context
- trace/UI display of referenced context
- MCP resource references later

### Priority 3: Skills UX parity

The repo already has a substantial skills backend, including local and remote
`SKILL.md` support, `load_skill`, `save_skill`, registry/provenance,
enable/disable, proposals, session overrides, and bundle export.

Skills are not missing. The remaining work is Claude-like UX parity.

Add:

- `/skills`
- direct `/<skill-name>` invocation
- bundled educational skills such as `code-review`, `debug`, `batch`, and
  `loop`
- `$ARGUMENTS` substitution
- frontmatter support for `when_to_use`
- frontmatter support for `argument-hint`
- frontmatter support for `allowed_tools`
- skill invocation trace entries
- `context: fork` later, after a subagent registry exists

### Priority 4: Custom subagent registry

A single constrained research sub-agent exists through `SubAgent`. There is no
general registry or delegation surface for reusable specialist agents.

Add:

- `agents/*.md`
- `/agents`
- `/agent <name> <task>`
- `delegate_agent(name, task)`
- built-in `explore`, `review`, `debug`, and `research` agents
- allowed tools per agent
- model profile per agent

### Priority 5: Patch preview and review UX

The repo already has `apply_patch`, edit verification, and structured coding
reports. The remaining high-value workflow gap is a first-class review
experience before changes are applied.

Add:

- `preview_patch`
- `apply_previewed_patch`
- `discard_previewed_patch`
- browser diff viewer
- hunk-level approval later

### Later priorities

These are valuable, but should not come before the priority items above.

- LSP-backed code intelligence
- session fork/rename/export UX polish
- `/loop` and scheduled local tasks
- image input
- plugin packaging

## Current recommended priority

The highest-value next feature is Claude-like memory and `/init`.

The repo already has a simple project-context loader, but Claude Code's
frequent workflow depends on richer memory:

- `/init` to generate or improve `CLAUDE.md`
- `/memory` to inspect loaded instructions
- `CLAUDE.local.md`
- `.claude/rules/*.md`
- nested memory behavior
- simple memory diagnostics

After memory, implement `@file` and `@directory` references. After that,
improve skill UX with `/skills` and direct `/<skill-name>` invocation.

Do not prioritize activity-view polish, bundle metadata, monetization
packaging, or cryptographic skill provenance ahead of these workflow features
unless the current task is explicitly about trust/security administration.

## Guidance for AI implementers

This section helps future AI coding agents pick the next task correctly.

When asked to choose or implement the next task:

- Prefer missing high-frequency Claude Code workflow features.
- Prefer features that make the harness easier to learn from.
- Prefer features that help a weak local model succeed.
- Prefer deterministic, testable changes.
- Avoid broad rewrites unless explicitly requested.
- Keep formatting-only changes separate from behavior changes.
- Do not continue polishing recently completed areas unless they are blocking.
- Before implementing, check whether the feature already exists in `README.md`,
  tests, and source files.

Current top priorities:

1. `/init` and richer memory
2. `@file` and `@directory` prompt references
3. skills UX parity: `/skills`, direct skill invocation, bundled skills
4. custom subagent registry
5. patch preview and UI diff review

## Next 10 recommended PRs

1. Add `/memory` diagnostics.
2. Add `/init` to draft or update `CLAUDE.md`.
3. Add `CLAUDE.local.md` and `.claude/rules/*.md` loading.
4. Add `@file` references.
5. Add `@directory` references.
6. Add `/skills` and direct `/<skill-name>` invocation.
7. Add bundled `code-review`, `debug`, `batch`, and `loop` skills.
8. Add `agents/*.md` registry and `/agents`.
9. Add `delegate_agent(name, task)`.
10. Add `preview_patch` and a browser diff viewer.

## Recently completed summary

Short summary only; see the git history and PR notes for full detail.

- **Skills:** local/remote `SKILL.md`, `load_skill`, `save_skill`,
  registry/provenance, enable/disable, member proposals, per-session overrides,
  and bundle export.
- **Plan mode:** execution, retry, re-planning, step verification, live
  streaming, persistence/resume, history, and a per-step tool transcript.
- **Edit trust:** git-backed edit summaries, structured coding reports, schema
  validation, and per-step diff deltas.
- **Sessions:** export/import, integrity checks, migration, import preview,
  sharing, and ownership transfer.
- **UI/ops:** plan-history viewer, activity view, sharing surface, per-session
  activity, and audit entries.

## Supporting tracks

These tracks remain valuable, but they should come after the high-value Claude
Code feature coverage above. They are not the immediate next priority.

### Educational completeness

Goal: a developer should be able to learn Claude Code-style harness
architecture from this repo in a weekend.

Keep current:

- `docs/LEARNING_PATH.md`
- `docs/TRACE_EDIT.md`
- `docs/CONCEPT_MAP.md`

Add:

- more trace documents:
  - plan mode
  - remote approval
  - bad tool-call recovery
  - MCP tool call
- small exercises at the end of each learning module
- a short demo script
- diagrams for the agent loop, approval flow, and persistence flow
- a glossary
- `docs/PRODUCTION_NOTES.md` explaining educational vs production-grade safety

### Source readability

Goal: the most important classes should be readable without a formatter or IDE
magic.

Continue manually reformatting high-value teaching files:

- `AgentEngine`
- `BuiltinTools`
- `CodebaseTools`
- `ContextManager`
- `CheckpointStore`
- `SessionStore`
- `AgentController`
- `LlamaClient`

Guidelines:

- Keep formatting-only changes separate from behavior changes.
- Avoid huge one-line files.
- Reintroduce automated formatting only after JDK/formatter versions are pinned.
- Add a CI formatting check only after the formatter is stable locally.

### Deterministic harness tests

Goal: test harness behavior without a live model.

Add:

- scripted traces for the real `AgentEngine` using a fake `LlamaClient`
- denied-approval recovery tests
- interrupt/steer behavior tests
- checkpoint grouping tests for multi-file patches

Later:

- a small offline eval suite over fake model scripts
- a live smoke suite when a local `llama-server` is available

### Edit trust and verification

Goal: file changes should be easy to review and hard to misrepresent.

Already done:

- edit summaries
- coding reports
- schema enforcement
- per-step diff deltas

Next:

- add a final-answer schema for coding tasks
- add project-specific verification commands in `IMINI.md` or `.imini/config`
- implement patch preview tooling from Priority 5

### Codebase understanding

Goal: improve repo-understanding quality on top of the existing deterministic
navigation tools.

Add:

- better `repo_tree` behavior for larger repos
- better caps and paging for `grep`, `read_many`, `git_log`, and `git_blame`
- easier-to-extend symbol extraction by language
- edge-case tests for symbol extraction
- repository-map summary:
  - tree
  - key files
  - top symbols

Later:

- optional LSP-backed symbol lookup
- dependency-graph summaries
- call-site search
- smarter retrieval refresh after mutation

### Production safety

Goal: move from educational safety controls to enforceable execution
boundaries.

The biggest gap is still real command/tool isolation. Policy checks and path
screening are useful, but they are not a complete boundary.

Add:

- documentation explaining policy checks vs real isolation
- `sandbox.command-mode=allowlist` as the recommended shared-deployment setting
- `/doctor` or a startup check that warns when running without containerized
  command execution

Later:

- per-run or per-session container/jail
- network off by default
- workspace-only mounts
- CPU, memory, and process limits
- hardened MCP execution
- append-only audit of prompts, diffs, and results

### Multi-user and operations

Goal: make the app safer to run for more than one user.

Add:

- clearer docs that API-key auth is app-level auth, not full identity/RBAC
- per-key attribution where missing
- reverse-proxy deployment notes
- backup/restore notes for `.imini/imini.db`

Later:

- per-user workspaces
- per-user permission policies
- OAuth/OIDC
- Prometheus/OpenTelemetry metrics
- admin audit dashboard

### Trust/security administration

These are legitimate when the current task is explicitly about trust/security
administration, but they should not jump ahead of the missing core workflow
features above.

Add later:

- cryptographic provenance for skills and bundles:
  - signing
  - trust root
  - verification built on existing SHA-256 integrity hashing
- per-session skill toggles recorded against the session target
- a `detail` column for audit entries
- scheduled or rotating audit export to a file path or webhook

### Monetization and packaging

Goal: package the project as a learning asset before selling it as a developer
tool.

Lowest priority.

Add later:

- course outline
- five-minute demo script
- README "who this is for" section
- clear license if missing
- workshop materials
- optional enterprise hardening modules
- paid support/consulting only after the learning path is polished
