# ROADMAP

This roadmap is optimized for one goal:

> Make `imini` a complete educational representation of the high-value,
> frequently used Claude Code harness features while keeping the codebase
> small enough to understand and safe enough to experiment with locally.

Use this roadmap as the source of truth for what should be implemented next.
When choosing the next task, prefer missing high-frequency workflow features
over lower-frequency polish, packaging, or enterprise hardening.

## North-star priority

When choosing the next implementation task, prefer features that are:

1. used frequently in day-to-day Claude Code workflows,
2. educationally important for understanding the harness/model split,
3. small enough to implement and test deterministically,
4. useful with a weak local `llama.cpp` model,
5. not already represented elsewhere in the repo.

Avoid prioritizing admin polish, monetization, packaging, or enterprise
hardening ahead of missing core workflow representation unless the task is
explicitly about trust, security, or operations.

## Current state

The repository already represents many important Claude Code-style harness
features:

- local `llama.cpp` / `llama-server` model integration,
- agent loop with tool calls, retries, and guardrails,
- file tools and patch application,
- deterministic codebase navigation,
- git-backed verification,
- plan mode and approvals,
- sessions and checkpoints,
- retrieval,
- project memory,
- skills backend,
- MCP integration,
- hooks,
- slash commands,
- web UI and remote approvals,
- RBAC, auth, rate limits, metrics, Docker, and CI.

The roadmap below focuses on the highest-value missing or incomplete workflow
representation, not on re-adding features that already exist.

## 1. High-value Claude Code feature coverage

Goal: represent the most common, high-value Claude Code workflows before
optimizing lower-frequency admin or packaging features.

### Priority 1 — Claude-like memory and `/init`

The current project-context loader is useful, but Claude Code’s everyday
workflow depends on richer memory behavior.

Implement:

- `/init` to inspect the repository and draft or update `CLAUDE.md`,
- ~~`/memory` to show loaded memory files and effective memory context~~ (done),
- ~~`CLAUDE.local.md`~~ (done),
- ~~`.claude/CLAUDE.md`~~ (done),
- ~~`.claude/rules/*.md`~~ (done),
- ~~simple `@path` imports inside memory files~~ (done),
- ~~diagnostics showing exactly which memory files loaded and why~~ (done via `/memory`).

Priority 1 is now **complete**: the layered memory loader, `/memory` diagnostics, and `/init`
(a deterministic repo scan that drafts/creates `CLAUDE.md`) all exist (see Recently completed). The next
priority is **explicit context references** (Priority 2).

Why this is first:

- persistent memory is one of the most frequently used Claude Code features,
- it improves almost every later coding task,
- it is highly educational because it teaches always-on project context.

### Priority 2 — Explicit context references

Add user-controlled prompt references so the user can explicitly inject context.

Implement:

- ~~`@file` prompt references~~ (done),
- ~~`@directory` prompt references~~ (done),
- ~~safe size and path caps~~ (done -- workspace-confined, file/total/dir-entry caps),
- ~~UI / trace display of referenced context~~ (done -- `[context] attached ...` on the run trace),
- MCP resource references later.

Priority 2's core is now **complete** (see Recently completed); only MCP-resource references remain.

Why this was second:

- it is a very common workflow,
- it complements existing deterministic navigation tools,
- it teaches the difference between user-provided context and model-selected
  context.

### Priority 3 — Skills UX parity

The repository already has a substantial skills backend. The missing work is
Claude-like UX parity, not “add skills from scratch.”

Implement:

- ~~`/skills`~~ (done -- lists skills with descriptions + effective enabled-state),
- ~~direct `/skill-name` invocation~~ (done -- enabled skills only; reserved commands protected),
- ~~bundled educational skills such as `code-review`, `debug`, `batch`, and
  `loop`~~ (done -- shipped under `skills/`),
- ~~`$ARGUMENTS` substitution~~ (done -- `$ARGUMENTS`/`$ARGS`; args appended if no placeholder),
- frontmatter support for `when_to_use`, `argument-hint`, and `allowed_tools`,
- ~~skill invocation trace entries~~ (done -- `[skill] invoked /<name>`),
- `context: fork` later, after the subagent registry exists.

Still open: `when_to_use`/`argument-hint`/`allowed_tools` frontmatter.

Why this is third:

- skills are now a major Claude Code extension mechanism,
- the backend already exists, so the leverage is high,
- it makes the system easier to teach and easier to use.

