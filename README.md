# imini — a low-end Claude Code learning harness

`imini` is a minimal but real agent harness over a local `llama-server` running a small local model such as `Qwen/Qwen2.5-3B-Instruct`.

The purpose of this repository is educational: it makes the boundary between **the model** and **the harness** concrete.

- The **model** reasons and emits text or tool calls.
- The **harness** owns tools, state, permissions, persistence, safety, verification, and user experience.

No cloud API key is required.

## Start here

- First-time install: [`INSTALL.md`](INSTALL.md)
- Guided learning path: [`docs/LEARNING_PATH.md`](docs/LEARNING_PATH.md)
- End-to-end edit trace: [`docs/TRACE_EDIT.md`](docs/TRACE_EDIT.md)
- Claude Code concept map: [`docs/CONCEPT_MAP.md`](docs/CONCEPT_MAP.md)
- Feature tests and manual scenarios: [`TESTING.md`](TESTING.md)
- Future work: [`ROADMAP.md`](ROADMAP.md)

## What this project teaches

`imini` demonstrates the major building blocks of a Claude Code-style harness:

- local model serving through `llama-server`,
- an agent loop,
- tool schemas and tool execution,
- read-only and mutating tool separation,
- permission gates and plan mode,
- sessions and checkpoints,
- context compaction and project memory,
- deterministic codebase navigation,
- git-aware verification,
- retrieval over workspace files,
- MCP as an external tool boundary,
- hooks and slash commands,
- prompt-injection fencing,
- streaming output,
- remote approvals,
- auth/rate limiting,
- metrics and structured logs,
- and Docker/CI support.

## Capabilities

| Area | Capability |
|---|---|
| Model serving | Config-driven `llama-server` launcher, model profiles, GPU/thread knobs, parallel slots, watchdog |
| Agent loop | Think -> act -> observe loop with streaming, deadlines, duplicate-call guards, and interrupts |
| File tools | `read_file`, `view`, `list_dir`, `write_file`, `edit_file`, `apply_patch` |
| Codebase navigation | `glob`, `grep`, `repo_tree`, `read_many`, `outline`, `find_symbol`, `find_references` |
| Git awareness | `git_status`, `git_diff`, `git_log`, `git_blame` |
| Safety | Permission modes, workspace confinement, command screening, optional container command wrapper |
| Planning | `todo_write`, plan mode, **plan-then-execute** orchestrator with retry, re-planning, step verification (+ auto-suggested checks), persist/resume, and per-session history, coding profile guidance |
| Edit trust | auto `git status`/`git diff --stat` verification + structured coding report appended to coding answers |
| State | SQLite-backed sessions, checkpoints, memory index |
| Retrieval | `index_workspace` and `search_memory` with lexical scoring and symbol boost |
| Skills | reusable `SKILL.md` bundles: auto-indexed, `load_skill`/`save_skill`, read-only remote repos (pinnable) via `refresh_skills`, a provenance registry (`search_skills`/`install_skill`, hash-verified), per-skill enable/disable (persisted global + per-session overrides), and member skill proposals (admin-reviewed, with a "my requests" view) |
| Extensibility | MCP client, research sub-agent, hooks, slash commands |
| UI/API | Blocking and streaming HTTP endpoints, web UI (live plan w/ per-step edits, plan-history + report viewer, session sharing, integrity-checked export/import w/ preview + skill overrides + sharing, skills toggles + proposals, filterable activity log w/ CSV/JSON export, per-session activity), remote approvals |
| Ops | API-key auth, rate limiting, per-user RBAC, per-resource ownership with session sharing + ownership transfer, audit log (incl. tool-call level), `/metrics`, structured logging, Docker, CI |

## File map

| File | Role |
|---|---|
| `MiniAgentApplication.java` | Spring Boot entry point |
| `LlamaServerManager.java` | Starts and supervises `llama-server` |
| `LlamaClient.java` | Model calls, streaming calls, summary calls, token counting |
| `AgentLoop.java` | Prepares prompts, sessions, project context, slash commands, tool registry; `runPlan` orchestrator |
| `Planner.java` | Plan parsing + step sequencing for plan-then-execute (pure, testable) |
| `CheckLibrary.java` | Suggests a verification command from project type + step text (pure) |
| `CheckSuggester.java` | Detects the build system and suggests a step check |
| `ToolCall.java` | Pure summary/outcome formatting for a recorded tool call |
| `RunRecorder.java` | Records mutating tool calls to the audit log + per-step transcript; tracks edited paths |
| `GitInspector.java` | Read-only `git status`/`git diff --stat` over the workspace |
| `EditSummary.java` | Pure parsing/formatting of git output into an edit-trust block |
| `CodingReport.java` | Pure parse/merge/render of the structured final-answer coding report |
| `PlanStore.java` | Persists the per-session plan (goal + checklist) for inspect/resume |
| `PlanHistory.java` | Archives completed plans (steps + transcript + report) as a per-session history |
| `AgentEngine.java` | Main think -> act -> observe loop |
| `ToolRegistry.java` | Builds the available tool set |
| `Tool.java` | Tool definition: name, description, schema, mutating flag, untrusted flag, executor |
| `BuiltinTools.java` | File, shell, web, patch, and todo tools |
| `CodebaseTools.java` | Deterministic repo navigation, git tools, and symbol search (defs + refs) |
| `SymbolRefs.java` | Pure whole-identifier reference matching + rendering for `find_references` |
| `PermissionService.java` | Permission modes, allow/deny rules, remembered decisions, plan mode, write confinement |
| `Sandbox.java` | Command screening, read confinement, optional container execution wrapper |
| `CheckpointStore.java` | Snapshot-before-edit and rewind |
| `SessionStore.java` | Session history persistence |
| `SessionBundle.java` | Pure build/validate/extract/migrate of a portable session export bundle |
| `Database.java` | SQLite connection and migrations |
| `ContextManager.java` | Token counting, compaction, tool-output trimming, durable memory note |
| `RetrievalService.java` | Workspace indexing and memory search |
| `SkillLibrary.java` | Pure parse/index/select/format/merge for skills + repo spec parsing |
| `SkillManifest.java` | Pure skill-registry manifest: parse, lexical search, SHA-256 verify |
| `SkillRequests.java` | Queue of member skill proposals awaiting admin review (DB-backed) |
| `SkillService.java` | Loads local + remote skills; index; `load_skill`/`save_skill`/`refresh_skills`/`search_skills`/`install_skill`; `/skills` + `/<name>` |
| `SkillInvocation.java` | Pure `/skills` listing + `/<skill-name>` parsing and `$ARGUMENTS` substitution |
| `ProjectContext.java` | Loads layered memory files (`CLAUDE.md`, `.claude/rules/*.md`, ...) into the system prompt; backs `/memory` |
| `MemoryLoader.java` | Pure memory helpers: candidate load order + `@path` import expansion |
| `RepoScan.java` / `InitDraft.java` | Pure `/init` logic: build-system/language detection + `CLAUDE.md` draft |
| `InitService.java` | Scans the repo and creates/drafts `CLAUDE.md`; backs `/init` |
| `ContextRefs.java` | Pure `@file`/`@directory` reference parsing + context-block assembly |
| `ContextRefService.java` | Resolves `@path` refs (workspace-confined, capped) and inlines them |
| `TodoStore.java` | Per-session task checklists |
| `InterruptService.java` | Per-session interrupt and steering |
| `Approvals.java` | Pending remote approval registry |
| `HookService.java` | Pre/post tool shell hooks from `hooks.json` |
| `SlashCommands.java` | Prompt templates from `commands/*.md` |
| `SubAgent.java` | Runs a delegated sub-agent loop (research, registry agent, or forked skill) in isolation |
| `AgentLibrary.java` / `AgentRegistry.java` | Custom subagents: parsing + built-in/`agents/*.md` catalog |
| `DiffRender.java` | Pure unified-diff rendering for patch previews |
| `PreviewStore.java` | In-memory staged patch previews (per session), each a list of hunks |
| `PreviewSelect.java` | Pure hunk-selection parsing (`0,2`, `1-3`, `all`) for hunk-level approval |
| `McpManager.java` | Optional MCP stdio client |
| `AgentController.java` | HTTP endpoints |
| `RunService.java` | Slot-bounded job queue for concurrent runs |
| `RunSink.java` | Output abstraction for console and SSE streaming |
| `Sse.java` | SSE event framing/parsing helpers |
| `AuthFilter.java` | API-key auth, request attribution, and RBAC gating |
| `Rbac.java` / `Principal.java` / `RequestContext.java` | role policy and per-request caller identity |
| `Ownership.java` | per-resource access policy (owner / admin / unowned) + `canRead` for shared sessions |
| `AuditLog.java` | append-only audit trail of privileged actions |
| `RateLimiter.java` | Fixed-window per-key rate limiter |
| `Metrics.java` | In-process metrics snapshot and run logs |
| `static/index.html` | Browser UI |
| `Dockerfile` | Container image for the app |
| `docker-compose.yml` | One-command local app + llama server setup |
| `.github/workflows/ci.yml` | CI tests and Docker build |

