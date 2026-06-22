# ROADMAP

This roadmap is optimized for one goal:

> Make `imini` a complete educational representation of the high-value,
> frequently used Claude Code harness features while keeping the codebase
> small enough to understand and safe enough to experiment with locally.

Use this roadmap as the source of truth for what to implement next.

---

## Next PR — decision procedure (read this first)

**Do NOT trust any "project complete" statement anywhere without re-deriving the gap
from `README.md` + source.** The harness has, in the past, been declared "finished"
while a high-frequency workflow feature was still missing — which let effort drift into
open-ended operations polish. Before proposing work, classify the candidate:

```
WORKFLOW FEATURE = something a developer does many times a day in Claude Code
                   (edit, run, navigate, COMMIT, plan, remember, reference, delegate).
OPS / HARDENING  = alerting, metrics, dashboards, auth, packaging, signing,
                   multi-node, enterprise. NOT a priority regardless of how
                   open-ended its surface looks.
```

**Build the highest unbuilt WORKFLOW FEATURE. Decline OPS/HARDENING work** unless the
request is explicitly about trust, security, or operations.

---

## Track B — Real-world capability: multi-root project work (NEW DIRECTION)

> Added because the harness, while a faithful *educational* model of the Claude Code workflow, is confined
> to a single workspace and so cannot perform a realistic cross-project task such as:
>
> > "Create a project at `C:\Users\larry\github\typescript-project` that is the TypeScript equivalent of the
> > code at `C:\Users\larry\github\mini`."
>
> The goal of Track B is to make the harness genuinely useful for real tasks **without** weakening its
> safety model — every capability below is gated behind explicit, auditable, scoped user approval. This
> raises educational impact: it shows how a coding agent safely escapes a single sandbox.

### Why it is blocked today (honest current-state assessment)

Verified against the source, three layers stop the task above:

1. **Single workspace root.** `agent.workspace-root` (default = CWD) is read once by `Sandbox`,
   `PermissionService`, and `RetrievalService`. There is exactly one root.
2. **Reads are confined to it.** `Sandbox` (`sandbox.confine-reads=true`) and `PermissionService.isWithin`
   reject reads outside the root, so the agent cannot even *read* the source project at path A if the
   destination/working root is elsewhere.
3. **Writes outside the root are hard-denied before approval.** `PermissionService.decide` calls
   `writesOutsideRoot(...)` and returns `DENY` for `write_file`/`edit_file` whose `path` is outside the root
   — this happens *before* any approval path, so even an authorizing user cannot currently permit it.

There is also **no project-scaffolding capability** (creating a directory tree / many files atomically) and
**no port/translation tooling**; today's file tools are single-file `read_file`/`write_file`/`edit_file`
plus `list_dir`.

### Design principles (safety is the feature)

- **Default-closed.** Multi-root stays *off* unless explicitly enabled; with it off, behavior is byte-for-byte
  what it is today.
- **Explicit, scoped grants.** A second root is usable only after the user grants it, naming the exact
  absolute path and the access (`read` vs `read-write`). Grants are per-session, audited, and expire.
- **Approval at the boundary, per destination root.** Writing into a newly granted root requires an approval
  whose payload shows the *root* and a *summary of the file set* (counts, total bytes, the tree), not just a
  single path — so the user authorizes the project creation, not 200 invisible writes.
- **Plan mode first.** A multi-file scaffold must be presentable as a plan (the full file manifest) under
  `PLAN` mode and executed only after the user re-sends in `ask`/`auto`.
- **Every cross-root action is auditable** in the existing `AuditLog`, and **capability-scoped** (a tenant/
  role may be barred from multi-root entirely via `CapabilityService`).

### Ranked changes (each shippable as its own approval-gated PR)

