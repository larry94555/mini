# imini — a low-end Claude Code (learning project)

A minimal but real agent harness over a local `llama-server` running
`Qwen/Qwen2.5-3B-Instruct`. It exists to make the boundary between **the model** (reasoning) and
**the harness** (tools, loop, memory, safety) concrete and readable. No cloud, no API key.

For first-time install (Java + llama-server), see **INSTALL.md**.
For hands-on tests of every feature, see **TESTING.md**.

---

## What it does

You send a question; the harness gives the model a set of tools and runs a think -> act -> observe
loop until the model answers. The model decides *what* to do; the harness *does* it.

Capabilities:

- **Tools** - read/view/list files, write and edit files, run shell commands, fetch web pages, plan.
- **Streaming** - watch the model think token by token in the console.
- **Accurate tokens + layered context** - real token counts via `/tokenize`, a durable memory note
  that survives compaction, and trimming of oversized tool outputs.
- **Sessions** - multi-turn conversations that persist to disk and resume after a restart.
- **Checkpoint / rewind** - every file edit is snapshotted and can be undone.
- **Sub-agent** - open-ended research is delegated to a second loop with isolated context.
- **MCP client** - optionally load tools from external Model Context Protocol servers.
- **Permissions + plan mode** - rules, remembered decisions, workspace confinement, and a plan mode
  that proposes actions without executing them.
- **Todo / planning tool** - the model lays out and checks off steps for multi-step tasks.
- **Parallel tools** - independent read-only tool calls in a turn run concurrently.
- **Runaway guards** - caps on generation length, wall-clock time, repetition, and repeated calls.

---

## File map

| File | Role |
|------|------|
| `MiniAgentApplication.java` | Spring Boot entry point |
| `LlamaServerManager.java` | launches & supervises `llama-server`, waits for `/health` |
| `LlamaClient.java` | model calls: `chat`, `chatStream` (SSE), `countTokens` (/tokenize) |
| `AgentEngine.java` | the shared loop: streaming, compaction, modes, plan recording, parallel tools, guards |
| `ContextManager.java` | real-token counting, durable memory note, tool-output trimming |
| `BuiltinTools.java` | tools: read_file, view, list_dir, write_file, edit_file, run_command, web_fetch, web_search, todo_write |
| `HtmlExtractor.java` | jsoup main-article extraction |
| `CheckpointStore.java` | snapshot-before-edit + rewind |
| `SessionStore.java` | per-session history, persisted to `.imini/sessions/` |
| `TodoStore.java` | the current task checklist |
| `PermissionService.java` | allow/deny rules, remembered decisions, workspace confinement, plan mode |
| `SubAgent.java` | research sub-agent (web-only tools) |
| `McpManager.java` | optional MCP client (stdio JSON-RPC) |
| `ToolRegistry.java` | assembles main toolset: builtins + delegate_research + MCP tools |
| `AgentLoop.java` | main agent: `run` (one-shot) and `chat` (session) |
| `AgentController.java` | REST endpoints |
| `Tool.java`, `AgentResult.java` | value types |