## Run on Windows

```bat
run.bat
```

Then try:

```bat
ask.bat "Say hello in one sentence."
chat.bat work1 "Remember that the codename is Bluefin."
stream.bat work1 "Use repo_tree to inspect the project, then summarize what kind of app this is."
```

The app runs on:

```text
http://localhost:8080
```

The local `llama-server` normally runs on:

```text
http://localhost:8081
```

## Common helper scripts

| Script | Purpose |
|---|---|
| `run.bat` | Start the app and local model server |
| `ask.bat "question"` | One-shot prompt |
| `chat.bat SESSION "message"` | Multi-turn session prompt |
| `stream.bat SESSION "message"` | Streaming session prompt |
| `plan.bat "request"` | Plan mode: record proposed mutations without running them |
| `rewind.bat` | Rewind the last checkpointed edit |
| `interrupt.bat SESSION` | Stop a running session |
| `steer.bat SESSION "guidance"` | Inject guidance into a running session |
| `runs.bat` | Show active and queued runs |

## HTTP endpoints

| Method and path | Purpose |
|---|---|
| `POST /ask` | One-shot prompt, blocking response (add `"plan":true` to plan-then-execute) |
| `POST /chat` | Multi-turn session prompt, blocking (`"plan":true` to plan; `"resume":true` to resume) |
| `POST /ask/stream` | One-shot prompt over SSE |
| `POST /chat/stream` | Session prompt over SSE |
| `GET /sessions` | List sessions |
| `GET /session?id=` | Read one session |
| `GET /shares?sessionId=` | Who can see a session: owner + shared readers (any reader) |
| `POST /share` | `{sessionId,user}` grant another user read access (owner/admin) |
| `POST /unshare` | `{sessionId,user}` revoke read access (owner/admin) |
| `POST /transfer` | `{sessionId,to}` transfer ownership; prior owner keeps read (owner/admin) |
| `GET /plans?sessionId=` | List the session's archived plan history (newest first) |
| `GET /session/export?sessionId=` | Download a portable bundle (conversation + plan history + todos) |
| `POST /session/import?mode=&target=&strict=&restoreSharing=` | Import a bundle; optionally restore its reader list |
| `POST /session/import/preview?mode=&target=` | Project an import's before/incoming/after counts (no apply) |
| `GET /skills` | List loaded skills (name, description, enabled) |
| `POST /skills/toggle` | `{name,enabled}` enable/disable a skill (admin) |
| `POST /skills/refresh` | Re-pull remote skill repos and reload (admin) |
| `GET /skills?sessionId=` | List skills with effective state for a session (+global/override) |
| `POST /skills/session-toggle` | `{sessionId,name,enabled}` per-session override (session access) |
| `POST /skills/session-reset` | `{sessionId,name}` clear a per-session override (session access) |
| `POST /skills/request` | `{name,description,body}` propose a skill (any member) |
| `GET /skills/requests?status=` | List skill proposals (admin) |
| `POST /skills/requests/resolve` | `{id,approve}` approve (save) or reject a proposal (admin) |
| `GET /skills/requests/mine` | The caller's own proposals and their status |
| `POST /skills/requests/withdraw` | `{id}` withdraw your own pending proposal |
| `POST /skills/requests/update` | `{id,name,description,body}` edit your own pending proposal |
| `GET /plan?sessionId=` | Read the current saved plan: goal + steps + statuses + per-step tool transcript |
| `GET /plan?sessionId=&n=` | Read archived plan `n` from history: goal + steps + tools + coding report |
| `GET /todos?sessionId=` | Read session todos |
| `GET /runs` | Show concurrency status |
| `POST /interrupt` | Stop one session's active run |
| `POST /steer` | Add steering guidance to one session's active run |
| `GET /approvals?sessionId=` | List pending approvals |
| `POST /approve` | Resolve a pending approval |
| `POST /rewind` | Rewind a session's last checkpoint |
| `GET /checkpoints?sessionId=` | List session checkpoints |
| `POST /index` | Build or rebuild retrieval index |
| `GET /memory?q=&k=` | Search indexed workspace memory (retrieval) |
| `GET /memory/files` | Project-memory diagnostics: which memory files loaded, in order, and why |
| `POST /init?write=&overwrite=` | Scan the repo and draft `CLAUDE.md` (optionally write it) |
| `GET /preview?sessionId=` | Staged patch previews for the browser diff viewer |
| `POST /preview/apply?sessionId=&id=` | Apply a staged preview (re-validates + snapshots) |
| `POST /preview/discard?sessionId=&id=` | Drop a staged preview |
| `GET /health` | Health check |
| `GET /me` | Current caller identity (`user`, `role`) |
| `GET /metrics` | Metrics snapshot (admin only) |
| `GET /audit?user=&action=&target=&offset=&limit=` | Audit trail of privileged actions, filterable + paged (admin only) |
| `GET /audit/export?format=csv\|json&since=&until=&...` | Download the (filtered, windowed) audit trail (admin) |
| `GET /session/activity?sessionId=&offset=&limit=` | This session's events (anyone with session access) |
| `GET /` | Browser UI |