1. **Multi-root model (`WorkspaceRoots` service).** ✅ **Done (PR #1).** Replace the single `root` with a
   registry of roots, each with an id, absolute path, and access level (`READ`, `READ_WRITE`). The default
   root (CWD) is always present and `READ_WRITE`. `Sandbox`/`PermissionService`/`RetrievalService` consult the
   registry instead of a single field. New config `agent.multi-root.enabled` (default **false**).

2. **Approval-gated root grants.** ✅ **Done (PR #2).** A `grant_workspace_root` tool (mutating, always gated — never auto-approved
   even in `AUTO`) that requests the user authorize an absolute path at a given access level. On approval the
   root joins the registry for the session, is written to `AuditLog`, and shows in the approval UI with the
   path, access level, and (for read) a one-line listing preview. A matching `POST /admin/roots` +
   `revoke_workspace_root`. Reads/writes outside *all* granted roots stay denied. *(Implemented as the
   always-gated tools + a read-only `GET /admin/roots`; grants are currently process-wide rather than truly
   per-session — see PR #3+ for per-session scoping and the approval-UI preview.)*

3. **`writesOutsideRoot` becomes `writesOutsideGrantedRoots`.** `PermissionService.decide` denies only when a
   path is outside *every* `READ_WRITE` root; a path inside a granted RW root proceeds to the normal approval
   path (it is **not** auto-allowed merely by being granted — a destructive write still needs the mode's
   approval). Reads are checked against `READ`-or-better roots.

4. **Project-scaffold capability (`create_project` / `write_files`).** ✅ **Done (PR #3 of this track).** A tool that takes a manifest
   (list of relative paths + contents under a destination root) and, under `PLAN`, returns the full tree +
   byte counts as the plan; under `ask`/`auto`, performs the writes **transactionally** (all-or-nothing,
   into a temp dir then atomic move, refusing to overwrite a non-empty destination unless the approval said
   so). The approval payload summarizes the manifest, not individual files.

5. **Port/translate workflow (the actual task).** With (1)-(4), "port A to TypeScript at B" becomes: grant
   `read` on A and `read-write` on B → the agent reads A (existing nav/retrieval tools, now multi-root) →
   produces a `create_project` manifest for B (the model does the language translation) → user approves the
   manifest → transactional scaffold. Add an `init`-style profile/template hook so common scaffolds
   (a TS project's `package.json`, `tsconfig.json`, `src/`, test config) are consistent. Translation quality
   is the model's job; the harness guarantees the *safety envelope and the file operations*.

6. **Tests + docs (mandatory, same PRs).** Golden traces for: a denied ungranted write; a granted-root write
   that still requires approval; a `PLAN`-mode scaffold that records the manifest without writing; a
   transactional scaffold that rolls back on a mid-way failure; capability-scoping that bars multi-root for a
   role. A `docs/MULTI_ROOT.md` walkthrough and a `WHATS_NOT_INCLUDED.md` update. Cross-platform path
   handling (Windows `C:\…` vs POSIX) must be covered, since the motivating task uses Windows paths.

### Acceptance (the motivating task works safely)

A user can run the TypeScript-port request; the agent **cannot** touch A or B until the user grants those
roots with explicit access levels; the new-project write is presented as an approvable manifest (or a plan
first); nothing is written outside a granted `READ_WRITE` root; every grant and write is audited; and with
multi-root disabled the harness behaves exactly as it does today.

---


### Build next (ranked; re-verify against README + source before starting)

The three previous "Build next" workflow gaps — **git write workflow**, **hook lifecycle breadth**, and
**MCP resources + prompts + HTTP transport** — are complete, and so are the follow-on polish items that
finished them: **MCP prompts as `/mcp__server__prompt` slash commands**, **`SessionStart`/`Notification`
hooks**, and **`git_push` (off by default) + the staged diff in the approval UI** (see Recently completed).

With those done, mini represents the high-value, frequently-used Claude Code **workflow** features end to
end, and the supporting surfaces (hooks, MCP, git) are complete. **There is no remaining high-frequency
workflow gap.** The most recent work added the **test + educational depth** that was queued here: a
live-server **MCP integration test** (node stdio stub + JDK HttpServer stub, both transports), an
**end-to-end git-commit approval-flow test** (asserting the staged diff rides the approval payload), and a
**workflow walkthrough doc** with edit→verify→commit / hook / MCP lifecycle diagrams (see Recently completed).

Remaining candidates are genuinely optional — pursue only if a concrete need appears:

1. **Hook/Notification breadth** — additional `Notification` trigger points (e.g. on long-running tools)
   if real usage shows a need.
2. **More eval depth** — the control-flow branches now all have end-to-end golden traces (happy path,
   plan/invalid-args/dup guard, capability/rate-limit denial, subagent hand-off, multi-server MCP routing).
   Further traces are pure regression guards for specific behaviours, not coverage gaps.

If none of these clears the "high value AND frequent" bar for your goals, the *single-workspace educational*
workflow representation is **done**. The next frontier is **Track B (multi-root project work)** above —
making the harness useful for real cross-project tasks while keeping every escape from the sandbox behind
explicit, scoped, audited user approval. Track B is the priority direction when the goal is real-world
usefulness rather than more single-root depth.

### Do NOT build next

- **Further alerting / SLO / observability work — that subsystem is feature-complete.** Decline
  additional alerting polish in favor of the workflow gaps above.
- **Enterprise hardening:** hardware-backed/OS keystore signing, Postgres/multi-node persistence,
  plugin dependency resolver.
- **Cosmetic / low-frequency:** output styles, statusline, agent teams, async/background agents.
- For the full catalogue of things omitted on purpose, see
  [`docs/WHATS_NOT_INCLUDED.md`](docs/WHATS_NOT_INCLUDED.md).

### A priority is "done" when…

its workflow is usable end-to-end from chat **and** has a deterministic test. Stop there — do
**not** keep polishing a completed area.

---

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

The repository already represents most high-value Claude Code workflow features:

- local `llama.cpp` / `llama-server` model integration; the agent loop with tool calls,
  retries, and guardrails;
- file tools (`read_file`/`write_file`/`edit_file`/`apply_patch`) with patch preview and
  hunk-level approval; sandboxed `run_command`; `web_fetch`/`web_search`; `todo_write`;
- deterministic codebase navigation (`glob`/`grep`/`outline`/`find_symbol`/`find_references`);
- layered project memory (`CLAUDE.md` + `/init` + `/memory`), `@file`/`@directory` references;
- skills (`/skills`, `/skill-name`, frontmatter, `context: fork`); a custom subagent registry;
  plan mode; slash commands; plugins;
- an MCP **client** (stdio + HTTP JSON-RPC; tools, resources, prompts);
  hooks (`PreToolUse`/`PostToolUse`/`UserPromptSubmit`/`Stop`);
- read + **write** git tools (`git_status`/`git_diff`/`git_log`/`git_blame`;
  `git_stage`/`git_commit`/`git_branch`, approval-gated);
- sessions/checkpoints, scheduled tasks, image input; retrieval and durable memory;
- RBAC, auth, rate limits, metrics, a full alerting/observability stack, Docker, and CI.

**Status:** the high-value, high-frequency Claude Code **workflow** features are now represented end to
end. Remaining items (see "Build next") are completeness/quality polish, not new high-frequency workflows.

## High-value Claude Code feature coverage (status)

The top-five workflow priorities from earlier iterations are **complete**:

1. Claude-like memory and `/init` — **done** (layered loader, `/memory`, `CLAUDE.local.md`,
   `.claude/rules/*.md`, `@path` imports).
2. Explicit context references — **done** (`@file`/`@directory`, caps, trace display). Only
   MCP-resource references remain (now folded into "Build next" #3).
3. Skills UX parity — **done** (`/skills`, `/skill-name`, bundled skills, frontmatter,
   `context: fork`).
4. Custom subagent registry — **done** (`agents/*.md`, `/agents`, `/agent`, `delegate_agent`).
5. Patch preview and review UX — **done** (`preview_patch`, hunk-level approval, browser diff).

The remaining workflow gaps are the three items in **Build next** above. After those, the
harness is a complete representation of high-value, frequently-used Claude Code workflow
features; further additions would fall below the "high value AND frequent" bar.

## Guidance for AI implementers

When asked to pick the next task, follow this order:

1. Prefer missing high-frequency Claude Code **workflow** features (see "Build next").
2. Prefer features that make the harness easier to learn from.
3. Prefer features that help a weak local model succeed.
4. Prefer deterministic, testable changes.
5. Avoid broad rewrites unless explicitly requested.
6. Keep formatting-only changes separate from behavior changes.
7. Do not continue polishing recently completed areas (especially alerting) unless blocking.
8. Before implementing, check whether the feature already exists in `README.md`, tests, or
   source — and re-derive the gap rather than trusting a "complete" claim.

## Later / lower-priority (after "Build next")

These remain valuable but rank below the workflow gaps and the educational core:

- **Educational completeness:** more trace documents, a richer glossary, "how to add a tool /
  MCP server" tutorials, loop/approval/persistence diagrams, more deterministic eval scenarios.
- **Deterministic tests:** expand fake-model end-to-end scenarios, more bad-model cases,
  golden-trace tests.
- **Production safety** (only if explicitly requested): genuinely sandboxed shell execution,
  stronger MCP isolation/policy, append-only event logs.
- **Multi-user / ops, monetization / packaging:** intentionally last; do not let these displace
  workflow coverage.

## Recently completed

Keep this section short (newest first). Full history lives in
[`docs/HISTORY.md`](docs/HISTORY.md).

- Track B — real-database integration test for durable grants: a new `GrantPersistenceIntegrationTest` boots a real SQLite database on a tempfile, runs the migrations, and drives `WorkspaceRoots` over a real `GrantStore` through a grant → reload (second registry over the same DB) → revoke → TTL-prune cycle, asserting via real SQL (`SELECT COUNT(*)`) that rows persist, reload, disappear on revoke, and are pruned past the TTL. It self-skips cleanly when sqlite-jdbc is absent (mirroring the persistence round-trips and live traces) and cleans up its tempfile. A new opt-in `.github/workflows/integration.yml` (manual dispatch or the `run-integration` PR label) provisions sqlite-jdbc + Node so the real-dependency tests (this IT, the persistence round-trips, the golden/eval traces) run on demand. Documented in a "How durability is verified" note in `docs/MULTI_ROOT.md` (offline doubles vs the real-DB IT) and TESTING.md cases 613-614.

- Track B — durable, TTL-aware grants: `grant_workspace_root`/`revoke_workspace_root` now persist to a new `workspace_grants` table (keyed by session id + path, with access level + granted-at) via a small best-effort `GrantStore`; `WorkspaceRoots` reloads non-expired grants on startup so an approved root survives a restart without re-approval. An optional `agent.multi-root.grant-ttl` (seconds, `0` = unlimited) makes a grant expire — ignored on reload *and* at access time — and prunes it from the store. The default root is global and never persisted; with multi-root disabled the table is never read or written (byte-identical). `GET /admin/roots` now reports each grant's `granted_at` + `remaining_ttl_ms`, and a new `GET /admin/roots/audit` lists the grant/revoke history. Covered by `GrantPersistenceTest` (6 methods, in-memory store double + settable clock) and documented in a "Persistence and lifecycle" subsection of `docs/MULTI_ROOT.md`.

- Track B — per-session grant scoping + capstone walkthrough: granted workspace roots are now scoped to the session that approved them, so one run cannot widen another run's read/write access. `WorkspaceRoots` keeps the default root global but holds additional roots in a per-session map; new session-aware methods (`add`/`remove`/`canRead`/`canWrite`/`roots` taking a sessionId) are the canonical API, and the legacy no-session overloads resolve the session from `SessionContext.sessionId()` (the engine sets it around tool dispatch; `"default"` outside a run) — so `Sandbox`, `PermissionService`, and `ProjectTools` became session-scoped with no signature change, and behavior stays byte-identical when multi-root is disabled. `GET /admin/roots` now reports per-session grants. Proven by a new isolation test plus the `CreateProjectTraceTest` capstone (now asserting cross-session isolation), and documented in a new `docs/PORT_WALKTHROUGH.md` + a "security model" subsection in `docs/MULTI_ROOT.md`.

- Track B PR #3 — transactional `create_project`: a new `ProjectTools.create_project` tool writes a whole project from a manifest (`root` + a list of `{path, content}` files) in one approval-gated step. It is mutating (normal approval flow); `plan_only=true` returns the tree + per-file byte counts without writing; the real write is transactional (staged in a temp dir, moved all-or-nothing, rolled back on a mid-move failure) and refuses to overwrite existing files unless `overwrite=true`. Every target must resolve inside a granted `read_write` root (`WorkspaceRoots.canWrite`); path escapes (`..`/absolute) are rejected; the approval payload is summarized (root, file count, total bytes, tree) in `PermissionService.decideRemote` rather than dumping content. Covered by `ProjectToolsTest` (6 methods) + a `CreateProjectTraceTest` golden trace driving the real engine through grant → plan → write, and documented in `docs/MULTI_ROOT.md` with a worked end-to-end port example. PR #5 (the port/translate workflow) and per-session grant scoping remain.

- Track B PR #2 — approval-gated grant/revoke root tools + `GET /admin/roots`: added `grant_workspace_root` (absolute `path` + `read`/`read_write` `access`) and `revoke_workspace_root` (`path`; never the default), both mutating and in a new `PermissionService.ALWAYS_CONFIRM` set so they are **never auto-approved** — even in `auto` mode or with `autoApprove` set they route to the human approval path (`plan` still records); a `deny` rule can still block them. Ordinary mutating tools (`write_marker`, `git_commit`, …) still auto-approve in `auto`, so existing golden traces are unaffected (verified offline). Grants/revokes are written to the `AuditLog`, report clearly when multi-root is disabled, and reject relative paths. A read-only `GET /admin/roots` lists the registry. Covered by `WorkspaceRootToolsTest` (4 methods) + extended `docs/MULTI_ROOT.md` with a worked TypeScript-port example. PR #3 (transactional `create_project`) is next.

- Track B PR #1 — WorkspaceRoots registry + wiring (multi-root, default-closed): replaced the single workspace root with a `WorkspaceRoots` registry (id + absolute path + `READ`/`READ_WRITE` access; default root always present and `READ_WRITE`), behind `agent.multi-root.enabled` (default false) with optional `agent.multi-root.roots` seeds. `Sandbox`, `PermissionService`, and `RetrievalService` consult the registry via an optional injected field that falls back to the historical single-root logic when absent — so plain construction (and the test fixtures) are unchanged and, with multi-root disabled, behavior is byte-for-byte identical (verified: `canRead`/`canWrite` reduce to `isWithin(defaultRoot)`). A `READ` root permits reads but denies writes; a write inside a granted `READ_WRITE` root is not auto-allowed — it still goes through the normal approval. Covered by `WorkspaceRootsTest` (6 methods) + `docs/MULTI_ROOT.md`. The grant/scaffold tools are deliberately deferred to PR #2 (this PR is the registry + wiring only).

- ROADMAP direction — Track B (multi-root project work): added a new roadmap track defining how the harness can safely perform real-world cross-project tasks (e.g. "create a TypeScript project at B that ports the code at A") behind explicit, scoped, audited user approvals — an honest current-state assessment of why it's blocked today (single workspace root; reads/writes confined; writes outside the root hard-denied before approval; no project-scaffold capability), safety design principles (default-closed, explicit per-path grants with access levels, approval at the destination-root boundary, plan-mode-first manifests, audited + capability-scoped), and six ranked approval-gated PRs (multi-root registry, grant/revoke-root tools, `writesOutsideGrantedRoots`, transactional `create_project`/`write_files`, the port workflow, and mandatory golden traces + docs incl. Windows path handling). Docs-only; no code change yet.

- Hook-executable self-check + broadened workflow-script check + CONTRIBUTING.md: `.githooks/check-scripts.sh` now requires every tracked `.githooks/*` hook to be `100755` (and `git-mark-exec.sh` includes `pre-push`), so a hook can't silently lose its executable bit on an archive import — this also surfaces `.githooks/pre-push`, which had been committed `100644`; `scripts/check-docs.sh`'s workflow-script check now scans all `.github` YAML (incl. composite-action `action.yml`) and more invocation shapes (`cd && sh x.sh`, `./x.sh`); and a top-level `CONTRIBUTING.md` consolidates the local + CI gates into one "before you push" checklist (and is itself validated by the docs checker).

- Pre-push doc-gate hook + workflow-script existence check + underscore slug fix: `.githooks/pre-push` (wired through `scripts/install-hooks.sh`) runs `./run.sh check` and blocks a push if the docs gates fail, catching breakage locally before CI (bypass with `git push --no-verify`); `scripts/check-docs.sh` gains a check that every script a workflow invokes exists (the failure mode that recently broke CI), POSIX with a green baseline; and the slug helper now keeps `_` to match GitHub (removing the underscore divergence), with the self-test's Scenario D and the limitations note updated accordingly.

- Slug-limitations note + divergence guard + more deep links + `./run.sh check`: `check-docs.sh`'s `slug` helper now documents where it diverges from GitHub (consecutive punctuation collapsing to one hyphen; dropped underscores) and `check-docs-selftest.sh` gains a Scenario D that pins exactly that behavior (accepting this script's slug, rejecting the GitHub-style guess) so it can't silently drift; `docs/CONCEPT_MAP.md`'s "proven by golden traces" notes became validated `#anchor` deep links to WORKFLOW_WALKTHROUGH §4 (four living docs now carry it); and `run.sh` gains a `check` umbrella that runs both doc gates with a combined pass/fail.

- check-docs.sh anchor/slug regression guard + validated deep links + round-trip eval cases: `scripts/check-docs-selftest.sh` runs the real checker against fixture docs with tricky headings and hard-coded known-good/known-bad anchors so the GitHub-style slug logic can't silently drift (wired into CI; proven to fail when the slug is perturbed); several by-name `WORKFLOW_WALKTHROUGH.md` §4 references in LEARNING_PATH/TRACE_TOUR/the walkthrough became real validated `#anchor` deep links so the anchor check now guards live content; and `eval/suite.txt` gains two write-then-read round-trip cases that exercise the mutating+reading tool path (no fixture dependency).

- Eval fixture/case coupling test + intra-repo anchor-link validation + `./run.sh help`: `EvalSuiteFileTest` now asserts every `eval/fixtures/...` path named in a suite prompt exists in the repo (offline guard, no model), so a renamed fixture breaks the build; `scripts/check-docs.sh` gains GitHub-style `#anchor` validation for cross-file (`OTHER.md#heading`) and same-file links, staying POSIX with a green baseline; and `run.sh` gains a `help`/`-h` usage path plus an unknown-subcommand error so the `check-docs` entry point is discoverable.

- Relative-link validation in check-docs.sh + `./run.sh check-docs` + harness-behavior eval cases: `scripts/check-docs.sh` gains a fourth check that validates inline Markdown links in the living docs (resolved relative to each linking file; http/anchor links ignored, dirs checked with `-d`) and fails on a missing target, staying POSIX-clean with `WARN_ONLY` support and a green baseline; `run.sh` gains a `check-docs` subcommand so contributors run the same gate CI does with one command; and `eval/suite.txt` grows three harness-behavior cases (read `eval/fixtures/note.txt` / list `eval/fixtures/`) that exercise the agent's tool loop rather than just model recall.

- Docs reference-integrity check + eval suite seed + learning-path capstone: `scripts/check-docs.sh` (dependency-free bash) scans the living docs (README + docs/*, excluding the `HISTORY.md` archive) for backticked test-class, `.java`, and TESTING-case references and fails if any does not resolve — wired into `ci.yml` before the tests (supports `WARN_ONLY=1` for staged rollout); a curated, editable eval suite ships at `eval/suite.txt` (`id | match | expected | prompt`) loaded by a new `EvalHarness.loadCases()`/`parseCases()` (pure, unit-tested by `EvalSuiteFileTest`, falling back to the built-in suite), turning the "agent evaluation" gap from absent to seeded; and `docs/LEARNING_PATH.md` gains a grand-tour capstone wiring in `docs/TRACE_TOUR.md` + `WORKFLOW_WALKTHROUGH.md` §4 with a "trace the tour against the tests" exercise.

- Grand-tour trace doc + trace-test scaffolding consolidation + CHANGELOG pass: `docs/TRACE_TOUR.md` narrates one realistic session chaining an edit→commit with a hook, a subagent delegation, and an MCP tool call — annotated step by step like `TRACE_EDIT.md` and cross-referenced to the golden-trace test (and `WORKFLOW_WALKTHROUGH.md` §4) that proves each step; the five trace tests' repeated `prop`/`schema` helpers and sandbox→git→permissions→engine construction are lifted into the shared `ScriptedAgent` fixture (`prop`/`schema` + a `Harness` factory), behavior unchanged; and `CHANGELOG.md` gains a curated `[Unreleased]` summary of the recent golden-trace/streaming/access-control/delegation work.

- Walkthrough trace-map refresh + subagent failure-propagation trace + learning-path cross-links: `docs/WORKFLOW_WALKTHROUGH.md` gains a §4 "how each branch is proven" table mapping every lifecycle diagram (edit→verify→commit, hooks, MCP, subagent delegation, access-control denial) to the golden-trace test that asserts it, plus a delegation sequence diagram; `SubAgentFailureTraceTest` proves a throwing sub tool surfaces as an `ERROR:` result and a sub tripping its own duplicate guard surfaces its stop string — both without crashing the parent (the shared `ScriptedAgent` fixture's `RoutingScriptedLlama` gains per-agent transcript capture); and `docs/LEARNING_PATH.md`/`docs/CONCEPT_MAP.md` now cross-link the access-control and delegation golden traces (CONCEPT_MAP also gains capability-scoping + rate-limiting rows).

- Subagent hand-off golden trace + multi-server MCP routing trace + doc-drift audit: `SubAgentHandoffTraceTest` drives the real `AgentEngine` and the real `SubAgent` with a scripted model — a parent turn delegates to a named subagent, the subagent runs its own nested turn (its tool call + answer), its result returns into the parent transcript, and the parent answers (the shared `ScriptedAgent` fixture gains a `RoutingScriptedLlama` that scripts two agents on one engine by system-prompt marker); `McpLiveIntegrationTest` gains a two-server routing test asserting `<server>_<tool>` namespacing and per-server `/mcp__<server>__<prompt>` routing; and `docs/WHATS_NOT_INCLUDED.md` was corrected for drift (agent-eval now notes the golden traces + `EvalHarness`/eval-gate; cost/rate-limiting now notes the `cost_ledger`/quotas/`ToolRateLimiter`; sub-agents now note `delegate_agent` + the new trace).

- Capability-scoping golden trace + HISTORY consolidation + true long-lived SSE streaming: `CapabilityScopingTraceTest` drives the real `AgentEngine` through its access-control branches with a scripted model — capability scoping denies an out-of-scope tool with `outside this caller's capability scope` (audited, not executed) while the in-scope tool runs, and `ToolRateLimiter` throttles a tool over its per-tenant limit with the `RATE_LIMITED` message (verified 8/8 offline, reusing the shared `ScriptedAgent` fixture via a new `buildEngine` overload); older `Recently completed` entries were swept into `docs/HISTORY.md` to keep the roadmap focused; and the HTTP MCP transport now consumes an **unbounded** server-push `text/event-stream` via incremental line reads (`ofInputStream` + `readSseResponse`), returning as soon as the JSON-RPC response event arrives and closing the stream — keep-alive/interim events are skipped (`McpManager.sseDataJson`/`isJsonRpcResponse`), covered by a keep-alive `HttpServer` integration test + pure helper unit tests.

- Recovery golden traces + shared scripted-agent fixture + node in CI: `RecoveryTraceTest` drives the real `AgentEngine` through its non-happy-path branches — a mutation denied in PLAN mode (`RECORD_PLAN`, nothing executed), an invalid-args call that becomes corrective feedback then a successful retry, and a repeated identical mutating call that trips the duplicate-call guard (execution capped, run stopped) — asserting the permission decision, the validation/guard messages, and the final answer for each; a shared `ScriptedAgent` test fixture (scripted `LlamaClient` + real-engine `buildEngine` + decision-recording permissions) now backs `GoldenTraceWorkflowTest`, `RecoveryTraceTest`, and `FakeModelHarnessTest` (the last upgraded to drive the real engine), removing the parallel harness; and `ci.yml` installs Node so the stdio MCP integration tests run in CI instead of self-skipping.

- Golden-trace workflow test + streaming SSE MCP + learning-path/workshop modules: `GoldenTraceWorkflowTest` drives the real `AgentEngine` loop with a scripted (model-free) `LlamaClient` through edit→stage→commit, asserting tool dispatch, the permission decision, hook firing, and the git-verified edit-trust summary in one trace (plus an MCP-prompt-slash-command trace); the HTTP MCP transport now consumes a terminating multi-event `text/event-stream`, skipping interim progress events to pick the JSON-RPC response (`McpManager.jsonFromHttpBody`), covered by a streaming-SSE integration test + a pure selector test; and `docs/WORKFLOW_WALKTHROUGH.md` is wired into `docs/LEARNING_PATH.md` (Module 13.5) and `docs/WORKSHOP.md` (Lab 6) with the new tests as checkpoints.

- Live MCP integration test + git-commit approval-flow test + workflow walkthrough doc: `McpLiveIntegrationTest` connects `McpManager` to a stub server over both transports (a node child process over stdio + a JDK `HttpServer` over HTTP) and asserts tools/resources/prompts discovery plus `read_resource` and the `/mcp__server__prompt` slash command returning rendered content (stdio half self-skips without node); `GitCommitApprovalFlowTest` drives a real repo through stage → approval → commit, asserting the staged diff is attached to the approval payload; and `docs/WORKFLOW_WALKTHROUGH.md` documents the edit→verify→commit loop, the six-event hook lifecycle, and the MCP lifecycle with mermaid diagrams. A small package-private `McpManager.connect()` test seam was added.

_Older entries have been moved to [`docs/HISTORY.md`](docs/HISTORY.md)._
