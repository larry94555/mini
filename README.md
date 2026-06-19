# imini — a low-end Claude Code learning harness

`imini` is a minimal but real agent harness over a local `llama-server` running a small local model such as `Qwen/Qwen2.5-3B-Instruct`.

The purpose of this repository is educational: it makes the boundary between **the model** and **the harness** concrete.

- The **model** reasons and emits text or tool calls.
- The **harness** owns tools, state, permissions, persistence, safety, verification, and user experience.

No cloud API key is required.

## Start here

- **New here? Start with [`GettingStarted.md`](GettingStarted.md)** — the newbie front door (simple test +
  recommended learning path + the docs to use).
- First-time install: [`INSTALL.md`](INSTALL.md)
- One-command demo (Docker, full stack incl. metrics dashboards): see [`docs/observability/`](docs/observability/) -- `docker compose -f docker-compose.yml -f docker-compose.observability.yml up --build`
- No-build demo from a **published image**: `docker compose -f docker-compose.published.yml up` (pulls `ghcr.io/larry94555/imini`, published by the release workflow as a **multi-arch** image -- runs natively on Intel/AMD and ARM/Apple Silicon)
- Core terms in plain language: [`docs/GLOSSARY.md`](docs/GLOSSARY.md)
- Guided learning path: [`docs/LEARNING_PATH.md`](docs/LEARNING_PATH.md)
- Guided 90-minute workshop (labs + test checkpoints): [`docs/WORKSHOP.md`](docs/WORKSHOP.md)
- End-to-end edit trace: [`docs/TRACE_EDIT.md`](docs/TRACE_EDIT.md)
- Claude Code concept map: [`docs/CONCEPT_MAP.md`](docs/CONCEPT_MAP.md)
- What imini deliberately leaves out (and where you'd go next): [`docs/WHATS_NOT_INCLUDED.md`](docs/WHATS_NOT_INCLUDED.md)
- Recursive Language Models — concept note (why imini doesn't use them, when they fit): [`docs/RECURSIVE_LANGUAGE_MODELS.md`](docs/RECURSIVE_LANGUAGE_MODELS.md)
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
| `SessionStore.java` | Session history + ownership/sharing + titles persistence |
| `SessionNaming.java` | Pure title normalization + fork-name derivation |
| `SessionBundle.java` | Pure build/validate/extract/migrate of a portable session export bundle |
| `Database.java` | SQLite connection and migrations |
| `ContextManager.java` | Token counting, compaction, tool-output trimming, durable memory note |
| `TokenBudget.java` | Pure token estimate + fit-the-prompt-to-a-budget logic |
| `PlanFallback.java` | Pure decision: auto-switch an over-budget turn to plan mode |
| `LoopCommand.java` | Pure `/loop` parsing + iterate-until-green prompt/continue logic |
| `Schedule.java` / `ScheduledTasks.java` | Pure scheduling math + the durable local task scheduler |
| `SettingsStore.java` | Durable key/value app settings (`app_settings` table) |
| `SessionSettings.java` | Durable per-session settings (`session_settings` table) |
| `SessionSettingsResolver.java` | Pure per-session setting validation + mode precedence |
| `PluginPack.java` / `PluginService.java` | Plugin packs: pure model/validation/SHA-256 + export/install (incl. by URL/registry) |
| `PluginRegistry.java` | Pure registry-index model + parse/search/lookup |
| `VisionContent.java` / `VisionSupport.java` | Pure multimodal content building + vision capability gate |
| `AdminFormat.java` | Pure dashboard formatting (uptime, top-N tallies, success rate) |
| `RunHistory.java` | Pure bounded ring buffer of recent runs (for the dashboard) |
| `RunHistoryStore.java` | Durable run history (`run_history` table) + startup reload |
| `PromFormat.java` | Pure Metrics snapshot -> Prometheus text exposition format |
| `RunFilter.java` | Pure run-history filter (endpoint/outcome/session) |
| `WorkspaceBundle.java` / `WorkspaceService.java` | Whole-workspace export/import (pack + settings) |
| `WorkspacePreview.java` | Pure import dry-run classification (new/changed; create/overwrite) |
| `BundleSignature.java` | Pure bundle signing/verification: HMAC-SHA256 + Ed25519 (public-key) |
| `Keyring.java` | Pure verifier keyring (trust several public keys; key ids) |
| `SigningService.java` | Shared signing/verify config for bundles and plugin packs |
| `TokenBudgetService.java` | Runtime-configurable per-call token budget (default 8500) |
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
| `RateLimiter.java` | Per-key rate limiter: fixed or sliding window, optional SQLite persistence, stale-window pruning |
| `EvalHarness.java` | Agent-evaluation suite: pure scoring (contains/regex/normalized) + a model runner that self-skips offline |
| `Tracer.java` | Dependency-free OpenTelemetry-style tracer: W3C span ids, nesting, ring + SQLite, inbound `traceparent` propagation, OTLP/JSON export |
| `CostService.java` | Per-tenant token metering, micro-USD cost ledger, tiered monthly token quotas, and spend alerts |
| `CapabilityService.java` | Tool-level capability scoping: per-role allowlists (with MCP prefix match), enforced before execution, propagated into sub-agents, denials audited |
| `UsageDashboard.java` | Renders the per-tenant usage dashboard (HTML) from the cost summary |
| `Redact.java` | Masks secret/PII-shaped values (bearer tokens, keys, JWTs, emails) in traces and logs |
| `RedactingJsonEncoder.java` | Logback encoder that scrubs secrets/PII from the built-in `JsonEncoder`'s output (structured `json` profile) |
| `AuditDashboard.java` | Renders the filterable audit-log viewer (HTML) from audit entries |
| `ToolRateLimiter.java` | Per-tenant, per-tool sliding-window rate limiting, enforced before tool execution |
| `RedactionConfig.java` | Loads operator-supplied redaction patterns from config into `Redact` at startup |
| `Metrics.java` | In-process metrics snapshot and run logs |
| `static/index.html` | Browser UI |
| `Dockerfile` | Container image for the app |
| `docker-compose.yml` | One-command local app + llama server setup |
| `.github/workflows/ci.yml` | CI: unit tests (via `./mvnw`) and Docker build |
| `.github/workflows/smoke.yml` | CI: cross-platform build + boot smoke test (Linux + macOS + Windows) |
| `.github/workflows/release.yml` | On a `v*` tag: build the jar, checksum it, publish a GitHub Release |
| `.github/workflows/supply-chain.yml` | SBOM (CycloneDX) + dependency vulnerability scan (Trivy -> Security tab) |
| `.github/dependabot.yml` | Weekly dependency-update PRs (Maven, GitHub Actions, Docker) |
| `.github/workflows/release-please.yml` | Conventional-Commit release PRs: bump `pom.xml` + `CHANGELOG.md`, tag |
| `CHANGELOG.md` | Auto-generated changelog (release-please) |
| `.trivyignore` | Documented CVE exceptions for the CRITICAL scan gate |
| `docs/SECURITY.md` | Supply-chain severity policy (gate vs. report) |
| `docs/MEMORY.md` | Durable-memory subsystem pipeline + config |
| `docs/DEPLOY.md` | Deployment: health probes, Docker/k8s, observability |
| `MemoryStore.java` | Durable cross-session `[MEMORY]` note (per owner) |
| `.gitattributes` | Line-ending policy (LF for `*.sh`/`mvnw`, CRLF for `*.bat`/`*.cmd`/`*.ps1`) |
| `.githooks/` | Pre-commit guard: scripts stay executable + LF (`sh scripts/install-hooks.sh` to enable) |
| `scripts/git-mark-exec.sh` | One-shot: mark all scripts executable in git (`100755`) |
| `scripts/pin-maven-checksum.sh` | Re-pin the wrapper's Maven SHA-512 after a version bump |
| `mvnw` / `mvnw.cmd` / `.mvn/` | Maven wrapper — build with no system Maven installed |

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

## Run on macOS, Linux, or WSL

The app is plain Java, so the same code runs anywhere with a JDK 17+. Use the POSIX shell scripts (the
`.sh` equivalents of the `.bat` files). First time only, make them executable:

```sh
chmod +x *.sh scripts/*.sh
```

Then start it and try a few commands:

```sh
./run.sh
./ask.sh "Say hello in one sentence."
./chat.sh work1 "Remember that the codename is Bluefin."
./stream.sh work1 "Use repo_tree to inspect the project, then summarize what kind of app this is."
```

These run identically on macOS, Linux, WSL, and Git Bash on Windows. The scripts talk to
`http://localhost:8080` by default; point them at another host/port with the `IMINI_URL` environment
variable, e.g. `IMINI_URL=http://localhost:9000 ./runs.sh`. On macOS/Linux/WSL the model server binary is
`llama-server` (no `.exe`); imini detects this automatically, so no configuration change is needed.

**No Maven install needed.** The repo ships a Maven wrapper, so `run.sh`/`run.bat` build with `./mvnw`
(`mvnw.cmd` on Windows) automatically. The wrapper prefers a Maven already on your PATH; if there is none,
it downloads a pinned Apache Maven into `.maven/` once and uses that. You can also build directly:

```sh
./mvnw -version        # macOS/Linux/WSL
mvnw.cmd -version      # Windows
./mvnw test            # run the unit tests
```

**Pinned download (integrity-checked).** The wrapper downloads the official Apache Maven `.tar.gz` and
verifies it against `distributionSha512Sum` in `.mvn/wrapper/maven-wrapper.properties` -- this ships pinned
to the official 3.9.9 SHA-512, so `./mvnw` and `mvnw.cmd` abort on any mismatch. After a version bump,
re-pin with a verified value in one command:

```sh
sh scripts/pin-maven-checksum.sh   # downloads, hashes, rewrites distributionSha512Sum=, then commit it
```

CI caches the wrapper's downloaded Maven (`actions/cache` on `.maven`, keyed to the wrapper properties), so
the no-system-Maven path doesn't re-download on every run.

**Contributor setup (one time).** Enable the repo's pre-commit guard so scripts can't silently lose their
executable bit or pick up Windows line endings:

```sh
sh scripts/install-hooks.sh        # Windows: scripts\install-hooks.cmd
```

The guard (`.githooks/pre-commit`) blocks a commit if a required script is not executable in git
(`100755`) or if a `*.sh`/`mvnw` file contains CRLF, and prints the exact `git update-index --chmod=+x`
fix. `.gitattributes` keeps line endings correct on checkout. **CI now enforces the same check as a hard
failure** (Linux job in `smoke.yml`), and the cross-platform smoke test covers Linux, macOS, and Windows.

If the scripts ever show up non-executable in git (e.g. after importing from an archive, which cannot carry
the bit), fix them all in one shot, then commit:

```sh
sh scripts/git-mark-exec.sh        # runs the git update-index --chmod=+x for every script
```

## Releases, dependency updates, and supply chain

**Cutting a release.** Set the version in `pom.xml`, then push a matching tag:

```sh
./mvnw -q versions:set -DnewVersion=0.3.0    # or edit <version> in pom.xml
git commit -am "Release 0.3.0" && git tag v0.3.0 && git push --tags
```

The `release.yml` workflow verifies the tag matches the pom version, builds `target/imini.jar`, attaches it
and its `.sha256` to an auto-generated **GitHub Release**. (`docker-publish.yml` publishes the container
image for the same tag.) `workflow_dispatch` runs a build-only dry run without publishing.

**Dependency updates.** `.github/dependabot.yml` opens weekly PRs for Maven dependencies, the GitHub
Actions used by the workflows, and the Dockerfile base image. (Renovate is an equivalent alternative;
Dependabot is used here because it is built into GitHub.) When Dependabot bumps the pinned Maven version,
re-pin the wrapper checksum with `sh scripts/pin-maven-checksum.sh`.

**SBOM + vulnerability scan.** `supply-chain.yml` runs on pushes, PRs, and weekly. It generates a
**CycloneDX SBOM** (uploaded as a build artifact) and runs **Trivy** twice: a report step that sends all
HIGH/CRITICAL findings to the **Security -> Code scanning** tab (non-blocking), and a **gate step that fails
the build on a fixable CRITICAL** (`ignore-unfixed: true`, so CVEs with no upstream fix don't block).
The gate runs on PRs/pushes but is skipped on the weekly schedule (which only reports), so a CVE disclosed after a merge surfaces without breaking `main`. Full policy and how to accept an exception via `.trivyignore`: [`docs/SECURITY.md`](docs/SECURITY.md).

**Releases and changelog.** Commit with [Conventional Commits](https://www.conventionalcommits.org/)
(`feat:`, `fix:`, `feat!:` for breaking). `release-please.yml` maintains a "release PR" that bumps the
`pom.xml` version and updates `CHANGELOG.md`; merging it tags `vX.Y.Z`, which triggers `release.yml`
(attaches the jar + `.sha256` to the release) and `docker-publish.yml` (publishes the image). You can still
cut a release manually by bumping `pom.xml` and pushing a matching tag. A pull request that touches the release plumbing
(`pom.xml`, `release.yml`, or the release-please config) triggers a **dry-run** of `release.yml`: it builds
the jar and checksum and uploads them as a workflow artifact (the verify/publish steps are skipped), so a
broken release build is caught before a tag is ever cut.

## Large tool results: the bounded context fold

When a single tool result (a huge file or web page) would blow the model's context window, imini condenses
it before it enters the history. Moderately large results get a cheap head+tail trim. A result that
*vastly* exceeds the window (over `agent.fold-threshold-chars`) is instead **folded**: it is chunked, each
chunk is summarized by the cheap summary model, the summaries are concatenated, and the process recurses
until the digest fits. Unlike truncation, the fold reads every region at least once (lossy by
*compression*, not by *deletion*). It degrades gracefully to head+tail if the summary model is unavailable.

Tune it in `application.properties` (defaults shown): `agent.fold-enabled=true`,
`agent.fold-threshold-chars=24000`, `agent.fold-chunk-chars=8000`, `agent.fold-target-chars=4000`,
`agent.fold-max-depth=2`. Set `agent.fold-enabled=false` for the prior head+tail-only behavior. Background:
[`docs/RECURSIVE_LANGUAGE_MODELS.md`](docs/RECURSIVE_LANGUAGE_MODELS.md).

The fold applies wherever a single oversized input enters context: large **tool results** (including
`search_memory`/retrieval), and large **`@file` references** -- a referenced file over
`context.refs.max-file-kb` is now folded (up to `context.refs.max-fold-file-kb`, default 512 KB) instead of
being skipped, so its gist still reaches the model. Each fold increments the **`context_fold`** counter
(and `context_fold_fallback` when it degrades to head+tail), visible at `GET /metrics` and
`GET /metrics/prom` (`imini_counter{counter="context_fold"}`) -- so you can graph fold activity in the
bundled Grafana dashboard. When a fold happens during a run, a trace event
`[fold:<label>] condensed a large <tool> result: N -> M chars` is streamed to the run log -- it shows in
the web UI's activity trace (highlighted) and in the streamed CLI output, so you can see exactly when and
how much context was compressed.

### The context timeline

Folding is one of three context-management actions imini takes during a run; together they form a
**context timeline** you can watch live:

- **fold** -- a single oversized tool result or `@file` is summarized (`[fold:<label>]`).
- **compact** -- older history is folded into the durable `[MEMORY]` note when the conversation grows past
  `agent.compact-token-threshold` (`[compact:<label>]`).
- **trim** -- a last-resort per-call budget fit truncates/drops messages so a prompt never exceeds the
  window (counted in metrics; logged as `[token-budget]`).

The fold and compact events stream into the web UI activity trace (highlighted) and the run log. A
**trace filter** above the conversation lets you show/hide event categories (tools, guards, plan, fold,
compact, other) so you can isolate just the context timeline -- or hide it. A numeric summary is exposed at
`GET /metrics` under `context` (`{folds, fold_fallbacks, compactions, trims}`) and as
`imini_counter{counter="context_fold"|"context_compact"|"context_trim"|...}` at `GET /metrics/prom`.

**Per-run context report.** Each run's folds, compactions, and trims are attributed to that run and shown
in the admin overview's *recent runs* list (e.g. `2 folds, 1 compact`), and persisted with the run history
(`GET /admin/runs`, `GET /session/runs`) so you can open a past run and see how its context was managed -- expand a run in the admin *recent runs* list to see the actual `[fold]`/`[compact]`/`[trim]` event lines from that run, not just the counts.

**Durable cross-session memory.** A session's `[MEMORY]` note already survives a restart (it is part of the
saved conversation). Now it also carries across *different* sessions: after a run compacts, the note is
written to a durable per-owner store, and a brand-new session is seeded from it. The *Project memory* card lets you **curate** it: hand-edit the auto note, and **pin** facts that always seed
every new session and are never overwritten by compaction. Pinned facts plus the auto note are de-duplicated
when a session is seeded. Endpoints (admin): `GET /memory/durable`, `POST /memory/durable` (edit note),
`POST /memory/durable/pin` / `unpin`, `POST /memory/durable/clear`.

The card also offers **promote to pin**: facts in the auto note that aren't pinned yet appear as one-click candidates, so curating durable memory is a quick review rather than retyping. Durable memory is scoped **per workspace and owner** (keyed by a short hash of the working directory shown in the card), so different projects don't share one note. A **quality guard** keeps the note tight: when the auto note grows past `agent.memory-max-chars` (default 4000), the summary model consolidates it -- merging duplicates and dropping redundancy -- before it is stored (with a head+tail fallback if the model is unavailable). Durable memory is not shared between users.

When a new session starts, durable memory is **relevance-ranked** against your first message: all pinned facts are included, plus the most relevant auto-note facts up to `agent.memory-inject-max` (default 12), scored with the same lexical matcher as `search_memory` (no embedding server needed). Pinned facts carry **provenance** -- each records where it came from (`manual`, `auto note`, or an imported source) and when, shown on hover in the card. Durable memory (note + pins) is included in the signed **workspace bundle**, so *Export workspace* / *Import workspace* carry curated memory between machines (the bundle signature covers the plugin-pack digest; memory and settings ride alongside it, as before).

Ranking can be **semantic**: set `retrieval.embeddings=true` and injection (and the recall tool below) rank durable facts by embedding cosine similarity instead of term overlap, falling back to lexical if the embedding endpoint is unavailable. The agent can also recall on demand via the **`recall_memory`** tool -- it returns the durable facts most relevant to a query mid-conversation (top `agent.memory-recall-k`, default 6), not just what was seeded up front. **Memory analytics** (in the card, and `GET /memory/analytics`) show how often each fact is injected into a session or recalled by the tool, so you can prune facts that never earn their place. Those counts now drive **automatic hygiene**: after a run, durable facts that have been observed for more than `agent.memory-decay-days` (default 30) without ever being injected or recalled are pruned (pinned facts are never touched); you can also run it on demand with the *hygiene* button or `POST /memory/hygiene`. The promote-to-pin candidates are ordered by usage, so the facts actually pulling their weight surface first. Recall is **two-stage**: `recall_memory` first shortlists with the cheap ranker (`agent.memory-recall-shortlist`, default 12), then -- when `agent.memory-rerank=true` -- has the summary model pick and order the most relevant (falling back to the shortlist if the model is unavailable). When embedding ranking is on, each fact's vector is **cached** (in memory and the `embed_cache` table) so facts aren't re-embedded on every seed/recall or after a restart. The in-process cache is a bounded LRU and the `embed_cache` table is pruned to `retrieval.embed-cache-max` (default 4096). The whole durable-memory pipeline (seed → fold/compact → consolidate → hygiene → recall → analytics) is summarized in the *Project memory* card and documented in [`docs/MEMORY.md`](docs/MEMORY.md).

### Health & observability

`GET /health` is a bare liveness check. `GET /healthz` is a **readiness** probe (open, no auth) for deployment and monitoring: it reports overall `status` (`ok` / `degraded` / `down`), database availability, llama-server reachability and its context window, uptime, the context-management summary (folds/compactions/trims), and durable-memory presence. The admin dashboard (`GET /admin/overview`) now also folds in the context-management totals and durable-memory state alongside the recent-runs timeline, so run activity and memory are visible together. The admin card shows a **health dot** (green `ok` / amber `degraded` / red `down`) and a `runs.ndjson` download; `GET /admin/runs.ndjson` (admin) streams recent runs as newline-delimited JSON (one run per line, with per-run fold/compact/trim counts and the event timeline) for external log/trace tooling. The Docker image and `docker-compose.yml` wire a `HEALTHCHECK` to `/healthz`; Kubernetes probe snippets and the full deployment guide are in [`docs/DEPLOY.md`](docs/DEPLOY.md).

`GET /admin/runs/history.ndjson?since=<ts>&limit=<n>` (admin) streams the **full persisted** run history (oldest-first, paginate by passing the last line's `ts` as the next `since`), beyond the in-memory tail that `/admin/runs.ndjson` covers. The admin dashboard and `GET /metrics` now expose **SLO** signals -- run success rate and p50/p95 latency (also as `imini_run_latency_p50_ms` / `_p95_ms` / `imini_run_success_rate` Prometheus gauges). For machine-parseable logs, run with the `json` Spring profile (`--spring.profiles.active=json` or `SPRING_PROFILES_ACTIVE=json`): each log line becomes a JSON object, and requests carry correlation fields (`reqId`, `path`, `user`) in the MDC. That correlation now extends into the **agent loop** (each run adds `runId` + `session`) and **scheduled tasks** (`runKind=scheduled`, `taskId`, `session`), so a run's fold/compact/tool log lines are traceable end to end. For a **durable SLO** that survives restarts, `GET /admin/slo?window=24h` (also `7d`, `30m`, `90s`, `all`) computes success rate and p50/p95 latency from the persisted run history over a real time window -- distinct from the in-memory `/metrics` gauges. Ready-to-use Prometheus alert rules and a Grafana dashboard (now including success-rate and p95 panels) live in [`docs/observability/`](docs/observability/), with a one-command demo stack.

### Reliability & hardening

Transient llama-server failures (network errors, HTTP 5xx) are retried with exponential backoff **plus jitter** -- tune with `llama.max-retries` (default 2) and `llama.retry-backoff-ms` (default 400); 4xx and bad input are never retried. At startup a config validator **fails fast** on contradictory settings (e.g. negative retry counts, `persistence.enabled=true` with a blank db-path) and warns on risky ones (e.g. `auth.enabled=false` leaving the API unauthenticated). Secrets are masked in any log line (and the validator confirms a signing secret is set without printing it). The admin dashboard's recent-runs view is now a **per-run trace**: expand a run to see its event timeline (fold / compact / trim / tool / error chips) with the run's session and outcome. A **circuit breaker** around the llama client opens after `llama.circuit-breaker-threshold` (default 5) consecutive transient failures and fails fast during a `llama.circuit-breaker-cooldown-ms` (default 30s) cooldown — so a sustained server outage drains the retry budget only once rather than on every call; the current state (`closed`/`open`/`half_open`) is visible in `GET /healthz` under `llama.circuitBreaker`. The `run_command` sandbox now runs every process with the workspace root as its working directory and caps output at `sandbox.max-output-bytes` (default 64 KB). On SIGTERM imini stops accepting new runs and waits up to `agent.shutdown-drain-seconds` (default 30) for in-flight runs to finish before shutting down.

### Session lifecycle & rate limiting

Set `agent.session-ttl-hours` (default 0 = disabled) to have a background reaper prune sessions idle longer than the TTL every `agent.session-reap-interval-minutes` (default 60); `GET /sessions/summary` (admin) shows the age/size distribution and `POST /sessions/prune` (admin) runs a pass on demand. Pruning a session now **cascades** to every dependent table (owners, shares, titles, checkpoints, plans, plan steps, plan history, per-session skill state and settings, and bound scheduled tasks), and each reaper pass also runs an **orphan sweep** that removes child rows whose parent session no longer exists (cleaning up data left by older builds). The per-key rate limiter (`auth.rate-limit-per-minute`) is **persistent** by default (`auth.rate-limit-persistent=true`) — windows are stored in SQLite so limits survive a restart; set it to `false` for in-memory. Choose the algorithm with `auth.rate-limit-algorithm`: `fixed` (default) or `sliding` — the sliding-window counter removes the burst-at-window-boundary weakness of the fixed window by weighting the previous window's count into the current rate. A background pruner (`auth.rate-limit-reap-interval-minutes`, default 10) drops stale rate-limit windows for keys that have gone quiet so the table stays bounded. The streaming model path retries the **connection step** (before any tokens flow) under the same circuit breaker as the non-streaming calls; mid-stream failures are still surfaced to the caller.

### Eval harness, distributed tracing & per-tenant cost

**Eval harness.** `POST /admin/eval` (admin) runs a small fixed suite through the live agent and returns a pass-rate plus per-case detail, so prompt/model/refactor changes can be checked for quality regressions — not just that the code compiles. It self-skips (returns `{skipped:true}`) when the model is unreachable. The scoring (contains / regex / normalized-equals) is pure and unit-tested; supply your own suite for a real domain. An opt-in CI workflow (`.github/workflows/eval-gate.yml`) boots a tiny model, runs this suite, and **fails the build if the pass-rate drops below a threshold** — turning the harness into an automatic quality gate. It does not run on every push (it needs a model and CPU inference); trigger it manually from the Actions tab, or add the `run-eval-gate` label to a PR. **Distributed tracing.** Set `tracing.enabled=true` to emit OpenTelemetry-style spans (W3C `trace_id`/`span_id`/`parent_id`, timing, attributes) for every run endpoint (`/ask`, `/chat`, and both streaming variants); view recent spans at `GET /admin/traces` (admin). Spans nest within a run and carry a `traceparent`. **Cross-service propagation** is automatic: an inbound `traceparent` header continues the caller's trace into imini. Set `tracing.otlp-endpoint` (e.g. `http://localhost:4318/v1/traces`) to **export** each finished span to a real OTLP/HTTP collector (Jaeger, Tempo, the OTel Collector) as OTLP/JSON — best-effort and off the request thread, so it never slows or breaks a request. It's dependency-free: a compact, readable tracer rather than the full OTel SDK. **Per-tenant cost & quotas.** Every run records input/output tokens against the calling user in the `cost_ledger`, priced via `cost.input-usd-per-million` / `cost.output-usd-per-million` (0 for a free local model); see per-tenant usage for the month at `GET /admin/cost` (admin). A soft monthly token quota returns HTTP 429 on **all run endpoints** (`/ask`, `/chat`, `/ask/stream`, `/chat/stream`) once a tenant exceeds it. Quotas can be **tiered**: define named tiers with `cost.tiers` (e.g. `free=100000,pro=5000000`) and assign tenants with `cost.tier-assignments` (e.g. `alice=pro,bob=free`); a tenant with no assignment uses the default `cost.monthly-token-quota` (0 = unlimited).

### Capability scoping, secret redaction, rate limiting & admin dashboards

**Tool capability scoping.** On top of the ASK/AUTO/PLAN gating, you can restrict *which tools a caller may invoke at all*, by role. Set `capabilities.enabled=true` and define per-role allowlists with `capabilities.scopes` (e.g. `reader=read_file|view_dir|grep|web_search, operator=*`); `*` means every tool, and a scope token ending in `*` matches by prefix, so `github_*` permits every tool exposed by the `github` **MCP** server. A role with no entry falls back to `capabilities.default-scope`. The `admin` role is always unrestricted. A scoped-out tool is denied before it executes — including read-only tools, so a reporting bot can be allowed `read_file`/`grep` but denied `run_command` entirely. Scoping now also covers **delegated sub-agent runs**: the original caller's role is propagated into the sub-agent, so a `delegate_research` task can't reach tools the caller itself isn't allowed to use. Every denial is written to the audit log (`capability_denied`). View the resolved scopes at `GET /admin/capabilities` (admin). Off by default (every tool permitted), so existing behaviour is unchanged.

**Secret / PII redaction.** Trace span attributes and log messages are scrubbed of secret- and PII-shaped values — bearer tokens, `key=value` secrets, `sk-`/AWS/JWT tokens, and email addresses are masked (e.g. `Bearer ****`, `api_key=****`, `****@****`). Trace-attribute redaction is governed by `redaction.enabled` (on by default). Log redaction now covers **both logging profiles**: the default console profile via the `%rmsg` converter, and the structured `json` profile via `RedactingJsonEncoder`, which scrubs the output of Logback's built-in `JsonEncoder` (so neither plaintext nor JSON logs leak secrets). It is best-effort pattern matching, not a guarantee that every secret is caught.

**Custom redaction patterns.** Beyond the built-ins, operators can mask their own secret/PII shapes without code changes via `redaction.patterns`: entries separated by `;;`, each a regex optionally followed by `=>replacement` (default `****`). For example `redaction.patterns=EMP-\d{6}=>EMP-****;;(?i)\bpassword\b\s*\S+` masks internal employee IDs and password-ish values. Custom patterns run after the built-ins and apply everywhere redaction does (trace attributes, console, and JSON logs); invalid regexes are skipped at startup rather than failing the boot.

**Per-tenant spend alerts.** With `cost.alert-token-threshold` and/or `cost.alert-percent` set, imini logs a one-time warning the first time a tenant's monthly tokens cross the threshold (the lower of the absolute count and the percent-of-quota), **and records it to the audit log** (`spend_alert`) so it survives a restart and is queryable at `GET /audit`. Recent alerts also appear in `GET /admin/cost` and on the dashboard. **Usage dashboard.** `GET /admin/usage` (admin) renders a self-contained HTML page of per-tenant token usage, cost, run counts, quota-use bars, and recent spend alerts — a human-readable view of the same data `/admin/cost` returns as JSON.

**Per-tool rate limiting.** On top of capability scoping ("*may* you use this tool?"), you can cap *how often* an expensive tool runs per tenant with `tool-rate-limit.enabled=true` and `tool-rate-limit.limits` — comma-separated `tool=limit/windowSeconds` entries (e.g. `web_fetch=10/60, run_command=5/60`). Limits use the same sliding-window estimator as the HTTP limiter and are keyed per tenant, so one noisy tenant can't exhaust another's budget; a tool with no entry is unlimited. A throttled call returns `RATE_LIMITED: tool '...'` to the model (which can proceed with what it has) and is recorded to the audit log (`tool_rate_limited`). View the configured limits at `GET /admin/tool-rate-limits` (admin). State is in-memory, so limits reset on restart. Off by default.

**Audit-log viewer.** `GET /admin/audit.html` (admin) renders a filterable HTML view over the audit table — the same data as `GET /audit`, but browsable. Filter by user, action, and target via the form (which round-trips to query params), with capability denials, spend alerts, and tool rate-limit rejections highlighted. This surfaces the now-durable security events the rest of the stack records. Raw JSON remains at `/audit`, and CSV/JSON export at `/audit/export`.

**Context-budget pre-flight.** As you type, a readout under the composer estimates the prompt size against
the model's window and predicts which actions would fire -- `fits`, `would compact`, or `would trim`. It is
backed by `GET /budget/preflight?sessionId=&prompt=`, which returns the estimated tokens, the prompt cap,
the server context window, and the `wouldCompact` / `wouldTrim` predictions, so you can split a too-large
request before sending it. When the estimate exceeds the window (`recommendPlanMode`), the readout shows a
**use plan mode** link that switches the run to plan mode in one click -- plan mode breaks the request into
steps that each fit, turning the prediction into a remedy.

## Common helper scripts

Every script ships in two forms: `*.bat` for native Windows, and `*.sh` for macOS/Linux/WSL (and Git Bash).
They are thin `curl` wrappers around the HTTP endpoints below, so they behave identically.

| Windows | macOS / Linux / WSL | Purpose |
|---|---|---|
| `run.bat` | `./run.sh` | Start the app and local model server |
| `ask.bat "question"` | `./ask.sh "question"` | One-shot prompt |
| `chat.bat SESSION "message"` | `./chat.sh SESSION "message"` | Multi-turn session prompt |
| `stream.bat SESSION "message"` | `./stream.sh SESSION "message"` | Streaming session prompt |
| `plan.bat "request"` | `./plan.sh "request"` | Plan mode: record proposed mutations without running them |
| `rewind.bat` | `./rewind.sh` | Rewind the last checkpointed edit |
| `interrupt.bat SESSION` | `./interrupt.sh SESSION` | Stop a running session |
| `steer.bat SESSION "guidance"` | `./steer.sh SESSION "guidance"` | Inject guidance into a running session |
| `runs.bat` | `./runs.sh` | Show active and queued runs |
| `eval.bat` | `./eval.sh` | Run the behavioral eval suite (`evals/cases.json`; `.sh` needs `jq`) |

Set `IMINI_URL` to target a non-default address (the `.sh` scripts read it; default
`http://localhost:8080`).

## HTTP endpoints

| Method and path | Purpose |
|---|---|
| `POST /ask` | One-shot prompt, blocking response (add `"plan":true` to plan-then-execute) |
| `POST /chat` | Multi-turn session prompt, blocking (`"plan":true` to plan; `"resume":true` to resume) |
| `POST /ask/stream` | One-shot prompt over SSE |
| `POST /chat/stream` | Session prompt over SSE |
| `GET /sessions` | List sessions (ids the caller can read) |
| `GET /session?id=` | Read one session |
| `GET /session/titles` | Friendly titles for readable sessions (`id -> title`) |
| `POST /session/rename?sessionId=&title=` | Set/clear a session's title (owner/admin) |
| `GET /session/settings?sessionId=` | A session's durable settings (e.g. default mode) |
| `POST /session/settings?sessionId=&key=&value=` | Set a per-session setting (owner/admin) |
| `POST /session/settings/clear?sessionId=&key=` | Clear a per-session setting |
| `POST /session/fork?sessionId=&title=` | Copy a session (conversation + plans + todos) into a new one you own |
| `GET /settings/token-budget` | Current token budget, enforced prompt cap, and server `n_ctx` |
| `POST /settings/token-budget?tokens=` | Set the per-call token budget at runtime (admin) |
| `GET /schedule` | List scheduled local tasks |
| `POST /schedule?...` | Schedule a prompt (delay, optional repeat interval, kind run/plan/loop) |
| `POST /schedule/cancel?id=` | Cancel a scheduled task |
| `GET /plugin` | Counts of installable content (skills/agents/commands) |
| `GET /plugin/export` | Download a plugin pack (skills + agents + commands) as JSON |
| `POST /plugin/install` | Install a plugin pack (JSON body) into the workspace (admin) |
| `POST /plugin/install-url?url=&sha256=` | Install a pack from a URL, verified by SHA-256 (admin) |
| `GET /plugin/registry?url=` | Browse a registry index (list advertised packs) |
| `POST /plugin/registry/install?url=&name=` | Install a pack by name from a registry, pinned to its hash (admin) |
| `POST /plugin/registry/entry?name=&version=&url=` | Build a registry index entry (with SHA-256) for your pack (admin) |
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
| `POST /init?write=&overwrite=&augment=` | Scan the repo and draft `CLAUDE.md`; create, replace, or merge-in missing sections |
| `GET /preview?sessionId=` | Staged patch previews for the browser diff viewer |
| `POST /preview/apply?sessionId=&id=` | Apply a staged preview (re-validates + snapshots) |
| `POST /preview/discard?sessionId=&id=` | Drop a staged preview |
| `GET /health` | Health check |
| `GET /me` | Current caller identity (`user`, `role`) |
| `GET /metrics` | Metrics snapshot (admin only) |
| `GET /admin/overview` | Consolidated admin dashboard snapshot, incl. recent runs (admin only) |
| `GET /admin/runs?limit=&endpoint=&outcome=&session=` | Recent runs, filterable by endpoint/outcome/session (admin) |
| `GET /session/runs?sessionId=&limit=` | Recent runs for one session (session read access) |
| `GET /schedule/runs?id=&limit=` | Recent executions of one scheduled task (session read access) |
| `GET /metrics/prom` | Metrics in Prometheus text format, for scraping (admin) |
| `GET /workspace/export` | Download the whole workspace as one bundle (admin) |
| `GET /workspace/keys` | Inspect the verifier keyring: trusted keys, expiry, revoked/signer flags (admin) |
| `POST /workspace/keygen` | Mint an Ed25519 key pair for bundle signing (admin) |
| `POST /plugin/registry/sign` | Sign a registry index over its canonical listing digest (admin) |
| `POST /workspace/import/preview` | Dry-run an import: what would change, writes nothing (admin) |
| `POST /workspace/import` | Import a whole-workspace bundle (admin) |
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

**In the web UI.** The **Project memory** card mirrors `/memory`: it lists every memory file in load
order with its source/reason and size (skipped files dimmed, imports nested), so you can see at a glance
what context the agent is actually running with. (The separate *Memory search* card is retrieval over
the indexed workspace -- a different thing.)

### Bootstrapping memory with `/init`

Don't have a `CLAUDE.md` yet? Type `/init` in chat. `imini` scans the repository -- detecting the build
system, primary languages, and top-level layout -- and drafts a `CLAUDE.md` scaffold with sections for
overview, build/test commands, layout, conventions, and agent notes. The scan is **deterministic** (no
model call), so it works reliably even with a weak local model.

- If `CLAUDE.md` does **not** exist, `/init` writes it and reports what it found; it is immediately
  picked up as project memory (confirm with `/memory`). Fill in the Conventions/Notes sections.
- If `CLAUDE.md` **already** exists, `/init` **improves it in place without replacing your content**: it
  appends only the scaffold sections your file is missing (under a clear `<!-- Added by imini /init -->`
  marker) and reports what it added. Existing sections -- including ones you hand-wrote -- are never
  touched, and if nothing is missing it leaves the file unchanged. This is append-only and safe to re-run.

For explicit control over the endpoint: `POST /init?write=true` creates the file when absent;
`&augment=true` merges missing sections into an existing file (preserving content); `&overwrite=true`
replaces it entirely. Without `write` it returns a preview (build system, languages, missing sections,
draft).

```
curl -X POST "localhost:8080/init"                          -H "X-API-Key: <key>"   # preview only
curl -X POST "localhost:8080/init?write=true"               -H "X-API-Key: <key>"   # create if absent
curl -X POST "localhost:8080/init?write=true&augment=true"  -H "X-API-Key: <key>"   # add missing sections, keep content
```

### Why memory matters (and how it differs from skills and subagents)

**What memory is.** "Memory" here is *persistent project context* -- a few Markdown files in the repo
that are read on every turn and prepended to the model's system prompt. It is how the agent knows the
things that are true for *this* project across *all* sessions: the build/test commands, the directory
layout, the conventions to follow, and the gotchas to avoid.

**Why it's useful in an agent system.** A language model starts each conversation with no memory of your
repo. Without project context it re-derives the same facts every time (often wrongly on a weak local
model) -- guessing the build command, inventing a directory, ignoring your style. Loading a small, fixed
set of files turns those repeated guesses into *given* facts, which is exactly what makes an agent feel
like it "knows" your codebase. Because the load is **deterministic** (a fixed order, no model call), the
behavior is predictable and testable -- you can always run `/memory` to see precisely what the agent is
operating with, and why each file was included or skipped.

**How memory differs from skills and subagents** -- three distinct extension points:

| Mechanism | What it is | When it applies | Who triggers it |
|---|---|---|---|
| **Memory** (`CLAUDE.md`, rules) | Always-on project facts/conventions | Every turn, automatically | The harness (loaded into the prompt) |
| **Skills** (`skills/*.md`) | On-demand instruction bundles for a task | Only when invoked / auto-loaded | You (`/skill`) or the model (`load_skill`) |
| **Subagents** (`agents/*.md`) | A separate, tool-scoped agent loop | Only when delegated to | You (`/agent`) or the model (`delegate_agent`) |

In short: memory is *passive and ever-present* (context the agent always has), a skill is *a procedure
you reach for* (instructions for a particular kind of task), and a subagent is *a worker you hand a job
to* (an isolated loop that returns a summary). They compose -- e.g. a `code-review` skill can rely on the
conventions your `CLAUDE.md` memory establishes.

## Image (multimodal) input

If your local model is vision-capable, you can attach an image to a one-shot `ask` and the harness sends
it to the model in the OpenAI `image_url` format. Because most local llama.cpp builds are **text-only**,
this is **capability-gated**: image input is active only when `model.vision-enabled=true` *or* llama-server
reports a vision capability via `/props`. On a text-only model the image is dropped and a short note is
added to the prompt, so the turn still runs instead of erroring.

Attach an image by adding `image` (raw base64 or a full `data:` URL) and optional `imageType` to the
request body:

```
curl -X POST localhost:8080/ask -H "X-API-Key: <key>" -H "Content-Type: application/json" -d '{
  "question": "What does this screenshot show?",
  "image": "data:image/png;base64,iVBORw0KGgo...",
  "mode": "auto"
}'
```

To run a vision model, start llama-server with a multimodal projector (e.g. `--mmproj <model.mmproj>`)
and set `model.vision-enabled=true`.

> Honest scope: image input is supported on the one-shot `ask` path (a single multimodal turn; it is not
> threaded through plan/loop or multi-turn chat history). Capability detection via `/props` is best-effort
> -- if it can't tell, set `model.vision-enabled` explicitly. The harness does not resize or re-encode the
> image; very large images count against the token budget and may be trimmed.

## Plugins -- shareable packs of skills, agents, and commands

`imini`'s extensibility lives in plain Markdown: `skills/`, `agents/`, and `commands/`. A **plugin pack**
bundles those into one portable JSON file you can share or move between workspaces.

- **Export:** the **Plugins** card's *Export pack* button (or `GET /plugin/export?name=...`) downloads a
  `<name>.imini-plugin.json` containing every skill, agent, and command in the workspace.
- **Install:** paste a pack into the card and click *Install pack* (or `POST /plugin/install` with the
  JSON body; admin only). Each entry is written to its folder (`skills/<name>/SKILL.md`,
  `agents/<name>.md`, `commands/<name>.md`); existing files are skipped unless you check *overwrite*.

```
curl "localhost:8080/plugin/export?name=my-pack" -H "X-API-Key: <key>" > my-pack.imini-plugin.json
curl -X POST "localhost:8080/plugin/install?overwrite=false" -H "X-API-Key: <admin-key>" \
     -H "Content-Type: application/json" --data @my-pack.imini-plugin.json
```

A pack is a small manifest: `{ "format": "imini-plugin/1", "name", "version", "description", "entries":
[ { "type": "skill|agent|command", "name", "content" } ] }`.

**Install from a URL (a registry), verified by SHA-256.** Mirroring the remote-skill install, you can
install a pack straight from a URL and pin its hash so you get exactly the bytes you expect:

```
curl -X POST "localhost:8080/plugin/install-url?url=https://example.com/my-pack.imini-plugin.json&sha256=<hex>" \
     -H "X-API-Key: <admin-key>"
```

The pack is fetched (http/https only), its SHA-256 is computed and compared to the one you supply, and the
install is **refused on mismatch**. Omitting the hash is allowed but reported as `unpinned (not verified)`
-- pin it for anything you didn't produce yourself. The *Plugins* card has URL + sha256 fields for the
same flow.

**Discover packs with a registry index.** Rather than knowing each pack's URL, point the harness at a
*registry index* -- a JSON document that lists available packs -- to browse and install by name:

```json
{ "format": "imini-registry/1", "name": "my-registry", "packs": [
    { "name": "web-tools", "version": "2", "description": "web helpers",
      "url": "https://example.com/web-tools.imini-plugin.json", "sha256": "<hex>" } ] }
```

```
curl "localhost:8080/plugin/registry?url=https://example.com/registry.json" -H "X-API-Key: <key>"     # browse
curl -X POST "localhost:8080/plugin/registry/install?url=https://example.com/registry.json&name=web-tools" \
     -H "X-API-Key: <admin-key>"                                                                       # install by name
```

Installing by name reuses install-by-URL and **pins the registry's declared SHA-256**, so you get exactly
the bytes the registry advertises (refused on mismatch; `unpinned` if the registry omits a hash). Set a
default registry with `plugins.registry-url=` so you can browse without passing `url=` each time. The
*Plugins* card has a **Browse registry** button that lists packs with per-pack install links.

**Publishing a pack to a registry.** To host your own registry, export a pack, put it at a URL, and add
an entry to your index. The harness builds the entry for you -- including the pack's SHA-256 -- with
`POST /plugin/registry/entry?name=&version=&url=` (the *Plugins* card has a **Build entry** field):

```
curl -X POST "localhost:8080/plugin/registry/entry?name=web-tools&version=2&url=https://example.com/web-tools.imini-plugin.json" \
     -H "X-API-Key: <admin-key>"
# -> {"name":"web-tools","version":"2","description":"","url":"https://...","sha256":"<hex>"}
```

Paste the returned object into your registry index's `packs` array. The `sha256` matches what the pack
will hash to, so anyone installing it gets the verified bytes.

**Signed plugin packs.** When signing is configured (`bundle.signing-private-key` or
`bundle.signing-secret`), `GET /plugin/export` and the workspace export embed a `signature` over the pack's
content digest -- the same schemes (Ed25519/HMAC) and keyring as workspace bundles. On install (including
install-by-URL and from a registry), imini verifies the pack's signature against the configured
keyring/secret and reports a `signature` status in the result. Set `plugins.require-signature=true` to
**refuse** unsigned or unverified packs entirely.

**Signed registry index.** The registry *listing document* itself can be signed, so a consumer can trust
the index came from a known publisher (not just that each pack hashes correctly). `POST /plugin/registry/sign`
(admin) takes an index JSON and returns it with a `signature` embedded over a canonical digest of its
listings (sorted, order-independent). When you fetch a registry, the result includes a `signature` status
for the index; with `plugins.require-signature=true`, installing from a registry whose index does not
verify is refused (its status is reported as `indexSignature`). The **Browse registry** view in the
*Plugins* card shows this index-signature status as a banner above the pack list (green when `verified`),
with a reminder that individual packs remain SHA-256 pinned regardless.

> Honest scope: the registry is just a fetched JSON list -- there is no central/official index, no
> signing or trust-root in the registry itself, and no dependency resolution. The SHA-256 pin protects
> integrity (you get the advertised bytes); a pack *signature* adds provenance (who built it) when the
> publisher signs and you trust their key. The publish helper hashes the pack as it would be exported now;
> re-run it if the workspace changes.

> Honest scope: install **validates and sanitizes every entry** -- the `type` must be one of
> skill/agent/command and the `name` is reduced to a safe bare id (no path separators or `..` traversal),
> so a pack can never write outside `skills/`, `agents/`, or `commands/`. Packs are content only (Markdown
> instructions), not executable code; installing one is exactly like adding those files yourself. There is
> no version/dependency resolution or signing yet -- treat third-party packs as you would any files you
> drop into your repo.

## `/loop` -- iterate until green

`/loop` makes the agentic *iterate-until-green* pattern a first-class, **bounded** command: make a focused
change toward a goal, run a check, and repeat until the check passes or an attempt budget is spent (so it
can never spin forever).

```
/loop [check=<command>] [attempts=N] <goal>
```

- `/loop check="mvn -q test" attempts=4 make UserServiceTest pass` -- up to 4 attempts; after each, run
  `mvn -q test`; stop as soon as it exits 0, or after 4 tries. On a failed check the next attempt is
  given the failure output to fix.
- `check=` is optional (omit it and the goal simply runs once); quote a command with spaces.
- `attempts=` is clamped to `agent.loop.hard-max-attempts` (default 20); the default budget is
  `agent.loop.max-attempts` (5).

The check runs through the **same Sandbox screening** as `run_command` (off / deny-list / allowlist), in
the workspace root with a timeout -- success is exit code 0. Each attempt is a normal turn, so it also
benefits from the token budget and automatic plan-mode fallback. `/loop` supersedes the bundled `loop`
*skill* (which remains as an example of the same idea expressed as instructions).

## Scheduled local tasks

Run a prompt later, or on a fixed interval, **unattended** -- e.g. "every 10 minutes, run the tests and
summarize failures." Tasks run in **AUTO** permission mode (there is no user present to answer approval
prompts) as a normal run, a plan, or a `/loop`.

In the web UI, the **Scheduled tasks** card adds/lists/cancels tasks. Over HTTP:

```
# run once, 60s from now
curl -X POST "localhost:8080/schedule?sessionId=proj&prompt=run%20the%20tests&kind=run&delaySeconds=60" -H "X-API-Key: <key>"
# repeat every 10 minutes as a /loop
curl -X POST "localhost:8080/schedule?sessionId=proj&prompt=check=mvn%20-q%20test%20keep%20the%20build%20green&kind=loop&delaySeconds=60&repeat=true&intervalSeconds=600" -H "X-API-Key: <key>"
curl "localhost:8080/schedule" -H "X-API-Key: <key>"                       # list
curl -X POST "localhost:8080/schedule/cancel?id=task-1" -H "X-API-Key: <key>" # cancel
```

> Honest scope: scheduling is **durable** (tasks are persisted to SQLite and reloaded on startup -- an
> overdue task fires shortly after restart rather than instantly) but **single-node**. A minimum
> interval/delay (10s) and a max task count bound local load. Scheduled runs use the same sandbox/
> permission rules; because they run in AUTO mode, only enable them for sessions/workspaces where
> unattended tool use is acceptable.


**Per-task run history.** Each task keeps a short in-memory log of its recent executions
(status, latency, when). `GET /schedule/runs?id=<taskId>` returns them (newest first), and the
Scheduled-tasks card has a **history** link per task. Scheduled runs also feed the global run
history and the metrics (as the `/schedule:<kind>` endpoint), so they appear in the admin
dashboard and `imini_runs_by_endpoint` too.

> Per-task history is now **durable**: executions are persisted to the `scheduled_task_runs` table
> (pruned to `agent.schedule.run-history.persist-max`, default 50 per task) and a tail is reloaded on
> startup, so it survives a restart along with the `runs` counter and `lastDetail`.

## Token budget and context limits

A local model has a fixed **context window** (`n_ctx`). If a request's prompt exceeds it, llama-server
rejects the whole call:

```
llama-server error 400: request (8509 tokens) exceeds the available context size (8192 tokens)
```

`imini` prevents this with a **configurable per-call token budget**. Before every model call the harness
measures the prompt (llama-server's real `/tokenize` count, with a `chars/4` fallback) and, if it would
exceed the enforced cap, **shrinks the message list to fit** -- deterministically and structure-aware:

1. oversized individual messages (a big tool result or pasted file) are **condensed/truncated** to a fair
   share, with a `...[trimmed to fit the token budget]...` marker;
2. if still over, the **oldest middle messages are dropped** (the system message and the latest message
   -- which carries your actual request -- are always kept);
3. as a last resort the latest (then system) message is truncated, so the call *always* fits.

This complements the existing higher-level compaction (`ContextManager`, which folds old turns into a
durable `[MEMORY]` note); the budget is the **hard backstop** that guarantees the request never 400s.

**The budget.** `agent.max-prompt-tokens` (default **8500**) is the maximum tokens for one call (prompt +
the reserved response). The enforced **prompt cap** is `budget − agent.max-tokens`, and is additionally
**clamped to the server's detected `n_ctx`** -- so even a budget larger than the model's window cannot
overflow it. On an 8192-context server with the defaults, the prompt is capped at `min(8500, 8192) − 1024
= 7168` tokens, which fixes the error above out of the box.

**Changing it.** Set it in the config file:

```
agent.max-prompt-tokens=8500
```

or at runtime in the web UI's **Token budget** card, or over HTTP. Runtime changes are now **persisted**
(to the `app_settings` table) and reloaded on restart, so a budget you set in the UI sticks:

```
curl "localhost:8080/settings/token-budget"                 -H "X-API-Key: <key>"   # view
curl -X POST "localhost:8080/settings/token-budget?tokens=7000" -H "X-API-Key: <admin-key>"  # set
```

> Tip: set the budget at or below your server's context size. Lower it if you still see context errors
> (e.g. a very large system prompt or memory file); raise it if your model has a bigger window.
>
**Automatic plan-mode fallback.** You don't have to remember to pass `plan=true`. When a *normal* turn's
assembled prompt would exceed the enforced cap, `imini` automatically runs it in **plan mode** instead of
trimming context to force a one-shot answer -- it decomposes the work into steps, each sent within the
budget. The trace shows `[budget] first prompt ~N tok > cap C; auto-switching to plan mode ...`. This is
on by default; disable it with `agent.plan.auto-fallback=false` (then over-budget prompts are simply
trimmed to fit, as before). A turn that explicitly requested plan mode is never re-triggered.

> Honest scope: token measurement is exact when `/tokenize` is reachable and an estimate otherwise, so a
> small safety margin (the response reservation + the `n_ctx` clamp) absorbs estimation error. Plan-mode
> fallback helps when the *task* is large/multi-part (each step sends a focused prompt); it does not help
> when a single huge artifact -- e.g. one enormous `@file` or a very large `CLAUDE.md` -- dominates the
> system prompt, since every plan step still carries it. In that case reduce the input or raise the
> budget. The budget bounds the prompt we send; it does not change the model's actual context window.

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

## Per-session settings (durable)

A session can remember its own **default permission mode**, so you don't have to pass `mode` on every
request. It is layered as: an explicit per-request `mode` wins; otherwise the session's stored default;
otherwise the global default (`ask`). The setting persists across restarts (the `session_settings` table)
and is independent of per-session skill toggles, which already persist.

In the web UI, the session toolbar has a **default mode** dropdown (`(global)` / `ask` / `auto` / `plan`).
Over HTTP:

```
curl "localhost:8080/session/settings?sessionId=proj" -H "X-API-Key: <key>"                       # view
curl -X POST "localhost:8080/session/settings?sessionId=proj&key=mode&value=auto" -H "X-API-Key: <key>"  # set
curl -X POST "localhost:8080/session/settings/clear?sessionId=proj&key=mode" -H "X-API-Key: <key>"       # clear -> global
```

Setting the default mode applies to the persistent `chat` paths (the one-shot `ask` uses an ephemeral
session, so pass `mode` there). Values are validated (`mode` must be `ask`/`auto`/`plan`); setting
requires write access to the session.

**Seeing which mode a turn used.** Because the mode can now come from a session default, each run logs
the resolved mode to its trace -- a `[mode] running in auto` line appears in the live activity/stream --
and the admin **run history** records the resolved mode per run. So you can always tell whether a turn
ran in ask, auto, or plan and why.

> Honest scope: the only per-session key today is `mode` (the validation/precedence is a small pure
> resolver). The store is generic (`session_settings`), so adding keys later is a one-line whitelist
> change. A fork starts without inherited settings; clearing a key reverts to the global default.

## Session fork, rename, and export

Three small lifecycle conveniences in the toolbar (and over HTTP):

- **Rename** -- give a session a friendly title instead of a random id. The session picker shows
  `My refactor  (chat-1a2b)`; titles are normalized (trimmed, single-spaced, capped at 80 chars).
  `POST /session/rename?sessionId=...&title=...` (blank `title` clears it).
- **Fork** -- branch your work: copy a session's conversation, plan history, and todos into a **new**
  session that you own, leaving the original untouched. Handy before trying a risky direction. The new
  session is titled `fork of <name>` by default (it won't stack into `fork of fork of ...`).
  `POST /session/fork?sessionId=...` returns the new id.
- **Export** -- one click downloads the current session as a `*.imini-session.json` bundle (the same
  bundle described below), so you can archive it or import it elsewhere.

```
curl -X POST "localhost:8080/session/rename?sessionId=proj&title=Payments%20refactor" -H "X-API-Key: <key>"
curl -X POST "localhost:8080/session/fork?sessionId=proj"                              -H "X-API-Key: <key>"
```

> Honest scope: fork copies conversation + plan history + todos (the same content the export bundle
> carries); it does not copy per-session skill overrides or the shared-with list (a fork is yours, and
> starts private). Rename/fork require write access to the source (owner/admin/unowned); fork only needs
> read access since it creates a new session you own. Titles are display-only metadata.

## Whole-workspace bundle (export / import)

Beyond per-session export and single plugin packs, you can back up or clone an **entire setup** in one
file. `GET /workspace/export` (admin) downloads a `*.imini-workspace.json` bundle containing the plugin
pack (all skills, agents, and commands) plus the durable app settings; `POST /workspace/import` (admin)
installs the pack and applies the settings. The *Plugins* card has **Export workspace** / **Import
workspace** buttons (with an overwrite toggle).

```
curl "localhost:8080/workspace/export" -H "X-API-Key: <admin-key>" -o workspace.imini-workspace.json
curl -X POST "localhost:8080/workspace/import/preview" -H "X-API-Key: <admin-key>" \
     -H "Content-Type: application/json" --data-binary @workspace.imini-workspace.json   # dry run
curl -X POST "localhost:8080/workspace/import?overwrite=false" -H "X-API-Key: <admin-key>" \
     -H "Content-Type: application/json" --data-binary @workspace.imini-workspace.json
```

**Preview before you import.** `POST /workspace/import/preview` (admin) parses a bundle and reports what an
import *would* do -- pack entries that would be **created** vs **overwritten** vs **blocked**, and settings
that are **new** vs **changed** vs **unchanged** -- while writing nothing. The *Plugins* card has a
**Preview import** button next to **Import workspace**. Use it to check overwrites before applying.

**Signing bundles (optional).** imini can **sign** exported bundles and **verify** them on import (over the
pack's digest), in two schemes:

- **Public-key (Ed25519), preferred.** Mint a key pair with `POST /workspace/keygen` (or the *Plugins*
  card's **keygen** button). The signer sets `bundle.signing-private-key`; verifiers set only
  `bundle.signing-public-key`. Because verification needs only the public key, a verifier cannot forge a
  signature -- this is true third-party provenance.
- **Shared-secret (HMAC-SHA256).** Set `bundle.signing-secret` to the same value on both ends. Simpler, but
  symmetric: anyone who can verify can also sign.

The bundle records which scheme it used (`signatureAlg`) and, for Ed25519, the signer's `keyId`. On
import, a signature that does not verify is **refused**; an unsigned bundle, or no configured key/secret
for the bundle's scheme, is reported but allowed. Both `import` and `import/preview` return a `signature`
field (`verified` / `invalid` / `unsigned` / `no-key`).

**Verifier keyring.** A verifier can trust **several** publisher public keys at once. Set
`bundle.verify-public-keys` to a comma/newline-separated list of entries, each `keyId:base64PublicKey` or a
bare `base64PublicKey` (key id derived from the key). The legacy single `bundle.signing-public-key` is also
trusted. A signed bundle names the signer's `keyId` so verification picks the right key fast, then falls
back to trying every trusted key. This turns single-signer verification into a small web of trust.

**Key rotation and revocation.** A keyring entry may carry an **expiry**: append `@<epochMillis>` to the
key (e.g. `alice:<base64>@1767225600000`). Past its expiry a key is no longer trusted, though imini can
still tell a signature *was* made by that key (so the status is `expired`, not a bare `invalid`). To retire
a compromised key immediately, add its id to `bundle.revoked-key-ids` (comma/newline-separated); a
signature matching a revoked key verifies as `revoked`. Imports refuse `expired`/`revoked`/`invalid`
signatures. Together these let trust change over time without rewriting every bundle: rotate by adding a
new key (new `keyId`) and expiring the old, or revoke on compromise.

**Key management in the UI.** The *Plugins* card has a **keys** button (next to **keygen**) that lists the
verifier keyring via `GET /workspace/keys`: each trusted key's id, whether signing is enabled, which key is
this signer's, and per-key **trusted / expires &lt;date&gt; / expired / revoked** status. It is the
read-only view of the `bundle.verify-public-keys` / `bundle.revoked-key-ids` config, so you can see at a
glance what would verify before importing a bundle or installing a pack.

```
# public-key: signer has the private key, verifier has the public key
curl -X POST "localhost:8080/workspace/keygen" -H "X-API-Key: <admin>"      # -> {alg, publicKey, privateKey}
curl "localhost:8080/workspace/export" -H "X-API-Key: <admin>" -o ws.json   # signed (Ed25519)
curl -X POST "localhost:8080/workspace/import/preview" -H "X-API-Key: <admin>" \
     -H "Content-Type: application/json" --data-binary @ws.json             # -> "signature":"verified"
```

> Honest scope: the bundle includes skills/agents/commands and `app_settings` (e.g. the token budget). It
> does **not** include session history, per-session settings, scheduled tasks, or audit -- it is a content
> + config bundle, not a full state snapshot. Import reuses the plugin installer, so it stays
> workspace-confined and path-sanitized; settings are applied as-is, so import from sources you trust.
> Signing covers the pack digest, not the settings. Ed25519 gives public-key provenance (verifiers hold
> only the public key and cannot forge); the HMAC mode is shared-secret (anyone who can verify can also
> sign). The `keygen` endpoint is a convenience -- keep the private key secret and distribute only the
> public key.

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

## Admin overview (observability dashboard)

`imini` records observability data as it runs -- run counts and latency, tool-call tallies, request
volume by API key, live concurrency, plus the audit trail, scheduled tasks, and installed content. The
raw counters are at `GET /metrics`; `GET /admin/overview` (admin only) **consolidates the useful signals
into one snapshot**, and the web UI's **Admin overview** card renders it:

- **uptime** (human-readable);
- **runs** -- ok / failed / started and a success rate;
- **latency** -- average and max run time, plus live **slots** (active / limit, queued);
- **top tools** -- the most-called tools by name;
- **tasks** -- enabled / total scheduled tasks; **content** -- skill / agent / command counts;
- **server** -- detected context window, enforced prompt cap, token budget, and vision capability;
- **recent admin actions** -- the last few audit entries.

```
curl "localhost:8080/admin/overview" -H "X-API-Key: <admin-key>"
```

Click **refresh** on the card to re-poll. It's a read-only view; non-admins simply see "(admin only)".

**Run history.** The dashboard also lists the most **recent runs** -- each with its endpoint, the
**resolved mode** that turn ran in, duration, outcome, and session. The overview embeds the last 10;
`GET /admin/runs?limit=N` returns more (newest first, up to 200) and accepts `&endpoint=`, `&outcome=`
(`ok`/`failed`), and `&session=` filters (substring, case-insensitive; blank = any) -- the admin card has
matching filter controls. For a single session, `GET /session/runs?sessionId=&limit=` returns just that
session's runs (exact match) and only needs **read access to that session** (not admin) -- the session
toolbar has a **runs** button that shows them inline. It is a bounded ring buffer that is also **persisted** (the `run_history` table) and a tail is reloaded on
startup, so recent runs survive a restart. `agent.run-history.persist-max` (default 500) bounds how many
rows are kept.

```
curl "localhost:8080/admin/runs?limit=25" -H "X-API-Key: <admin-key>"
```

**Scrape-friendly metrics.** `GET /metrics/prom` (admin) returns the same counters in the **Prometheus
text exposition format**, so an external Prometheus/Grafana stack can scrape them:

```
curl "localhost:8080/metrics/prom" -H "X-API-Key: <admin-key>"
# imini_counter{name="runs_ok"} 5
# imini_tool_calls{tool="read_file"} 3
# imini_run_latency_avg_ms 120
```

A ready-to-use Prometheus scrape config and a starter **Grafana dashboard** (panels for runs, latency,
tool calls, concurrency, uptime) live in [`docs/observability/`](docs/observability/) with a short how-to.

> Honest scope: the counters themselves are still **in-process** (they reset on restart and aren't
> aggregated across nodes), and there are no histograms/percentiles -- but the `/metrics/prom` format lets
> an external system scrape and retain them over time. Run history is now durable; the live snapshot is
> still on demand, not a push/stream.

## A guided tour of the interface

New to the web UI? Click **? tour** (next to the session controls) for a short, dependency-free walkthrough
that highlights each card in turn -- sessions, the prompt box, token budget, scheduled tasks, plugins, and
the admin overview -- with a sentence on what each does and where to read more. It ends by pointing at
`docs/GLOSSARY.md` and `docs/LEARNING_PATH.md` (or `docs/WORKSHOP.md`). Re-open it anytime; it changes
nothing, it just guides.

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

Newcomers should start with **[`GettingStarted.md`](GettingStarted.md)**, which packages the path below
(and a one-page document index) for a first-time reader. In short:

1. Run the simple test in `GettingStarted.md` (`run.bat`, then one `ask.bat` prompt).
2. Read `docs/GLOSSARY.md` (eight core terms).
3. Skim `ARCHITECTURE.md` sections 1–2 for the mental model.
4. Work through `docs/LEARNING_PATH.md` (14 modules) — or run `docs/WORKSHOP.md` as a guided ~90-minute
   session with labs and `mvn test` checkpoints.
5. Read `docs/TRACE_EDIT.md`, run `mvn test`, then read `docs/CONCEPT_MAP.md`.
6. Understand the boundaries: `docs/WHATS_NOT_INCLUDED.md` (popular harness topics imini omits on
   purpose, including Recursive Language Models, meta-harnesses, and a real code sandbox).

## Current best next engineering step

The next highest-leverage production-like improvement is:

> Automatically run `git_status` and `git_diff` after any mutating file tool, then require final coding answers to summarize changed files and verification.

That is smaller than full sandboxing and would significantly improve trust.

## Suggested GitHub repository description

GitHub repository descriptions are metadata, not files. Update the repo description manually to:

```text
A local, llama.cpp-backed Java/Spring learning harness that demonstrates Claude Code-style agent loops, tools, permissions, sessions, retrieval, and safety boundaries.
```
