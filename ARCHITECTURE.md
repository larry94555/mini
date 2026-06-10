# ARCHITECTURE

This document explains **how imini works as a harness**, not just what features it has.

The key design idea is the same one behind Claude Code-style systems:

- the **model** reasons and decides what to do next,
- the **harness** supplies tools, state, permissions, persistence, guardrails, and product UX.

imini is intentionally small enough that you can follow one request from HTTP all the way through the loop.

---

## 1. Core mental model

A request flows through these layers:

```text
User / browser / batch script
  -> AgentController
  -> AgentLoop
  -> AgentEngine
  -> LlamaClient
  -> llama-server
  -> tool calls (optional)
  -> ToolRegistry / BuiltinTools / MCP / SubAgent
  -> AgentEngine continues
  -> AgentResult
  -> HTTP response / SSE stream / saved session state
```

The important architectural split is:

- **LlamaClient** talks to the model server.
- **AgentEngine** runs the think -> act -> observe loop.
- **ToolRegistry** defines what the model is allowed to do.
- **PermissionService / Sandbox / Approvals** decide whether a requested action may proceed.
- **SessionStore / CheckpointStore / TodoStore / Database** preserve state outside the model.
- **ContextManager / ProjectContext / RetrievalService** decide what context reaches the model.

---

## 2. Request lifecycle at a glance

### One-shot request

```text
POST /ask
  -> AgentController parses the request
  -> AgentLoop creates the initial message list and mode
  -> AgentEngine sends messages + tool schemas to the model
  -> model returns plain text OR tool calls
  -> harness validates tool calls
  -> harness applies permissions / sandbox / approvals
  -> tool executes and returns a result
  -> tool result goes back to the model
  -> loop repeats until final answer or a guard stops it
  -> AgentResult returned to caller
```

### Session request

```text
POST /chat
  -> AgentController loads or creates a session
  -> AgentLoop loads session transcript + project context
  -> AgentEngine runs the loop
  -> resulting transcript is saved back to SessionStore / Database
  -> caller receives the answer and sessionId
```

---

## 3. A concrete end-to-end example

This is the simplest Claude Code-style teaching example in the repo.

### User prompt

```text
In notes.txt, change "Status: draft" to "Status: final".
```

### Step A: controller entry

The request enters through `AgentController`.

Responsibilities here:

- parse HTTP input,
- decide whether the call is one-shot or session-based,
- attach the session id,
- choose the output sink (console or SSE/browser),
- delegate to `AgentLoop`.

### Step B: session and prompt assembly

`AgentLoop` prepares the prompt stack:

- system prompt,
- project memory (`IMINI.md`, `CLAUDE.md`, `AGENTS.md`),
- prior session messages if this is chat,
- slash-command expansion if applicable,
- current user request.

This is the first major harness responsibility: **compose the right context before the model sees anything**.

### Step C: model call

`AgentEngine` sends:

- messages,
- tool schemas,
- mode information,
- streaming preferences,
- optional grammar constraints,
- token budget / guard settings,
- and possibly a sink for incremental output

to `LlamaClient`, which talks to `llama-server`.

At this point the model may do one of two things:

1. answer directly, or
2. return one or more tool calls.

### Step D: tool-call validation

Suppose the model requests:

```json
{
  "name": "edit_file",
  "arguments": {
    "path": "notes.txt",
    "old_str": "Status: draft",
    "new_str": "Status: final"
  }
}
```

Before execution, the harness checks:

- schema validity (`SchemaValidator`),
- mode restrictions (`PermissionService`),
- workspace confinement (`PermissionService` / `Sandbox`),
- approval requirements (`Approvals` / remote approval flow),
- checkpoint creation (`CheckpointStore`),
- hook execution (`HookService`, if configured).

This is the second major harness responsibility: **never trust the model's tool call blindly**.

### Step E: tool execution

If the call is allowed:

- `BuiltinTools` performs the edit,
- `CheckpointStore` snapshots state for rewind,
- the tool returns a structured success or error message.

If the call is denied:

- the tool is not run,
- the denial becomes feedback for the model,
- the loop continues so the model can re-plan.

