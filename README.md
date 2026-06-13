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
| Codebase navigation | `glob`, `grep`, `repo_tree`, `read_many`, `outline`, `find_symbol` |
| Git awareness | `git_status`, `git_diff`, `git_log`, `git_blame` |
| Safety | Permission modes, workspace confinement, command screening, optional container command wrapper |
| Planning | `todo_write`, plan mode, **plan-then-execute** orchestrator with retry, re-planning, step verification (+ auto-suggested checks), and persist/resume, coding profile guidance |
| State | SQLite-backed sessions, checkpoints, memory index |
| Retrieval | `index_workspace` and `search_memory` with lexical scoring and symbol boost |
| Extensibility | MCP client, research sub-agent, hooks, slash commands |
| UI/API | Blocking and streaming HTTP endpoints, web UI, remote approvals |
| Ops | API-key auth, rate limiting, per-user RBAC, per-resource ownership, audit log (incl. tool-call level), `/metrics`, structured logging, Docker, CI |

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
| `RunRecorder.java` | Records mutating tool calls to the audit log + per-step transcript |
| `PlanStore.java` | Persists the per-session plan (goal + checklist) for inspect/resume |
| `AgentEngine.java` | Main think -> act -> observe loop |
| `ToolRegistry.java` | Builds the available tool set |
| `Tool.java` | Tool definition: name, description, schema, mutating flag, untrusted flag, executor |
| `BuiltinTools.java` | File, shell, web, patch, and todo tools |
| `CodebaseTools.java` | Deterministic repo navigation, git tools, and symbol search |
| `PermissionService.java` | Permission modes, allow/deny rules, remembered decisions, plan mode, write confinement |
| `Sandbox.java` | Command screening, read confinement, optional container execution wrapper |
| `CheckpointStore.java` | Snapshot-before-edit and rewind |
| `SessionStore.java` | Session history persistence |
| `Database.java` | SQLite connection and migrations |
| `ContextManager.java` | Token counting, compaction, tool-output trimming, durable memory note |
| `RetrievalService.java` | Workspace indexing and memory search |
| `ProjectContext.java` | Loads `IMINI.md`, `CLAUDE.md`, or `AGENTS.md` into the system prompt |
| `TodoStore.java` | Per-session task checklists |
| `InterruptService.java` | Per-session interrupt and steering |
| `Approvals.java` | Pending remote approval registry |
| `HookService.java` | Pre/post tool shell hooks from `hooks.json` |
| `SlashCommands.java` | Prompt templates from `commands/*.md` |
| `SubAgent.java` | Constrained research sub-agent |
| `McpManager.java` | Optional MCP stdio client |
| `AgentController.java` | HTTP endpoints |
| `RunService.java` | Slot-bounded job queue for concurrent runs |
| `RunSink.java` | Output abstraction for console and SSE streaming |
| `Sse.java` | SSE event framing/parsing helpers |
| `AuthFilter.java` | API-key auth, request attribution, and RBAC gating |
| `Rbac.java` / `Principal.java` / `RequestContext.java` | role policy and per-request caller identity |
| `Ownership.java` | per-resource access policy (owner / admin / unowned) |
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
| `GET /plan?sessionId=` | Read the saved plan: goal + steps + statuses + per-step tool transcript |
| `GET /todos?sessionId=` | Read session todos |
| `GET /runs` | Show concurrency status |
| `POST /interrupt` | Stop one session's active run |
| `POST /steer` | Add steering guidance to one session's active run |
| `GET /approvals?sessionId=` | List pending approvals |
| `POST /approve` | Resolve a pending approval |
| `POST /rewind` | Rewind a session's last checkpoint |
| `GET /checkpoints?sessionId=` | List session checkpoints |
| `POST /index` | Build or rebuild retrieval index |
| `GET /memory?q=&k=` | Search indexed workspace memory |
| `GET /health` | Health check |
| `GET /me` | Current caller identity (`user`, `role`) |
| `GET /metrics` | Metrics snapshot (admin only) |
| `GET /audit?user=&target=&limit=` | Audit trail of privileged actions, newest first (admin only) |
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
(`{"steps":[{"text","status"}]}`) every time the checklist changes -- when steps are drafted, start,
complete, fail, or get re-planned. The web UI renders this as a live checklist above each answer, with
`[ ]` pending, `[~]` in progress, `[x]` done, and `[!]` failed, so you can watch the agent work the
plan in real time (no polling). Non-streaming sinks fall back to logging the event; the list is also
always readable at `GET /todos`.

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
- `find_symbol` to find declarations across the repo,
- `read_many` to compare related files,
- `git_status` and `git_diff` to verify changes.

## Persistence and retrieval

`imini` uses SQLite for durable sessions, checkpoints, and retrieval index data. If SQLite cannot be opened, the app falls back to in-memory behavior so the learning flow still works.

Retrieval is lexical by default and works well for code identifiers. Optional embedding-based retrieval can be enabled if you run a model/server that supports embeddings.

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
curl "localhost:8080/audit?target=session:proj"    -H "X-API-Key: <admin>"
```

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