## Plan-driven execution

For a multi-step goal, a small model often wanders. Plan mode makes it work like a checklist: draft a
short plan, turn it into the session's todo list, then do one step at a time before a final synthesis.

Enable it per run by adding `"plan": true` to the request body of `/ask`, `/chat`, `/ask/stream`, or
`/chat/stream` (in the web UI, tick **plan&execute** next to the mode selector):

```
curl -X POST localhost:8080/ask -H "Content-Type: application/json" \
  -d '{"question":"Add a /version endpoint and document it","mode":"auto","plan":true}'
```

What happens:

1. the agent drafts a numbered plan (read-only `PLAN` mode, no tools) and it is parsed into steps;
2. the steps become the session's todos and a live `plan` checklist in the UI (also at `GET /todos`);
3. each step runs as a focused turn with the full toolset and the requested permission mode, told to do
   only that step and end its report with a `STEP_STATUS: done` or `STEP_STATUS: failed <reason>` line;
4. **verification:** if a step's report includes a `CHECK: <shell command>` line, the harness runs it
   (exit code 0 = success, through the same `Sandbox` screening as `run_command`) and the result is
   AUTHORITATIVE -- it overrides the model's self-report, so a step that *claims* success but does not
   actually work is caught;
5. **failure recovery:** a step that fails (a failed check, a `STEP_STATUS: failed`, or an `ERROR`
   result) is retried up to `agent.plan.step-retries` times (default 1, prior failure fed back in); if
   it still fails it is marked `[!]` in the todos and -- up to `agent.plan.max-replans` times for the
   whole run (default 2) -- the model is asked to revise the REMAINING plan, whose new steps are run;
6. a final synthesis turn produces the answer for the whole goal.

If no plan can be parsed, it falls back to a single normal run. The step count is capped
(`Planner.MAX_STEPS`, 12). The classification, retry, and re-plan logic is pure and unit-tested with
fake runners.

**Live plan panel.** On the streaming endpoints the run emits a structured `plan` SSE event
(`{"steps":[{"text","status","tools"}]}`) every time the checklist changes -- when steps are drafted,
start, complete, fail, or get re-planned. The web UI renders this as a live checklist above each
answer, with `[ ]` pending, `[~]` in progress, `[x]` done, and `[!]` failed, so you can watch the agent
work the plan in real time (no polling). **Each step also lists the mutating tool calls it made** --
e.g. `· write_file src/App.java [ok]`, `· run_command $ mvn -q test [error]` (failures in red) --
straight from the per-step transcript, so you see not just *that* a step ran but *what it did*.
Non-streaming sinks fall back to logging the event; the checklist is also at `GET /todos` and the full
plan + transcript at `GET /plan`.

**Step verification.** Self-reported status is best-effort, so a step may declare a concrete check.
When a step's report contains a `CHECK: <command>` line, the harness runs that command and uses its
exit code (0 = pass) as the real outcome -- overriding the self-report and feeding the retry/re-plan
loop. Checks run through the same `Sandbox` command screening as `run_command`, in the workspace root,
with a `agent.plan.check-timeout-seconds` timeout (default 20). Turn it off with
`agent.plan.verify=false`. Good checks are cheap and decisive, e.g. `CHECK: test -f build/out.jar`,
`CHECK: grep -q "/version" src/Main.java`, or `CHECK: mvn -q -DskipTests compile`.

**Suggested checks.** Weak models often forget to add a `CHECK:` line. When a step has none, the
harness can suggest one from the detected build system and the step text and run it anyway:
`mvn -q -DskipTests compile` for a Maven repo (`mvn -q test` if the step is about tests), the
equivalents for Gradle/Node/Python, or `test -f <file>` when the step names a file to create. The
model's own `CHECK:` always wins; suggestions only fill the gap, only when `agent.plan.verify=true`,
and can be turned off with `agent.plan.suggest-checks=false`. Suggested checks show up in the log as
`check passed (suggested)` / `check FAILED (suggested)`.

> Honest scope: suggestions are heuristics, not guarantees -- a suggested compile/test can fail for
> reasons unrelated to the step (and trigger extra retries/re-plans), and `test -f` only confirms a
> file exists, not that its contents are correct. Disable with `agent.plan.suggest-checks=false` if a
> project's build is slow or noisy.

**Intermediate diff feedback.** After each step that changes files, the executor appends a short
`[edits this step]` note -- the files that step touched plus a `git diff --stat` -- to the running
context. Later steps and the final synthesis see it, so the model can react to unexpected diffs mid-plan
(e.g. notice it edited the wrong file) instead of only learning what changed at the end.

