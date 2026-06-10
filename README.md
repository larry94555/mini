# imini — a low-end Claude Code learning harness

`imini` is a deliberately small, local-first coding-agent harness built around `llama-server`.
It is designed to make the boundary between:

- **the model**: reasoning, choosing a tool, producing text, and
- **the harness**: tools, permissions, persistence, compaction, retrieval, approvals, and UX

concrete and easy to study.

It is intentionally **education-grade**, not a claim of production readiness.

## Start here

- **INSTALL.md** — first-time setup
- **TESTING.md** — feature-by-feature manual checks
- **ARCHITECTURE.md** — one complete request trace through the harness
- **ROADMAP.md** — what to harden next for production use

---

## What this repo is good for

Use this repo if you want to learn:

1. how a Claude Code-style harness is assembled,
2. how tool-calling loops work,
3. how permissions and plan mode fit into the loop,
4. why persistence, retrieval, and compaction belong outside the model,
5. how to write deterministic tests for an agent harness.

Use a larger project such as Aider, OpenCode, or OpenHands if you want a deeper production reference.

---

## Current capabilities

| Area | Feature | What it teaches |
|---|---|---|
| core | Agent loop + tools + streaming | think -> act -> observe |
| serving | Model profiles + llama-server supervision | local model serving is part of the harness |
| editing | Precise editing + checkpoints + rewind | safe mutation needs reversibility |
| memory | Sessions + SQLite persistence | chat memory should survive restarts |
| memory | Retrieval / search_memory | context selection belongs in the harness |
| permissions | Allow/deny rules + remembered decisions + plan mode | review and execution should be separable |
| approvals | Console or remote approvals | mutating actions need a human gate |
| context | Accurate token counting + compaction + project memory | context is engineered, not accidental |
| orchestration | Todo tool + parallel read-only tools + interrupt/steer | long tasks need state and control |
| extensibility | MCP + hooks + slash commands + sub-agent | agent systems are mostly extension surfaces |
| reliability | schema validation + corrective retry + optional grammar | small models need harness assistance |
| safety | workspace confinement + command screening | policy must live outside the prompt |
| ops | auth + rate limiting + metrics + web UI | the product surface matters |

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

## Request modes

`imini` currently exposes three main execution modes:

- **ask** — default, mutating tools need approval.
- **auto** — mutating tools are auto-approved, but policy and confinement still apply.
- **plan** — intended actions are recorded, not executed.

That distinction is important for learning because it makes reviewable planning a first-class concept.

---

## Approvals, interrupts, and current behavior

The newer harness behavior is:

- approvals can be handled either by the **console** or by the **remote approval flow**,
- interrupt and steer are intended to be **per-session controls**,
- sessions, checkpoints, and retrieval are persisted more durably than in the earlier JSON-only design.

Older docs and caveats sometimes still describe an earlier single-user, console-only flow.
This patch aligns the top-level docs around the current behavior and treats any remaining older wording as historical drift to remove.

---

## Formatting and CI

This patch adds:

- `.editorconfig`,
- Spotless formatting in Maven,
- GitHub Actions CI that runs formatting checks and tests.

The Spotless configuration uses `ratchetFrom origin/main`, which means formatting is enforced for **files changed in the branch** without forcing a giant repository-wide reformat in one PR.

To format locally:

```bat
format.bat
```

or:

```bash
mvn spotless:apply
```

---

## Testing philosophy

The most valuable tests in this repo are the deterministic harness tests.

Examples:

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
- The web UI and remote-approval flows make the product more usable, but they do not by themselves make it production-safe.
- The codebase is evolving quickly, so documentation drift is something to actively guard against.

If your next goal is production hardening rather than learning, start with `ROADMAP.md`.
