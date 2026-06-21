# Concept Map: Claude Code-Style Ideas in imini

This map connects common Claude Code-style harness concepts to the files in `imini` that demonstrate the same idea in a smaller local project.

`imini` is not a Claude Code clone. It is a learning harness that makes the architectural pieces visible.

## Core model/harness split

| Concept | What it means | imini files |
|---|---|---|
| Model server | The process that generates assistant messages and tool calls | `LlamaServerManager.java`, `LlamaClient.java` |
| Harness | The application that owns tools, loop, state, safety, and UX | `AgentLoop.java`, `AgentEngine.java`, `ToolRegistry.java` |
| OpenAI-compatible chat API | The wire format used to talk to `llama-server` | `LlamaClient.java` |
| Streaming | Token-by-token output to the caller | `LlamaClient.java`, `RunSink.java`, `Sse.java`, `AgentController.java` |

## Agent loop

| Concept | What it means | imini files |
|---|---|---|
| Think -> act -> observe | Model proposes a tool call, harness executes it, model observes the result | `AgentEngine.java` |
| Tool schemas | JSON schema-like descriptions of callable tools | `Tool.java`, `BuiltinTools.java`, `CodebaseTools.java` |
| Tool-call validation | Reject invalid arguments before running a tool | `SchemaValidator.java`, `LoopCorrectnessTest.java` |
| Corrective retry | Return validation failures as tool results so the model can repair the call | `AgentEngine.java`, `SchemaValidatorEdgeCaseTest.java` |
| Runaway guards | Stop repeated calls, endless generations, or over-budget runs | `AgentEngine.java`, `LlamaClient.java` |

## Tools

| Concept | What it means | imini files |
|---|---|---|
| File tools | Read, view, write, edit, and patch files | `BuiltinTools.java` |
| Shell tool | Run a command and return output | `BuiltinTools.java`, `Sandbox.java` |
| Web tools | Fetch/search web content as untrusted data | `BuiltinTools.java`, `HtmlExtractor.java`, `Untrusted.java` |
| Codebase navigation | Deterministic search/read tools for repo understanding | `CodebaseTools.java`, `CodebaseToolsTest.java` |
| Git awareness | Inspect working-tree state and history | `CodebaseTools.java` |
| Retrieval/RAG | Search indexed workspace snippets | `RetrievalService.java`, `RetrievalTest.java` |

## Planning and task state

| Concept | What it means | imini files |
|---|---|---|
| Plan mode | Record intended mutations without executing them | `PermissionService.java`, `AgentEngine.java` |
| Todo tool | Let the model maintain an explicit task checklist | `BuiltinTools.java`, `TodoStore.java` |
| Coding profile | Prompt-level workflow guidance for code tasks | `AgentProfile.java` |

## Permissions and safety

| Concept | What it means | imini files |
|---|---|---|
| Permission gate | Decide whether mutating tools can run | `PermissionService.java` |
| Remote approvals | Approve/deny tool calls from UI/API | `Approvals.java`, `AgentController.java`, `static/index.html` |
| Workspace confinement | Prevent file tools from escaping the project root | `PermissionService.java`, `Sandbox.java` |
| Command screening | Deny or allowlist shell commands before execution | `Sandbox.java`, `SandboxTest.java` |
| Container command wrapper | Optional way to run shell commands in an external container/jail | `Sandbox.java`, `application.properties` |
| Prompt-injection fencing | Mark external tool output as data, not instructions | `Untrusted.java`, `AgentEngine.java` |
| Capability scoping | Restrict which tools a caller's role may use | `CapabilityService.java`, `AgentEngine.java` |
| Per-tenant rate limiting | Throttle a tool per tenant (`RATE_LIMITED`) | `ToolRateLimiter.java`, `AgentEngine.java` |

_Proven by golden traces: `CapabilityScopingTraceTest` (out-of-scope denial + audit, and `RATE_LIMITED`),
`RecoveryTraceTest` (plan-mode `RECORD_PLAN`, invalid-args recovery, duplicate-call guard). See
[`WORKFLOW_WALKTHROUGH.md`](WORKFLOW_WALKTHROUGH.md) §4._

## State and persistence

