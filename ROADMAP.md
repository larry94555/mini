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

1. **More eval/test depth** — golden-trace eval scenarios that drive the git/hook/MCP paths through the
   full agent loop (not just unit/integration seams), and a live stdio+HTTP test matrix on CI runners that
   have `node`.
2. **SSE *streaming* MCP** — the HTTP transport handles single-response JSON/SSE; long-lived
   server-initiated streams are still out of scope.
3. **Hook/Notification breadth** — additional `Notification` trigger points (e.g. on long-running tools)
   if real usage shows a need.

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

- Live MCP integration test + git-commit approval-flow test + workflow walkthrough doc: `McpLiveIntegrationTest` connects `McpManager` to a stub server over both transports (a node child process over stdio + a JDK `HttpServer` over HTTP) and asserts tools/resources/prompts discovery plus `read_resource` and the `/mcp__server__prompt` slash command returning rendered content (stdio half self-skips without node); `GitCommitApprovalFlowTest` drives a real repo through stage → approval → commit, asserting the staged diff is attached to the approval payload; and `docs/WORKFLOW_WALKTHROUGH.md` documents the edit→verify→commit loop, the six-event hook lifecycle, and the MCP lifecycle with mermaid diagrams. A small package-private `McpManager.connect()` test seam was added.

- MCP prompts as slash commands + SessionStart/Notification hooks + git_push & approval-diff: discovered MCP prompts are now invokable as `/mcp__<server>__<name>` slash commands (listed in `/help`, `key=value` args parsed, rendered prompt becomes the turn input); `HookService` gains `sessionStart` (first-turn context injection) and `notification` (fires when the agent requests approval) events; and a capability-gated, off-by-default `git_push` tool (`git.allow-push`) plus the staged diff (`git diff --cached --stat`) surfaced in the commit approval prompt complete the git workflow.

- Git write workflow + hook breadth + MCP resources/prompts/HTTP transport: new mutating `git_stage`/`git_commit`/`git_branch` tools (approval-gated, message via the `commit-message` skill) complete the edit→verify→commit loop; `HookService` gains `userPromptSubmit` (block-or-inject) and `stop` (append) turn-level events alongside the existing tool hooks; and the MCP client discovers `resources/list`+`resources/read` (a `<server>_read_resource` tool) and `prompts/list`+`prompts/get` (per-prompt tools) and can reach servers over an HTTP transport (`mcp.json` `transport:"http"`, plain-JSON or single-event SSE) as well as stdio.

- Live posture row + structured-payload toggle + posture Prometheus gauges: the overview current-posture row is rebuilt from overview.json on each poll (no longer stale until reload); alerts.slo-digest-structured (default true) lets receivers opt out of the digest object in the webhook payload; and the snapshot posture is exported as imini_alerts_digest_window_ratio/_delivery_ratio/_worst_route_ratio/_worst_success_route_ratio gauges.

- Overview posture row + structured webhook payload + report format choice: the digest section opens with a compact current-posture row (window/delivery vs targets, worst routes, mute/catch-up) from the live sloDigest() snapshot (now also in stats()); the scheduled webhook digest payload includes the snapshot as a structured digest object alongside the back-compat text field; and the report link/download lets the reviewer pick JSON or CSV.

- Report bundle snapshot + download link + picker validation feedback: the digest-report bundle now includes the latest sloDigest() snapshot (JSON snapshot field + a # snapshot CSV section); the overview gains a Download report bundle link (digest-report CSV for the current range); and the date-picker surfaces the server-side range validation error inline (without pinning the view) instead of failing silently.

- Combined digest report bundle + copy-link + range validation: GET /admin/alerts/digest-report returns mute state + history + audit for a date range (JSON or one sectioned CSV); the overview gains a Copy report link button that copies the bundle URL for the current range; and the history/audit/report endpoints reject malformed or inverted date ranges with HTTP 400 + a clear message (pure rangeError).

- Digest history CSV + quick-range buttons + CSV download links: GET /admin/alerts/slo-digest/history gains ?format=csv (parity with digest-audit); the overview date-picker adds 24h/7d/30d quick-range buttons that set the window and apply; and History/Audit CSV download links export the current from/to range.

- Marker-faithful live trends + overview date-picker + window-ratio target line: the delivery-success trend now redraws its mute (square) / catch-up (diamond) markers on every auto-refresh via a JS trendSVG (previously markers were server-render only); the digest section gains a from/to date-picker that fetches the ranged history/audit and pauses the live poll while pinned; and the window-ratio trend overlays the SLO target reference line.

- Full digest trends + date-range filtering + mute/catch-up trend markers: the overview charts three trends across recent digests (delivery-success, window ratio, budget remaining), with the delivery-success trend annotated by mute (square) and catch-up (diamond) markers; the digest history and digest-audit endpoints accept ?from/?to/?days date-range filters (audit still supports ?format=csv).

- Structured digest history + trend chart + digest-audit CSV + catch-up audit: digest history rows are now versioned (v2) and carry structured metrics (window ratio, delivery-success, budget) so the overview charts a delivery-success trend across recent digests; the mute audit trail exports as CSV (GET /admin/alerts/digest-audit?format=csv); and a mute-expiry catch-up send records an alert_digest_catchup audit event. Legacy 4-field history rows still parse.


_…older entries moved to [`docs/HISTORY.md`](docs/HISTORY.md)._