### Priority 4 — Custom subagent registry

Generalize the existing research subagent into a reusable registry.

Implement:

- `agents/*.md`,
- `/agents`,
- `/agent NAME TASK`,
- `delegate_agent(name, task)`,
- built-in `explore`, `review`, `debug`, and `research` agents,
- per-agent allowed tools and model profile.

Why this is fourth:

- subagents are high-value for context isolation and specialization,
- they pair naturally with skills,
- they represent an important Claude Code concept that is only partially
  present today.

### Priority 5 — Patch preview and review UX

The repository already supports mutation and verification. The next step is to
make review first-class.

Implement:

- `preview_patch`,
- `apply_previewed_patch`,
- `discard_previewed_patch`,
- browser diff viewer,
- hunk-level approval later.

Why this is fifth:

- review is a core part of the coding workflow,
- it makes mutations easier to trust,
- it builds directly on existing patch, checkpoint, and git verification work.

### Later priorities

These are still valuable, but they come after the top five workflow features.

- LSP-backed code intelligence.
- Session fork / rename / export UX polish.
- `/loop` and scheduled local tasks.
- Image input.
- Plugin packaging.

## 2. Current recommended priority

The highest-value next feature is now **skills UX parity** (Priority 3), since
Priorities 1 and 2 are complete: layered memory + `/memory` + `/init`, and
`@file` / `@directory` prompt references (workspace-confined, capped, shown on
the trace).

`/skills` (listing), direct `/skill-name` invocation, `$ARGUMENTS` substitution,
skill-invocation trace entries, and bundled educational skills (`code-review`,
`debug`, `batch`, `loop`) now exist. The remaining Priority 3 work is:

- frontmatter for `when_to_use` (auto-load hinting), `argument-hint` (shown in
  `/skills`), and `allowed_tools` (per-skill tool scoping).

After skill
UX with `/skills` and direct `/skill-name` invocation.

Do not prioritize activity-view polish, bundle metadata, monetization
packaging, or cryptographic skill provenance ahead of these workflow features
unless the current task is explicitly about trust or security administration.

## 3. Guidance for AI implementers

When asked to pick the next task, follow this priority order:

1. Prefer missing high-frequency Claude Code workflow features.
2. Prefer features that make the harness easier to learn from.
3. Prefer features that help a weak local model succeed.
4. Prefer deterministic, testable changes.
5. Avoid broad rewrites unless explicitly requested.
6. Keep formatting-only changes separate from behavior changes.
7. Do not continue polishing recently completed areas unless they are blocking.
8. Before implementing, check whether the feature already exists in
   `README.md`, tests, or source files.

Current top priorities:

1. `/init` and richer memory.
2. `@file` and `@directory` prompt references.
3. Skills UX parity: `/skills`, direct `/skill-name` invocation, bundled
   skills.
4. Custom subagent registry.
5. Patch preview and UI diff review.

## 4. Next 10 recommended PRs

1. ~~Add `/memory` diagnostics.~~ (done -- layered loader + `@path` imports + `/memory` / `GET /memory/files`)
2. ~~Add `/init` to draft or update `CLAUDE.md` from a repo scan.~~ (done -- `RepoScan`/`InitDraft`/`InitService`, `POST /init`)
3. ~~Add `@file` references.~~ (done -- `ContextRefs`/`ContextRefService`)
4. ~~Add `@directory` references.~~ (done -- one-level listing, capped)
5. ~~Add `/skills` and direct `/skill-name` invocation.~~ (done -- `SkillInvocation`, `$ARGUMENTS`, trace)
6. ~~Add bundled `code-review`, `debug`, `batch`, and `loop` skills.~~ (done -- under `skills/`)
7. Add `when_to_use`/`argument-hint`/`allowed_tools` frontmatter.
8. Add `agents/*.md` registry and `/agents`.
9. Add `delegate_agent(name, task)`.
10. Add `preview_patch` and browser diff viewer.
11. Add hunk-level approval to the patch-preview flow.

## 5. Educational completeness

After the highest-value workflow features above, continue improving the project
as a teaching tool.

Recommended follow-up work:

- add more trace documents,
- add a glossary,
- add “how to add a tool” and “how to add an MCP server” tutorials,
- add diagrams for the loop, approvals, and persistence,
- add small deterministic eval scenarios.

## 6. Source readability

The project is now feature-rich enough that readability directly affects its
teaching value.