| Concept | What it means | imini files |
|---|---|---|
| Sessions | Multi-turn history that can be resumed | `SessionStore.java`, `Database.java` |
| Checkpoints | Snapshot-before-edit and rewind | `CheckpointStore.java`, `Database.java` |
| Durable database | SQLite-backed storage with migrations | `Database.java` |
| Per-session state | Todos, permissions, interrupt, and checkpoints scoped by session | `TodoStore.java`, `PermissionService.java`, `InterruptService.java`, `CheckpointStore.java` |

## Context management

| Concept | What it means | imini files |
|---|---|---|
| Token counting | Use model tokenization when available | `ContextManager.java`, `LlamaClient.java` |
| Compaction | Summarize or trim older context | `ContextManager.java` |
| Project memory | Load project instruction files into the system prompt | `ProjectContext.java` |
| Tool-output trimming | Reduce huge tool outputs before returning them to the model | `ContextManager.java` |

## Extensibility

| Concept | What it means | imini files |
|---|---|---|
| MCP | Load external tools from stdio JSON-RPC servers | `McpManager.java` |
| Subagent | Delegate a constrained task to a narrower agent | `SubAgent.java` |
| Hooks | Run deterministic shell commands before/after tool use | `HookService.java` |
| Slash commands | Reusable prompt templates | `SlashCommands.java`, `commands/*.md` |

_Proven by golden traces: `SubAgentHandoffTraceTest` + `SubAgentFailureTraceTest` (delegation hand-off and
failure propagation), `McpLiveIntegrationTest` (stdio/HTTP discovery, streaming + unbounded SSE, two-server
namespacing/routing). See [`WORKFLOW_WALKTHROUGH.md`](WORKFLOW_WALKTHROUGH.md) §4._

## User experience and operations

| Concept | What it means | imini files |
|---|---|---|
| HTTP API | Expose one-shot, chat, session, rewind, memory, approval, and metrics endpoints | `AgentController.java` |
| Web UI | Browser interface for sessions, streaming, todos, approvals, and metrics | `static/index.html` |
| Auth | API-key protection for the HTTP surface | `AuthFilter.java`, `RateLimiter.java` |
| Metrics | In-process counters, latency, and concurrency snapshot | `Metrics.java` |
| Structured logging | Consistent logs for runs and tool activity | `logback-spring.xml`, `Metrics.java` |
| Docker | Containerized app and local model service | `Dockerfile`, `docker-compose.yml` |
| CI | Automated tests and Docker build | `.github/workflows/ci.yml` |

## What is intentionally simplified

`imini` keeps several areas simple so the harness remains understandable:

- The sandbox policy is not a full OS security boundary unless you configure container execution.
- Symbol search uses regex heuristics rather than a full parser or LSP.
- Retrieval is lexical by default, with optional embeddings.
- Auth is API-key based, not OAuth/OIDC/RBAC.
- Metrics are in-process JSON, not OpenTelemetry or Prometheus.
- MCP support is useful for learning but should be isolated more aggressively for production.
- The UI is a small single-page app, not a full IDE integration.

## How to use this map

When studying the repo, start with the concept you care about and read the matching file. Then run the related test if one exists. The goal is to understand the harness layer one piece at a time instead of treating the agent as a black box.


## Concepts deliberately not demonstrated

Some popular harness ideas are intentionally out of scope for a small learning project. These are not
mapped to files because imini does not implement them; they are catalogued, with the reasoning and what
adding them would involve, in [`WHATS_NOT_INCLUDED.md`](WHATS_NOT_INCLUDED.md).

| Concept | Why it's not here | Where to read more |
|---|---|---|
| Recursive Language Models (RLM) | Needs a code sandbox + a strong model; heavier than imini's token-budget need | [`RECURSIVE_LANGUAGE_MODELS.md`](RECURSIVE_LANGUAGE_MODELS.md) |
| Genuinely sandboxed code execution | "pattern sandbox != syscall" — real isolation is its own systems project | [`WHATS_NOT_INCLUDED.md`](WHATS_NOT_INCLUDED.md) |
| Meta-harnesses (multi-agent orchestration) | imini shows one delegation example (`SubAgent`), not a full orchestrator | [`WHATS_NOT_INCLUDED.md`](WHATS_NOT_INCLUDED.md) |
| Distributed state, real auth, vector RAG, agent evals, model routing, ... | Out of scope for a single-node teaching harness | [`WHATS_NOT_INCLUDED.md`](WHATS_NOT_INCLUDED.md) |
