# Example 3 — a custom subagent (`stylecheck`)

**Use case:** you want a focused, tool-scoped specialist the main agent can delegate a subtask to (and
that returns only its conclusion, keeping its intermediate work out of the main context) — shipped as
code rather than an `agents/*.md` file.

**What this demonstrates:** an `Extension` contributing an `AgentLibrary.AgentDef` — a named subagent
with a read-only tool scope and a system prompt. Runs in its own isolated loop.

**The code:** [`StyleCheckAgentExtension.java`](StyleCheckAgentExtension.java).

## Install

1. Copy `StyleCheckAgentExtension.java` into `src/main/java/com/example/imini/ext/`.
2. Rebuild + run. `GET /admin/extensions` shows `stylecheck-agent` → `agents: ["stylecheck"]`; `/agents`
   lists it.

## Try it

```bat
ask.bat "/agent stylecheck src/main/java/com/example/imini/Tool.java"
```

**Observe:** the subagent reads the file with read-only tools and returns a short list of style findings
plus a verdict — nothing is modified. A disk file `agents/stylecheck.md` would override this definition
(disk wins over extensions wins over built-ins).