(`PermissionGate.java` from earlier versions is removed -- `PermissionService` replaces it. Delete
it from your copy if it's still there.)

Bean wiring (no cycles): AgentEngine -> LlamaClient, ContextManager, PermissionService;
BuiltinTools -> CheckpointStore, TodoStore; SubAgent -> AgentEngine, BuiltinTools;
ToolRegistry -> BuiltinTools, SubAgent, McpManager; AgentLoop -> AgentEngine, ToolRegistry, SessionStore;
AgentController -> AgentLoop, SessionStore, CheckpointStore, TodoStore.

---

## Run (Windows)

```bat
run.bat
```

It checks Java, warns if `llama-server.exe` is missing, installs a local Maven if you don't have
one, then `mvn spring-boot:run`. The first launch downloads the ~2 GB model (progress in
`llama-server.log`). You're up when you see `llama-server is ready.` and
`Started MiniAgentApplication`. The app listens on http://localhost:8080 ; llama-server on 8081.

Helper scripts: `ask.bat "q"` (one-shot), `chat.bat SESSION "msg"` (multi-turn),
`plan.bat "q"` (plan mode), `rewind.bat` (undo last edit).

---

## HTTP endpoints

| Method & path | Body | Purpose |
|---------------|------|---------|
| `POST /ask` | `{"question":"...","mode":?}` | one-shot, no memory |
| `POST /chat` | `{"sessionId":"...?","message":"...","mode":?}` | multi-turn; returns sessionId |
| `GET /sessions` | - | list known session ids |
| `GET /todos` | - | current task checklist |
| `POST /rewind` | - | undo the most recent file edit |
| `GET /checkpoints` | - | list available rewind points |

`mode` (optional) is `ask` (default; prompt for each mutating call), `auto` (approve automatically,
still workspace-confined), or `plan` (record intended actions, execute nothing).

---

## Tools the model can call

| Tool | Mutating? | Notes |
|------|-----------|-------|
| `read_file` | no | read a file |
| `view` | no | read with line numbers (range optional) - use before editing |
| `list_dir` | no | list a directory |
| `write_file` | yes | overwrite a whole file (snapshots first) |
| `edit_file` | yes | exact, unique-match replacement (snapshots first) |
| `run_command` | yes | run a shell command |
| `web_fetch` | no | fetch a page, jsoup main-article text |
| `todo_write` | no | record/update the task checklist |
| `delegate_research` | no | hand an open-ended task to the sub-agent |
| `web_search` | no | sub-agent only (DuckDuckGo) |
| `<server>_<tool>` | yes | any tools discovered from MCP servers |

Read-only tools never prompt and can run in parallel. Mutating tools go through `PermissionService`.

---

## Permissions & plan mode

- **Modes** (per request, via the `mode` field): `ask`, `auto`, `plan`.
- **Rules** in `permissions.json` (optional; copy `permissions.example.json`): `allow` and `deny`
  lists of tool names or `run_command:<prefix>` entries. Deny wins; allow skips the prompt.
- **Remembered decisions:** answer `a` (always) at a prompt to allow that tool/command for the run.
- **Workspace confinement:** `write_file`/`edit_file` outside the workspace root are denied even in
  auto mode (set `agent.confine-to-workspace=false` to disable).

---

## Configuration (`application.properties`)

```
server.port=8080                    # this app's REST API
agent.auto-approve=false            # legacy: true means default mode = auto
agent.stream=true                   # stream model tokens to the console
agent.compact-token-threshold=6000  # when to fold old turns into memory
agent.compact-keep-recent=6         # recent messages kept verbatim
agent.max-tokens=1024               # cap per single generation
agent.deadline-seconds=120          # wall-clock budget per /ask or /chat
agent.stream-max-chars=12000        # stream length backstop
agent.stream-max-seconds=90         # stream time backstop
agent.confine-to-workspace=true     # deny writes outside the workspace root
agent.workspace-root=               # blank = current working directory
agent.parallel-tools=true           # run independent read-only calls concurrently
agent.max-tool-result-chars=4000    # trim oversized tool outputs before they enter history
```

`.imini/` holds runtime state: `sessions/`, `checkpoints/`, and any `mcp-<server>.log` files.

---

## MCP (optional, off by default)

Copy `mcp.example.json` to `mcp.json` and point it at any MCP server you have. With no `mcp.json`,
MCP is simply off. See TESTING.md for a worked example.

---

## Caveats (it's a learning kit)

- The 3B model's tool-calling is imperfect; phrasing a prompt to name the tool helps. The engine has
  a `<tool_call>` text fallback and several runaway guards.
- `web_search` scrapes DuckDuckGo HTML - fine for learning, brittle for production.
- `run_command` and MCP tools execute real actions (after approval). Keep `auto-approve=false`.
- Workspace confinement applies to file writes/edits, not to arbitrary shell commands; for commands,
  rely on allow/deny rules.
- The MCP read is synchronous; a server that never replies can block that request thread.
- Token counting now uses `/tokenize`; compaction folds older turns into one evolving memory note.