Recommended work:

- reformat the main Markdown files with normal line breaks,
- reformat the top educational Java files first,
- split large behavior-heavy methods only when it improves readability,
- keep formatting-only PRs separate from feature PRs.

## 7. Deterministic harness tests

Continue investing in deterministic tests that do not require a live model.

Recommended work:

- expand fake-model end-to-end scenarios,
- add more bad-model-behavior cases,
- add golden-trace tests,
- add evaluation docs that explain deterministic tests versus live smoke tests.

## 8. Edit trust and verification

The repository already has good foundations here. Continue improving trust after
mutations.

Recommended work:

- strengthen post-edit summaries,
- surface changed files and verification more clearly in the UI,
- add patch preview and approval flow,
- keep verification honest when tests were not run.

## 9. Codebase understanding

The deterministic navigation layer is already present. Future work should
improve quality rather than re-adding basic tools.

Recommended work:

- improve ranking and output shaping,
- add richer symbol extraction,
- add repo-map style summaries,
- add LSP-backed precision later.

## 10. Production safety

This remains the biggest production gap, but it is not the first educational
priority.

Recommended work:

- stronger isolated execution for shell commands,
- better MCP isolation and policy,
- append-only event logs,
- more explicit auditability,
- clearer deployment and secret-handling docs.

## 11. Multi-user and operations

These matter for team usage, but they should follow the core workflow features.

Recommended work:

- stronger auth integration,
- per-user workspace controls,
- improved admin audit views,
- backup and restore guidance,
- operational dashboards.

## 12. Monetization and packaging

These are intentionally last. Do not let them displace the core feature
coverage roadmap.

Possible future work:

- plugin packaging,
- educational packaging,
- workshop/course materials,
- consulting-oriented demos,
- open-core packaging experiments.

## Recently completed

Keep this section short. Move detailed history elsewhere if needed.

- Bundled educational skills: `code-review`, `debug`, `batch`, and `loop` ship as `SKILL.md` files under
  `skills/`, each using `$ARGUMENTS` and pairing with `@file` references / the deterministic tools, so
  `/skills` is useful out of the box (load/parse asserted by `BundledSkillsTest`).
- `/skills` + direct `/<skill-name>` invocation: `/skills` lists available skills (descriptions +
  effective enabled-state); `/<skill-name> [args]` runs an enabled skill's body as the prompt with
  `$ARGUMENTS`/`$ARGS` substituted (args appended if no placeholder), logged as `[skill] invoked /<name>`
  on the trace. Built-in commands are reserved (`SkillInvocation` pure + unit-tested).
- `@file` / `@directory` prompt references: mentioning `@path` in a prompt inlines that file's content
  (or a directory's one-level listing) into what the model sees, inside a `<referenced-context>` block.
  Resolution is workspace-confined with file/total/dir-entry caps; unresolved tokens (e.g. `@mentions`)
  are left untouched; attachments are shown on the run trace (`ContextRefs` pure + unit-tested,
  `ContextRefService`). Completes Priority 2's core.
- `/init` (draft/update `CLAUDE.md`): a deterministic repository scan (build-system + language detection
  + layout) renders a `CLAUDE.md` scaffold and creates it if absent (never overwriting an existing file
  implicitly); `POST /init?write=&overwrite=` for explicit control (`RepoScan`/`InitDraft` pure +
  unit-tested, `InitService`). Completes Priority 1.
- Project memory (layered) + `/memory` diagnostics: loads `.claude/CLAUDE.md`, `CLAUDE.md`, `IMINI.md`,
  `AGENTS.md`, `.claude/rules/*.md`, and `CLAUDE.local.md` (in order) into the system prompt, inlines
  `@path` imports (depth/size/cycle guarded), and shows what loaded via the `/memory` command and
  `GET /memory/files` (`MemoryLoader` pure + unit-tested; `ProjectContext` rewritten).
- Skills: local/remote `SKILL.md`, registry, enable/disable, proposals,
  session overrides, and bundle export.
- Plan mode: execution, retry, re-planning, verification, persistence/resume,
  history, and per-step tool transcript.
- Edit trust: git-backed edit summaries, structured coding reports, schema
  validation, and per-step diff deltas.
- Sessions: export/import, integrity checks, migration, import preview,
  sharing, and ownership transfer.
- UI/ops: plan history, activity view, sharing surface, and audit entries.