By default (`agent.plan.step-diff.snapshot=true`) the note reports each step's **exact delta**: the
executor snapshots the working tree before and after the step -- staging into a throwaway git index
(`GIT_INDEX_FILE`) so your real index and working tree are untouched -- and diffs the two snapshots.
This attributes a file *re-edited* in a later step to that step, and reports a per-step (`diff this
step:`) rather than cumulative stat. Set `agent.plan.step-diff.snapshot=false` to fall back to the
lighter "newly-touched paths + cumulative `diff so far:`" derived from the tool recorder (no snapshot);
it also degrades to this automatically outside a git workspace. Turn the whole note off with
`agent.plan.step-diff=false`. In the **web UI** the note appears as a blue `[edits]` line under the
step in both the live plan panel and the plan-history viewer (alongside that step's tool calls).

**Plan history.** Each time a plan run finishes, a snapshot is archived per session -- the goal, the
final checklist (steps + statuses), the per-step tool transcript, and the coding report. So a session
builds up an inspectable record of past goals and what was done, not just the latest plan:

```
curl "localhost:8080/plans?sessionId=proj"        # list: [{seq, goal, stepCount, summary, createdAt}]
curl "localhost:8080/plan?sessionId=proj&n=2"     # fetch archived plan #2 (steps+tools+report)
```

`GET /plans` lists the history newest-first (each with a `summary` like `5 steps: 4 done, 1 failed`);
`GET /plan?n=<seq>` returns that archived plan in full, while `GET /plan` (no `n`) still returns the
current live plan. The last `agent.plan.history-max` plans are kept per session (default 20; 0 =
unlimited). All are ownership-scoped.

In the **web UI**, the *Plan history* card lists the current session's past plans; click one to expand
its step checklist (with per-step tool calls) and its coding report inline. The list refreshes when a
run finishes and when you switch sessions (via the session selector), and there is a *refresh* link.
Because it uses the same ownership/shared-read scope as the endpoints, a session shared with you shows
its history here too.

**Persistence & resume.** The plan (goal + every step's status) is saved to a `plans` table on each
change, so it survives a restart and can be inspected at `GET /plan?sessionId=` (ownership-scoped). If a
run is interrupted (a `Stop`, a crash, a closed tab), resume it: send `"resume": true` (with
`"plan": true`) on any run endpoint, or click **Resume plan** in the web UI. Resume reloads the saved
checklist and continues from the FIRST not-completed step -- completed steps are left as-is, while
`failed`/`in_progress`/`pending` ones are (re)attempted. Resuming an already-complete plan is a no-op.

> Honest scope: one saved plan per session (a new plan-run overwrites it). Resume re-runs from the
> first unfinished step; it does not replay the outputs of already-completed steps into the new run's
> context (the model still sees the full plan and which step is current). Persistence is the checklist,
> not a full execution snapshot.

> Honest scope: steps run sequentially (no parallelism). Failure detection is best-effort -- the
> `STEP_STATUS` line the model is asked to emit, falling back to the `ERROR`-prefix convention -- so a
> step that silently does the wrong thing can still read as done. Retries and re-plans are bounded.
> Plan runs are goal-oriented one-shots and do not append to the conversational `/chat` history.

## Edit trust

A coding answer is easy to overstate, so after any run that changed files `imini` appends a
**git-verified summary** of the edits to the final answer:

```
---
Edits (verified with git):
- changed files: src/App.java (M), src/New.java (A)
- git diff --stat: 2 files changed, 12 insertions(+), 2 deletions(-)
```

It runs read-only `git status --porcelain` and `git diff --stat` over the workspace root (the same way
the `git_*` tools shell out), so the model cannot misrepresent what it touched. In plan mode the
synthesis step is also asked to note changed files, how it verified them, and any risks or tests not
run. For streaming clients the block is streamed into the answer body; for blocking calls it is part of
the returned answer; either way a one-line `edits: …` shows in the activity log.

**Structured coding report.** With `agent.coding-report=true` (default), a run that changed files ends
with a consistent report instead of the bare git block:

```
---
Coding report:
- Summary: Added a /version endpoint and documented it
- Changed files: src/App.java, README.md
- Commands run: mvn -q -DskipTests compile
- Verification: compiled cleanly; hit /version returns the build number
- Tests not run: integration tests
- Risks:
  - no auth on the new endpoint
- git diff --stat: 2 files changed, 14 insertions(+)
```

The **changed files**, **commands run**, and **diff stat** are factual -- taken from git and the tool
recorder, so the model cannot misstate them. The **summary**, **verification**, **tests not run**, and
**risks** come from a small dedicated JSON model call after the answer (kept out of the streamed body),
and degrade to `(not reported)` if that call fails. This works for `/ask`, `/chat`, and plan runs.

**Schema enforcement.** With `agent.coding-report.enforce=true` (default) the report is checked for
gaps a complete coding answer should not have -- no verification for changed files, no risks reported,
or no summary -- and any gaps are flagged inline and logged:

```
- [!] Report gaps: verification not reported for 2 changed file(s); risks not reported
```

The flag is appended to the report (so it travels into the answer and into plan history) and a
`coding report: N gap(s) - …` line is logged. A verification value of `none`/`n/a`/`nothing` counts as
missing. It is a visible nudge, not a hard gate -- the answer is never blocked. Disable the check with
`agent.coding-report.enforce=false`.

Turn the report off with `agent.coding-report=false` (falls back to the plain edit-trust block), or
disable edit verification entirely with `agent.verify-edits=false`.

> Honest scope: the report is appended only when the run changed files; the factual fields reflect the
> git working tree (not strictly this run's diff); the soft fields are model-authored and best-effort
> (one extra short model call), and the report is descriptive, not a gate.

> Honest scope: the summary reflects the workspace's git state (working tree), not strictly the diff of
> this one run; when the workspace is not a git repo (or git is missing) it falls back to listing the
> files the run's tools touched and notes that no tracked diff was available. It is descriptive, not a
> gate -- it does not block answers.

## Permission modes

| Mode | Behavior |
|---|---|
| `ask` | Prompt before mutating tools. This is the default. |
| `auto` | Approve mutating tools automatically, while still applying policy and path confinement. |
| `plan` | Record mutating actions as a plan. Do not execute them. |

## Codebase navigation workflow

For coding tasks, the preferred flow is:

```text
orient -> locate -> read -> edit -> verify -> summarize
```

Useful tools:

- `repo_tree` to understand shape,
- `glob` to find files by name,
- `grep` to find text or usages,
- `outline` to inspect declarations in one file,
- `find_symbol` to find a symbol's **declaration** across the repo,
- `find_references` to find every **usage** of an identifier (declaration sites marked `[def]`),
- `read_many` to compare related files,
- `git_status` and `git_diff` to verify changes.

**Go-to-definition / find-references.** Two complementary tools give LSP-style code intelligence without
an external language server:

- **`find_symbol`** (`{name, dir?, glob?}`) -- where a symbol is *defined*: `path:line: kind name`.
- **`find_references`** (`{name, dir?, glob?}`) -- every *usage* of an identifier across the repo as
  `path:line: text`, with declaration sites marked `[def]`. Matching is whole-identifier, so searching
  `user` won't match `username` or `user_id`.

So `find_symbol fetchUser` jumps to the definition, and `find_references fetchUser` lists every call
site (and the definition, flagged) -- handy before a rename or to gauge a change's blast radius.

> Honest scope: this is heuristic, regex-based identifier matching, not a typed resolver -- it sees
> names, not scopes or types, so it can over-match a name reused in an unrelated file (e.g. a local
> variable and an unrelated method that share a name). Declaration detection reuses the same
> per-language symbol heuristics as `find_symbol`/`outline`. Results are capped (`max_results`,
> default 50).

## Persistence and retrieval

`imini` uses SQLite for durable sessions, checkpoints, and retrieval index data. If SQLite cannot be opened, the app falls back to in-memory behavior so the learning flow still works.

Retrieval is lexical by default and works well for code identifiers. Optional embedding-based retrieval can be enabled if you run a model/server that supports embeddings.

## Project memory

Like Claude Code's `CLAUDE.md`, `imini` loads project memory files and appends them to the system prompt
so the agent follows your conventions, commands, and preferences. Memory is now **layered**: several
files are loaded (in a fixed order) and concatenated, and a memory file can pull in another with an
`@path` import.

Load order (each loaded if present, relative to the workspace root):

1. `.claude/CLAUDE.md`
2. `CLAUDE.md`
3. `IMINI.md`
4. `AGENTS.md`
5. `.claude/rules/*.md` (sorted by filename)
6. `CLAUDE.local.md` (last, so local overrides win)

**Imports.** A line whose first token is `@<path>` inlines that file (resolved relative to the importing
file, confined to the workspace). Imports are recursive but depth-, size-, and cycle-guarded; write a
literal leading at-sign as `@@`. Caps: `memory.import-max-depth` (default 3), `memory.max-file-kb`
(default 64).

**Diagnostics (`/memory`).** Type `/memory` in chat (or call `GET /memory/files`) to see exactly which
memory files loaded, in what order, their size, and why -- direct, imported (shown nested), or skipped
(missing, cyclic, past the depth cap, or over the size cap). For example:

```
Loaded project memory (3 entries, 412 bytes):
  - CLAUDE.md  [loaded (project memory)] 380B
    - import .claude/conventions.md  [imported via @] 32B
  - CLAUDE.local.md  [loaded (local override)] 32B
```

> Note: this replaces the earlier single-file behavior (only the first of `IMINI.md`/`CLAUDE.md`/
> `AGENTS.md`). All present layered files now load; a repo with just one of them behaves as before.
> `GET /memory/files` is the memory-file view; `GET /memory?q=` remains the separate retrieval search.

### Bootstrapping memory with `/init`

Don't have a `CLAUDE.md` yet? Type `/init` in chat. `imini` scans the repository -- detecting the build
system, primary languages, and top-level layout -- and drafts a `CLAUDE.md` scaffold with sections for
overview, build/test commands, layout, conventions, and agent notes. The scan is **deterministic** (no
model call), so it works reliably even with a weak local model.

- If `CLAUDE.md` does **not** exist, `/init` writes it and reports what it found; it is immediately
  picked up as project memory (confirm with `/memory`). Fill in the Conventions/Notes sections.
- If `CLAUDE.md` **already** exists, `/init` never overwrites it: it shows the proposed draft and lists
  any scaffold sections your file is missing, so you can copy what you want.

For explicit control, `POST /init?write=true` creates the file (and `&overwrite=true` replaces an
existing one); without `write` it returns a preview (build system, languages, missing sections, draft).

```
curl -X POST "localhost:8080/init"                       -H "X-API-Key: <key>"   # preview only
curl -X POST "localhost:8080/init?write=true"            -H "X-API-Key: <key>"   # create if absent
```

## Context references (`@file` / `@directory`)

Mention a path with `@` in any prompt and `imini` inlines it into what the model sees -- like Claude
Code. `@path/to/File.java` attaches that file's content; `@some/dir` (or `@some/dir/`) attaches a
one-level listing of that directory. You can reference several at once:

```
Why does @src/main/java/com/example/imini/AgentLoop.java call into @src/main/java/com/example/imini/AgentEngine.java?
Summarize what's in @docs/
```

The referenced content is appended to your message inside a `<referenced-context>` block, so the model
reads the actual code rather than guessing. What was attached (or skipped, and why) is shown on the run
trace, e.g. `[context] attached @src/.../AgentLoop.java (file, 5123 bytes)`.

**Safety and caps.** References resolve **only inside the workspace** -- a token that escapes the root
(`@../etc/passwd`) or doesn't exist is ignored and left as plain text, so ordinary `@mentions` are never
mangled. Inlining is bounded by `context.refs.max-files` (10), `context.refs.max-file-kb` (64),
`context.refs.max-total-kb` (256), and `context.refs.max-dir-entries` (100); set `context.refs.enabled=false`
to turn the feature off. Directory references list names only (not nested file contents). `@@` is an
escape for a literal at-sign.

> This is distinct from memory `@path` imports (which live *inside* memory files like `CLAUDE.md`):
> context references are resolved in your chat prompts, per message.

## Skills

Skills are reusable instruction bundles -- a `SKILL.md` describing *when* to use it and *how* to do a
recurring task -- that the agent can pull into context on demand. They generalize the `commands/`
slash-command templates, and discovery reuses the same lexical scorer as retrieval.

**Where they live.** Drop skills under `skills/` in the workspace root (configurable via `skills.dir`):

```
skills/
  commit-message/SKILL.md     # folder form (name defaults to the folder)
  code-review/SKILL.md        # bundled
  debug/SKILL.md              # bundled
  batch/SKILL.md              # bundled
  loop/SKILL.md               # bundled
  readme.md                   # flat form (name defaults to the file stem)
```

**Format.** Optional `---` front-matter, then the body. Beyond `name` and `description`, four optional
keys tune behavior: `when_to_use` (extra text the auto-load scorer matches against, so the right skill
gets injected for a weak model), `argument-hint` (shown next to the name in `/skills`), `allowed_tools`
(a comma-separated list; on direct invocation the harness reminds the model to prefer just those tools),
and `context: fork` (run the skill in an isolated sub-agent -- see "Forked skills" below). The simplest
skills use only `name` + `description`:

```markdown
---
name: commit-message
description: Write a conventional-commits message from a diff or change summary.
argument-hint: <@file or change summary>
allowed_tools: git_diff, git_status
---
When asked to write a commit message:
1. Use `<type>(<scope>): <subject>` ...
```

**How the agent uses them.** A short index of every skill's name + description is injected into the
system prompt automatically:

```
--- Available skills (call load_skill with the name to load full instructions) ---
- commit-message: Write a conventional-commits message from a diff or change summary.
```

The model then calls the **`load_skill`** tool (`{"name":"commit-message"}`) to pull the full body when
a task matches -- progressive disclosure, so a large skill library costs only its index until used. The
**`save_skill`** tool (`{name, description, body}`) captures new knowledge as `skills/<name>/SKILL.md`
and reloads, so the agent (or you) can grow the library during a session.

**Listing and invoking skills directly.** Two Claude-like shortcuts let *you* drive skills from chat:

- `/skills` lists the available skills with their descriptions and effective enabled-state (per-session
  overrides respected), e.g. `/code-review - Review a diff`.
- `/<skill-name> [args]` invokes a skill directly: its body becomes the prompt, with `$ARGUMENTS` (or
  `$ARGS`) replaced by the text after the name. For example, `/commit-message fixed the parser NPE`
  runs the commit-message skill with that change summary. If the skill body has no placeholder, your
  arguments are appended as an `Arguments:` line so they aren't lost. The trace shows `[skill] invoked
  /commit-message`.

Only **enabled** skills are invokable, and the built-in commands (`/help`, `/memory`, `/init`,
`/skills`) are reserved -- they're never shadowed by a skill of the same name. A `/<name>` that matches
no enabled skill falls through to the normal `commands/` template (or the model) as before.

**Bundled skills.** `imini` ships with a few educational skills so `/skills` is useful out of the box,
each pairing naturally with `@file` references and the deterministic tools:

- `/code-review @path` -- review a diff or files for correctness, safety, and clarity, returning
  prioritized findings.
- `/debug <error or symptom>` -- diagnose methodically: reproduce, localize, hypothesize, minimal fix,
  verify.
- `/batch <change across many files>` -- enumerate targets, do one as a template, apply consistently,
  verify each.
- `/loop <goal + stop condition>` -- a bounded improve-and-check loop (one change per iteration, capped
  attempts).

They're ordinary `SKILL.md` files under `skills/`; edit or remove them like any other skill, or disable
them per-session.

**Forked skills (`context: fork`).** A skill whose front-matter sets `context: fork` does not run inline
in the main conversation. Instead, invoking `/<skill-name> [args]` delegates it to a **sub-agent** (like
the custom subagents below): the skill body becomes the sub-agent's instructions, scoped to the skill's
`allowed_tools` (or a read-only default), and only its final answer returns to the main thread. Use it
for noisy, multi-step skills (deep reviews, investigations) whose intermediate context you don't want
cluttering the main window. The trace shows `[skill] fork /<name>`. Skills without `context: fork` keep
running inline as before.

**Auto-load (optional).** Weaker local models sometimes won't call `load_skill` on their own. Set
`skills.auto-load=true` to also inject the single best-matching skill's body for `/ask` queries (picked
by lexical overlap with names + descriptions). Off by default. `skills.max-body` caps an injected body.

**Remote skill repositories (read-only).** Point `skills.repos` at a comma-separated allowlist of git
URLs; on startup (and whenever the agent calls `refresh_skills`) each is cloned/fast-forward-pulled
read-only into `<root>/<skills.cache-dir>` and its skills are loaded alongside the local ones:

```properties
skills.repos=https://github.com/your-org/agent-skills.git,https://github.com/team/more-skills.git
```

A repo's skills are read from its `skills/` subdirectory if present, else its root, using the same
folder/flat layout. **Local skills override remote ones of the same name**, and earlier-listed repos win
over later ones (`SkillLibrary.merge`). The configured list is the *allowlist* -- only those URLs are
ever fetched, and the model cannot inject a URL (the `refresh_skills` tool takes no arguments).

Pin a repo to a branch or tag with `url#ref` (e.g. `https://github.com/org/skills.git#v1.2`) so you
load a known revision rather than whatever `HEAD` happens to be.

**Skill registry (provenance).** A registry is a manifest of *available* skills with provenance, so a
skill can be searched for and verified before it is installed. Point `skills.registry` at a manifest
JSON (path under the workspace root); each entry carries a content hash:

```json
[
  {"name":"commit-message","description":"Write a conventional commit from a diff.",
   "source":"commit-message/SKILL.md","version":"1.0","sha256":"<sha256 of the SKILL.md>"}
]
```

The agent calls **`search_skills`** (`{query}`) to rank the manifest (same lexical scorer; shows
`[installed]`), then **`install_skill`** (`{name}`) which reads the entry's `source` (a path *relative
to the manifest's directory*, so a cloned remote repo can ship its own `registry.json`), **verifies the
SHA-256**, and -- only on a match -- writes the skill locally with its provenance (`source`, `version`,
`sha256`) recorded in the front-matter. A hash mismatch refuses the install; an entry with no `sha256`
installs with a warning.

**Enable / disable.** Skills can be turned off without deleting them -- a disabled skill is dropped from
the prompt index, auto-load, and `load_skill`. `GET /skills` lists every loaded skill with its `enabled`
flag; admins flip one with `POST /skills/toggle {name, enabled}` (and re-pull remotes with `POST
/skills/refresh`). Toggles are **persisted** in the `skill_state` table, so they survive a restart (with
no database configured they are in-memory for the run). The **web UI** shows a *Skills* card to everyone:
members see a **read-only** list of skills and their state, while admins get the checkboxes and the
*refresh* link. Seed the disabled set at startup with `skills.disabled=name1,name2` (the persisted state
takes over once an admin toggles).

**Member proposals.** Members who cannot toggle skills can still *propose* one: `POST /skills/request`
with `{name, description, body}` queues a proposal (the UI *Skills* card has a "Propose a skill" form
for everyone). Admins review the queue with `GET /skills/requests`, then `POST /skills/requests/resolve`
with `{id, approve}` -- approving saves it as a local skill (same path as `save_skill`), rejecting just
marks it. Proposals live in the `skill_requests` table (in-memory without a DB). A member can review their own
proposals and their status via `GET /skills/requests/mine` (the *Skills* card shows a "my requests"
list), withdraw a pending one with `POST /skills/requests/withdraw {id}`, or edit it with `POST
/skills/requests/update {id, ...}`.

**Per-session overrides.** Beyond the global default, a skill can be enabled or disabled for a single
session: `POST /skills/session-toggle {sessionId, name, enabled}` sets an override and `POST
/skills/session-reset {sessionId, name}` clears it (both need access to that session). The effective
state for a session is the override if present, otherwise the global default -- this is what drives the
skills index and auto-load for that session's runs. `GET /skills?sessionId=<id>` returns each skill's
effective `enabled`, its `global` default, and any `override`. In the *Skills* card the per-row checkbox
toggles **this session** (anyone with session access); a *reset* link clears the override, and admins
get a `[global: on/off]` link to flip the global default. Overrides persist in `session_skill_state`.

Config: `skills.enabled` (default true), `skills.dir` (default `skills`), `skills.auto-load` (default
false), `skills.max-body` (default 4000), `skills.repos` (default empty), `skills.cache-dir` (default
`skill-cache`), `skills.repo-timeout-seconds` (default 60), `skills.repos-on-start` (default true),
`skills.registry` (default empty), `skills.disabled` (default empty).

> Honest scope: skills are READ-ONLY instructions, not executable bundles -- if a skill suggests
> running a script, that still goes through `run_command` and the sandbox command policy (no auto-exec),
> and this holds equally for remote and installed skills. Discovery is lexical (keyword overlap), not
> semantic; names are sanitized to prevent path traversal and registry sources may not escape the
> manifest directory. `install_skill` verifies a SHA-256 (integrity) but there is no cryptographic
> signing / trust root yet, and repo pinning supports branches/tags (shallow); treat sources as trusted.

## Subagents

A **subagent** is a named, tool-scoped helper the main agent (or you) can hand a focused subtask to. It
runs in its **own** isolated loop and returns only its final answer -- all of its intermediate context
(search results, file dumps) stays in the sub-conversation and never clutters the main window. That
isolation is the point: delegate "explore the auth code" or "review this diff" and get back a clean
summary.

**Built-in agents.** `imini` ships with read-only subagents so the feature works out of the box:

- `explore` -- map the codebase (glob/grep/repo_tree/read) and report where the relevant code lives.
- `review` -- review code or a diff and return prioritized findings.
- `debug` -- investigate a bug (read-only) and propose a minimal fix.
- `research` -- search the web and summarize (web_search/web_fetch).

**Using them.** `/agents` lists the available subagents with their tool scopes; `/agent <name> <task>`
delegates, e.g. `/agent explore where is the approval flow handled?`. The main model can also delegate
on its own via the **`delegate_agent`** tool (`{name, task}`). Each delegation is logged on the trace as
`[agent] delegate /agent <name>`.

**Custom agents.** Drop an `agents/<name>.md` in the workspace (configurable via `agents.dir`) to add or
override an agent. Optional `---` front-matter sets `description`, `tools` (a comma-separated allow-list
of tool names the agent may use), and `model`; the body is the agent's system prompt:

```
---
name: explore
description: Map the codebase and report where the relevant code lives.
tools: glob, grep, repo_tree, read_many, read_file, view
---
You are an exploration subagent. Locate the relevant files and report a concise map...
```

A disk agent overrides a built-in of the same name. Set `agents.enabled=false` to turn the feature off.

> Honest scope: a subagent is scoped to the tools its definition lists (resolved against the registered
> tools); built-ins are read-only and run in AUTO mode, so they're safe to auto-run. A custom agent that
> lists a mutating tool would run it without a separate approval prompt inside the sub-loop -- prefer
> read-only tool sets for delegated agents. The `model` key is advisory (a profile name), not a separate
> endpoint.

## Patch preview and review

Sometimes you want to *see* a change before it touches the workspace. The **`preview_patch`** tool takes
the same edits as `apply_patch` ({path, find, replace} or {path, create}) but writes nothing -- it stages
the change and returns a unified diff. Review it, then **`apply_previewed_patch`** writes it (re-validating
against the current files and snapshotting each change so it can be rewound), or **`discard_previewed_patch`**
drops it. Both default to the most recent staged preview, or take an `id`.

**Hunk-level approval.** A staged preview is a list of **hunks** -- one per edit, each independently
applicable and numbered (`[0]`, `[1]`, ...). You don't have to take a preview all-or-nothing: pass
`hunks` to apply or discard only some, e.g. `apply_previewed_patch hunks="0,2"` or `hunks="1-3"` (blank
= all). Applied hunks are written and snapshotted; the rest stay staged (re-numbered) so you can handle
them later.

The web UI has a **Patch preview** card: each staged preview shows its hunks with a checkbox and per-hunk
diff, and **Apply selected** / **Apply all** / **Discard** buttons -- review-and-pick right in the
browser. The same surface is available over HTTP:

```
curl "localhost:8080/preview?sessionId=default"                                -H "X-API-Key: <key>"
curl -X POST "localhost:8080/preview/apply?sessionId=default&id=pv-1&hunks=0,2" -H "X-API-Key: <key>"
```

> Honest scope: each hunk is one `apply_patch` edit; a hunk's diff is a single-hunk render (common
> prefix/suffix trimmed), good for small targeted edits -- not a full LCS diff. Previews are in-memory
> and per-session (ephemeral). `apply_previewed_patch` re-applies the selected hunks against the
> *current* files, so if a file changed since staging, the apply aborts rather than clobbering it.

## Session export / import

A whole session -- its conversation, plan history (steps + per-step tools + coding reports), and todos --
can be exported as one portable JSON bundle and imported into a fresh session, on the same instance or
another one:

```
curl "localhost:8080/session/export?sessionId=proj" > proj.json   # download a bundle
curl -XPOST localhost:8080/session/import --data @proj.json        # -> {sessionId, messages, plans, todos}
```

`GET /session/export` returns a `imini-session/1` bundle (ownership/shared-read scoped) and stamps it
with an `integrity` SHA-256 over its content. `POST /session/import` validates the bundle, checks the
version is supported, and (when an `integrity` hash is present) **recomputes and compares it** -- in the
default `strict=true` mode a mismatch is refused; `strict=false` imports anyway with a warning. The
`mode` controls the destination:

| `mode` | effect |
| --- | --- |
| `new` (default) | create a fresh `imp-...` session owned by you |
| `replace` | restore into the `target` session (overwrites its conversation/todos) |
| `merge` | append the bundle's messages to the `target` session |

`replace`/`merge` need a `target=<sessionId>` you can manage; plans are re-archived oldest-first either
way. In the **web UI**, the *Session bundle* card has *Export* (downloads `<sessionId>.json`), an import
**mode** selector, and *Import* (pick a file -- `new` switches to the imported session; `replace`/`merge`
target the current session).

**Migration.** The current bundle version is `imini-session/2`. Import normalizes older or looser
bundles into it before restoring (after the integrity check, which is always over the bundle as
received): a missing or `imini-session/0` version, or a `imini-session/1` bundle, is upconverted (a v1
bundle gains an empty `skillOverrides`); a legacy `history` key is read as `messages`; and `todos` given
as plain strings are wrapped into `{content, status:"pending"}`. Integrity is **version-aware** -- v1
bundles are hashed without `skillOverrides`, so previously exported v1 bundles still verify. A bundle
whose (migrated) version is still unsupported is rejected.

**Skill overrides travel with the session.** A bundle carries the session's per-session skill overrides
(`skillOverrides: [{name, enabled}]`); on import they are re-applied to the destination session, so a
shared or migrated session keeps its tuned skill set. The import/preview responses include a
`skillOverrides` count.

**Sharing travels too (opt-in restore).** The current bundle version is `imini-session/3` and also
carries the session's `owner` and `readers` (its shared-with list). Import with `restoreSharing=true`
(the UI's "restore the bundle's shared-with list" checkbox) re-grants those readers on the destination
session -- the importer always becomes the new owner. Integrity stays version-aware: v1 bundles hash
without `skillOverrides`/`readers` and v2 without `readers`, so older exports still verify; migration
upconverts them (gaining empty fields). The import/preview responses include a `sharedWith`/`readers`
count.

**Preview.** `POST /session/import/preview` (or the UI *Preview* button) reports what an import *would*
do without touching anything: the integrity status (`ok`/`mismatch`/`none`), the (migrated) version and
whether it is supported, and a before/incoming/after count for messages, todos, and plans under the
chosen mode -- so you can see that, say, a `merge` would grow messages from 10 to 15 before committing.

**Activity log (admin).** The web UI shows an admin-only *Activity* card backed by `GET /audit`
(recent governance/tool events: skill toggles, session overrides, proposals/resolutions, imports, and
more). It filters by `user` (exact) and `action` (substring), a "this session only" toggle (matches
`target` containing `session:<id>`), and pages with prev/next (`offset`/`limit`) -- a readable window on
the audit trail without curling the endpoint.

> Honest scope: integrity is a content SHA-256 (tamper-evidence), not a signature -- stripping the field
> bypasses the check, and `strict=false` imports regardless. The bundle is plain JSON (no encryption, no
> streaming for very large sessions). `merge` only appends messages (it does not de-duplicate).

## Session sharing and ownership

A session and everything keyed to it -- its conversation, plans, per-step transcript, coding reports,
and plan history -- is owned by the user who first used it (admins and unowned/legacy sessions stay
open). Two operations let that record be handed off or reviewed by a teammate:

```
curl -XPOST localhost:8080/share    -d '{"sessionId":"proj","user":"cara"}'   # grant cara read access
curl  localhost:8080/shares?sessionId=proj                                     # -> {owner, readers:[...]}
curl -XPOST localhost:8080/unshare  -d '{"sessionId":"proj","user":"cara"}'   # revoke
curl -XPOST localhost:8080/transfer -d '{"sessionId":"proj","to":"dave"}'     # hand ownership to dave
```

**Sharing** grants *read* access: a reader can view the session and its plans, history, todos, and
checkpoints (the read endpoints), and the session shows up in their `GET /sessions` list. Readers
cannot run, mutate, share, or transfer -- those stay owner/admin-only. **Transfer** moves ownership to
another user and keeps the previous owner on as a reader, so a hand-off never locks the original owner
out. Both actions are recorded in the audit log.

Access is resolved by `Ownership.canRead` (owner/admin/unowned, or an explicit reader) for read
endpoints and `Ownership.canAccess` (owner/admin/unowned) for everything that changes state.

In the **web UI**, the *Sharing* card shows the current session's owner and readers; it offers a *Share*
box to grant read access, a *revoke* link next to each reader, and a *Transfer* box to hand ownership to
another user (with a confirm). The grant/transfer controls only appear when you can manage the session
(owner, admin, or unowned); a reader sees the roster but not the controls. It refreshes on session
switch and after each action.

> Honest scope: sharing is a single read tier (no per-resource or write-sharing granularity); grants are
> by user name with no expiry or invitation flow; this is app-level access control, not OAuth/OIDC or
> fine-grained ACLs.

## Safety notes

`imini` includes useful educational safety layers, but it is not a complete production security boundary by default.

What it does today:

- confines file reads and writes to the workspace,
- screens shell commands with deny-only or allowlist mode,
- supports optional container command wrapping,
- checkpoints file edits before mutation,
- fences untrusted web/MCP output,
- and supports approval gates for mutating tools.

Important limitations:

- Pattern-based command screening is not the same as a syscall sandbox.
- For strong isolation, use allowlist mode and containerized command execution.
- MCP servers should be treated as powerful external tool providers.
- Auth is app-level: API keys map to users with a simple two-role RBAC (admin/member) and per-resource
  ownership of sessions; it is not OAuth/OIDC or fine-grained per-resource ACLs.
- Metrics are in-process JSON, not a production observability backend.
- The audit log records privileged actions for accountability; it is not tamper-proof storage.

## Audit log

Every privileged action is recorded to an append-only `audit` table (with an in-memory fallback): the
acting `user`, the `action` (`ask`, `chat`, `chat/stream`, `interrupt`, `steer`, `rewind`, `approve`,
`index`, and per-tool `tool:<name>`), the `target` (e.g. `session:proj`, `session:proj step:2`, or
`approval:<id>`), an ISO timestamp, and the `outcome`.

Read it (admin only) at `GET /audit`, newest first, with optional filters:

```
curl "localhost:8080/audit?limit=50"               -H "X-API-Key: <admin>"
curl "localhost:8080/audit?user=bob"               -H "X-API-Key: <admin>"
curl "localhost:8080/audit?action=skill&offset=20" -H "X-API-Key: <admin>"
curl "localhost:8080/audit?target=session:proj"    -H "X-API-Key: <admin>"
```

**Export.** `GET /audit/export?format=csv|json` downloads the trail (same `user`/`action`/`target`
filters plus a `since`/`until` epoch-millis window; `0` = unbounded) as a `text/csv` or
`application/json` attachment -- the admin *Activity* card has date pickers and *Export CSV*/*Export
JSON* buttons. CSV is RFC-4180-escaped.

```
curl "localhost:8080/audit/export?format=csv&since=1717200000000" -H "X-API-Key: <admin>" -o audit.csv
```

**Per-session activity.** `GET /session/activity?sessionId=<id>` returns just that session's events
(those whose audit target is the session) and is readable by **anyone with access to the session**, not
only admins -- so a session owner or reader can see their own session's history (imports, sharing
changes, etc.). The web UI shows a *Session activity* card (with prev/next) for the current session.

### Tool-call detail & per-step transcript

Beyond request-level entries, every **mutating** tool call (`write_file`, `edit_file`, `apply_patch`,
`run_command`, `todo_write`) is recorded as a `tool:<name>` audit entry attributed to the session and,
during a plan, the step (`target` = `session:proj step:2`). Read-only calls (reads, greps, listings)
are not recorded, to keep the trail signal-rich.

The same calls are gathered into a **per-step transcript**: `GET /plan?sessionId=` returns each step
with a `tools` array of one-line entries like `write_file src/App.java [ok]` or
`run_command $ mvn -q test [error]`, so a finished or resumed plan shows *what was actually done* at
each step (persisted in the `plan_steps` table). Step boundaries are taken from the live checklist (the
one step that is `in_progress`). Turn the whole feature off with `agent.audit.tool-calls=false`.

> Honest scope: only mutating tools are recorded, attributed by session owner (not necessarily the live
> caller on a worker thread); the transcript is one line per call (tool + short arg + ok/error), not
> full inputs/outputs; and it is best-effort, not tamper-proof.

Identity comes from the API key (see RBAC: legacy `auth.keys` are admins; `auth.principals` of the form
`user:key:role` assign roles). When `auth.enabled=false` actions are attributed to the anonymous admin.
`/audit` is admin-gated via `auth.admin-paths` (default `/metrics,/audit`).

## Recommended learning sequence

1. Read `docs/LEARNING_PATH.md`.
2. Run the app with `run.bat`.
3. Try one simple `ask.bat` prompt.
4. Try a repo-navigation prompt.
5. Try plan mode.
6. Try a scratch-file edit and rewind.
7. Read `docs/TRACE_EDIT.md`.
8. Run `mvn test`.
9. Read `docs/CONCEPT_MAP.md`.

## Current best next engineering step

The next highest-leverage production-like improvement is:

> Automatically run `git_status` and `git_diff` after any mutating file tool, then require final coding answers to summarize changed files and verification.

That is smaller than full sandboxing and would significantly improve trust.

## Suggested GitHub repository description

GitHub repository descriptions are metadata, not files. Update the repo description manually to:

```text
A local, llama.cpp-backed Java/Spring learning harness that demonstrates Claude Code-style agent loops, tools, permissions, sessions, retrieval, and safety boundaries.
```
