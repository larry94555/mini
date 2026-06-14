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
| Planning | `todo_write`, plan mode, **plan-then-execute** orchestrator with retry, re-planning, step verification (+ auto-suggested checks), persist/resume, and per-session history, coding profile guidance |
| Edit trust | auto `git status`/`git diff --stat` verification + structured coding report appended to coding answers |
| State | SQLite-backed sessions, checkpoints, memory index |
| Retrieval | `index_workspace` and `search_memory` with lexical scoring and symbol boost |
| Skills | reusable `SKILL.md` bundles: auto-indexed, `load_skill`/`save_skill`, read-only remote repos (pinnable) via `refresh_skills`, and a provenance registry (`search_skills`/`install_skill`, hash-verified) |
| Extensibility | MCP client, research sub-agent, hooks, slash commands |
| UI/API | Blocking and streaming HTTP endpoints, web UI (live plan, plan-history + coding-report viewer, session sharing), remote approvals |
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
| `CodebaseTools.java` | Deterministic repo navigation, git tools, and symbol search |
| `PermissionService.java` | Permission modes, allow/deny rules, remembered decisions, plan mode, write confinement |
| `Sandbox.java` | Command screening, read confinement, optional container execution wrapper |
| `CheckpointStore.java` | Snapshot-before-edit and rewind |
| `SessionStore.java` | Session history persistence |
| `Database.java` | SQLite connection and migrations |
| `ContextManager.java` | Token counting, compaction, tool-output trimming, durable memory note |
| `RetrievalService.java` | Workspace indexing and memory search |
| `SkillLibrary.java` | Pure parse/index/select/format/merge for skills + repo spec parsing |
| `SkillManifest.java` | Pure skill-registry manifest: parse, lexical search, SHA-256 verify |
| `SkillService.java` | Loads local + remote skills; index; `load_skill`/`save_skill`/`refresh_skills`/`search_skills`/`install_skill` |
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
`[edits this step]` note -- the files that step touched plus the current `git diff --stat` -- to the
running context. Later steps and the final synthesis see it, so the model can react to unexpected diffs
mid-plan (e.g. notice it edited the wrong file) instead of only learning what changed at the end. It is
derived from the tool recorder's tracked paths, so it is independent of the audit toggle. Turn it off
with `agent.plan.step-diff=false`.

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
- `find_symbol` to find declarations across the repo,
- `read_many` to compare related files,
- `git_status` and `git_diff` to verify changes.

## Persistence and retrieval

`imini` uses SQLite for durable sessions, checkpoints, and retrieval index data. If SQLite cannot be opened, the app falls back to in-memory behavior so the learning flow still works.

Retrieval is lexical by default and works well for code identifiers. Optional embedding-based retrieval can be enabled if you run a model/server that supports embeddings.

## Skills

Skills are reusable instruction bundles -- a `SKILL.md` describing *when* to use it and *how* to do a
recurring task -- that the agent can pull into context on demand. They generalize the `commands/`
slash-command templates, and discovery reuses the same lexical scorer as retrieval.

**Where they live.** Drop skills under `skills/` in the workspace root (configurable via `skills.dir`):

```
skills/
  commit-message/SKILL.md     # folder form (name defaults to the folder)
  readme.md                   # flat form (name defaults to the file stem)
```

**Format.** Optional `---` front-matter for the name and a one-line description, then the body:

```markdown
---
name: commit-message
description: Write a conventional-commits message from a diff or change summary.
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

Config: `skills.enabled` (default true), `skills.dir` (default `skills`), `skills.auto-load` (default
false), `skills.max-body` (default 4000), `skills.repos` (default empty), `skills.cache-dir` (default
`skill-cache`), `skills.repo-timeout-seconds` (default 60), `skills.repos-on-start` (default true),
`skills.registry` (default empty).

> Honest scope: skills are READ-ONLY instructions, not executable bundles -- if a skill suggests
> running a script, that still goes through `run_command` and the sandbox command policy (no auto-exec),
> and this holds equally for remote and installed skills. Discovery is lexical (keyword overlap), not
> semantic; names are sanitized to prevent path traversal and registry sources may not escape the
> manifest directory. `install_skill` verifies a SHA-256 (integrity) but there is no cryptographic
> signing / trust root yet, and repo pinning supports branches/tags (shallow); treat sources as trusted.

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
