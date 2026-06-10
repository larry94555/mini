# imini — a low-end Claude Code learning harness

`imini` is a deliberately small, local-first coding-agent harness built around `llama-server`.

Its purpose is to make the boundary between:

- **the model** — reasoning, choosing a tool, producing text, and
- **the harness** — tools, permissions, persistence, compaction, retrieval, approvals, and UX

concrete and easy to study.

It is intentionally **education-grade**: readable enough to learn from, useful enough to experiment with, and honest about where production hardening would still be needed.

---

## Start here

- **INSTALL.md** — first-time setup
- **TESTING.md** — feature-by-feature checks and deterministic tests
- **ARCHITECTURE.md** — one complete request trace through the harness
- **ROADMAP.md** — the next steps toward a stronger local coding agent

---

## What this repo is good for

Use this repo if you want to learn:

1. how a Claude Code-style harness is assembled,
2. how tool-calling loops work,
3. how permissions and plan mode fit into the loop,
4. why persistence, retrieval, and compaction belong outside the model,
5. how MCP, hooks, project memory, and slash commands extend an agent,
6. how to write deterministic tests for a coding-agent harness.

If you want a deeper production reference, study projects such as **Aider**, **OpenCode**, or **OpenHands** alongside this one.

---

## Current capabilities

| Area | Feature | What it teaches |
|---|---|---|
| core | Agent loop + tools + streaming | think -> act -> observe |
| serving | Model profiles + llama-server supervision | local model serving is part of the harness |
| editing | Precise editing + checkpoints + rewind | safe mutation needs reversibility |
| memory | Sessions + SQLite persistence | chat memory should survive restarts |
| memory | Retrieval / `search_memory` | context selection belongs in the harness |
| permissions | Allow/deny rules + remembered decisions + plan mode | review and execution should be separable |
| approvals | Console or remote approvals | mutating actions need a human gate |
| context | Accurate token counting + compaction + project memory | context is engineered, not accidental |
| orchestration | Todo tool + parallel read-only tools + interrupt/steer | long tasks need state and control |
| extensibility | MCP + hooks + slash commands + sub-agent | agent systems are mostly extension surfaces |
| reliability | Schema validation + corrective retry + optional grammar | small models need harness assistance |
| safety | Workspace confinement + command screening | policy must live outside the prompt |
| ops | Auth + rate limiting + metrics + web UI | the product surface matters |

---

## File map

| File | Role |
|---|---|
| `MiniAgentApplication.java` | Spring Boot entry point |
| `AgentController.java` | HTTP surface: ask/chat/stream/control endpoints |
| `AgentLoop.java` | request/session orchestration |
| `AgentEngine.java` | core loop: streaming, tool execution, guards, plan mode |
| `LlamaClient.java` | chat, streaming, token counting, summarization routing |
| `BuiltinTools.java` | file, shell, web, todo tools |
| `PermissionService.java` | approval decisions, plan mode, confinement |
| `Approvals.java` | remote approval state |
| `Sandbox.java` | command screening + read/write confinement policy |
| `ContextManager.java` | token budgeting, compaction, trimming |
| `ProjectContext.java` | loads `IMINI.md`, `CLAUDE.md`, `AGENTS.md` |
| `RetrievalService.java` | workspace indexing and retrieval |
| `SessionStore.java` / `Database.java` | session persistence |
| `CheckpointStore.java` | snapshot + rewind support |
| `TodoStore.java` | per-session task tracking |
| `HookService.java` | pre/post tool hooks |
| `SlashCommands.java` | `/name` command templates |
| `SubAgent.java` | isolated research helper |
| `McpManager.java` | external MCP tools |
| `Metrics.java` | counters, latency, gauges |
| `src/test/java/...` | deterministic harness tests |

---

## Execution modes

`imini` currently exposes three main execution modes:

- **ask** — default; mutating tools need approval.
- **auto** — mutating tools are auto-approved, but policy and confinement still apply.
- **plan** — intended actions are recorded, not executed.

That split matters because it makes reviewable planning a first-class concept rather than an afterthought.

---

## Current behavior

The intended current behavior is:

- approvals can be handled either by the **console** or by the **remote approval flow**,
- interrupt and steer are treated as **session-oriented controls**,
- sessions, checkpoints, and retrieval are persisted more durably than in the earlier JSON-only design.

When these docs and the implementation disagree, treat the code and tests as the source of truth and update the docs quickly.

---

## Testing philosophy

The most valuable tests in this repo are the deterministic harness tests.

Examples include:

- schema validation of tool-call arguments,
- workspace confinement,
- retry behavior,
- grammar generation,
- command-screening rules,
- approval and auth/rate-limit behavior,
- retrieval ranking.

This is intentional. The harness should be testable even when the model is not deterministic.

---

## Caveats

`imini` is still a learning kit.

- Small local models remain weaker at tool use than larger hosted models.
- `web_search` remains a lightweight approach, not a production search stack.
- Command screening is not the same thing as a hardened sandbox.
- The web UI and remote-approval flows improve usability, but they do not by themselves make the system production-safe.
- The codebase is evolving quickly, so documentation drift needs active attention.

If your next goal is production hardening rather than learning, start with `ROADMAP.md`.
