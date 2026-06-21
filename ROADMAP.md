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

If none of these clears the "high value AND frequent" bar for your goals, the workflow representation is
**done** — prefer educational depth (docs, diagrams, eval scenarios) over inventing new surface.

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