### Step F: model continues

The tool result is inserted into the conversation and sent back to the model.

The model then typically returns a final answer such as:

```text
I changed notes.txt from draft to final.
```

### Step G: persistence and response

Finally:

- the answer is streamed or returned,
- session state is persisted if this was a chat request,
- todos / checkpoints / metrics are updated,
- the run is eligible for rewind, inspection, or later resume.

That is the complete Claude Code-style loop in miniature.

---

## 4. Main components and why they exist

## AgentController

Purpose:

- HTTP entry point,
- maps REST calls to harness operations,
- exposes streaming and non-streaming paths,
- exposes secondary operations like interrupts, approvals, sessions, checkpoints, and metrics.

Teaching value:

- shows that product UX starts outside the model.

## AgentLoop

Purpose:

- orchestrates a single run or chat turn,
- wires together session state, project memory, slash commands, and engine execution.

Teaching value:

- shows the difference between **transport** concerns and **agent** concerns.

## AgentEngine

Purpose:

- the core think -> act -> observe loop,
- streams output,
- validates tool calls,
- supports plan mode,
- supports interruption and steering,
- manages retries, guards, and compaction.

Teaching value:

- this is the heart of the harness.

## LlamaClient

Purpose:

- model-server adapter,
- sends prompts/tool schemas,
- handles streaming,
- handles token counting and summarization routes.

Teaching value:

- shows that a coding agent is not tied to one hosted API.

## ToolRegistry / BuiltinTools / MCP / SubAgent

Purpose:

- expose actions to the model,
- separate built-in tools from externally supplied tools,
- isolate specialist work in sub-agents.

Teaching value:

- shows that "agent capability" is mostly a tool-interface problem.

## PermissionService / Approvals / Sandbox

Purpose:

- decide whether actions are allowed,
- require human approval when needed,
- apply path and command confinement,
- implement plan mode.

Teaching value:

- shows that safety belongs in the harness, not in a prompt alone.

## ContextManager / ProjectContext / RetrievalService

Purpose:

- manage token budget,
- trim tool output,
- inject project memory,
- retrieve relevant workspace snippets.

Teaching value:

- shows why context engineering is separate from generation.

## SessionStore / CheckpointStore / TodoStore / Database

Purpose:

- persist chat state,
- enable rewind,
- store task checklists,
- survive restarts.

Teaching value:

- shows that model memory is not enough; agent products need durable state.

---

## 5. Why plan mode matters

Plan mode is a useful teaching feature because it separates:

- **understanding the task**, from
- **taking irreversible action**.

In plan mode, the harness still lets the model inspect the workspace and propose actions, but it records intended mutations instead of executing them.

That teaches an important production lesson:

> a trustworthy coding agent needs a reviewable planning surface, not only an execution surface.

---

## 6. Why deterministic tests matter

A common beginner mistake is to think that testing an agent means testing the model.

In practice, the most valuable tests are often harness tests:

- does the schema validator reject malformed tool args?
- do path checks stop workspace escape?
- do retries happen only for transient failures?
- do command-screening rules block dangerous strings?
- do grammar builders include the correct tool names?

These are deterministic and CI-friendly even when the model itself is stochastic.

---

## 7. Current architectural limits

imini is still a learning harness. The major limits are intentional and instructive:

- tool-calling reliability still depends on a small local model,
- shell sandboxing is not yet strong enough for production,
- codebase navigation is still lighter than Aider / OpenCode / OpenHands,
- diff-first editing and verification loops are still limited,
- some state and UX layers are newer than the older docs and are still being aligned.

Those are useful limits because they make the next engineering steps obvious.

---

## 8. What to study next after this document

After reading this file, the best next learning path is:

1. `README.md` for feature overview,
2. `TESTING.md` for hands-on behavior,
3. `AgentController.java` and `AgentLoop.java` for request entry,
4. `AgentEngine.java` for the core loop,
5. `PermissionService.java` and `Sandbox.java` for policy,
6. `LoopCorrectnessTest.java` and `SandboxTest.java` for deterministic harness tests.

That sequence moves from concept -> product surface -> execution loop -> safety -> testability.
