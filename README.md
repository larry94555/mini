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

- **Tools** - read/view/list files, write and **edit** files, run shell commands, fetch web pages.
- **Streaming** - watch the model think token by token in the console.
- **Context compaction** - long histories are summarized automatically to fit the window.
- **Sessions** - multi-turn conversations that persist to disk and resume after a restart.
- **Checkpoint / rewind** - every file edit is snapshotted and can be undone.
- **Sub-agent** - open-ended research is delegated to a second loop with isolated context.
- **MCP client** - optionally load tools from external Model Context Protocol servers.
- **Permission gate** - mutating actions ask for approval first.
- **Runaway guards** - caps on generation length, wall-clock time, repetition, and repeated calls.

---

## File map

| File | Role |
|------|------|
| `MiniAgentApplication.java` | Spring Boot entry point |
| `LlamaServerManager.java` | launches & supervises `llama-server`, waits for `/health` |
| `LlamaClient.java` | model calls: `chat` (blocking) and `chatStream` (SSE) + runaway guards |
| `AgentEngine.java` | the shared loop: streaming, compaction, time budget, duplicate-call detection |
| `ContextManager.java` | token estimate + summarize-and-trim compaction |
| `BuiltinTools.java` | tool factories: read_file, view, list_dir, write_file, edit_file, run_command, web_fetch, web_search |
| `HtmlExtractor.java` | jsoup main-article extraction |
| `CheckpointStore.java` | snapshot-before-edit + rewind |
| `SessionStore.java` | per-session history, persisted to `.imini/sessions/` |
| `SubAgent.java` | research sub-agent (web-only tools) |
| `McpManager.java` | optional MCP client (stdio JSON-RPC) |
| `ToolRegistry.java` | assembles main toolset: builtins + delegate_research + MCP tools |
| `AgentLoop.java` | main agent: `run` (one-shot) and `chat` (session) |
| `AgentController.java` | REST endpoints |
| `PermissionGate.java` | approve mutating tools |
| `Tool.java`, `AgentResult.java` | value types |

Bean wiring (no cycles): AgentEngine -> LlamaClient, ContextManager; BuiltinTools -> CheckpointStore;
SubAgent -> AgentEngine, BuiltinTools; ToolRegistry -> BuiltinTools, SubAgent, McpManager;
AgentLoop -> AgentEngine, ToolRegistry, PermissionGate, SessionStore;
AgentController -> AgentLoop, SessionStore, CheckpointStore.

---

## Run (Windows)

```bat
run.bat
```

It checks Java, warns if `llama-server.exe` is missing, installs a local Maven if you don't have
one, then `mvn spring-boot:run`. The first launch downloads the ~2 GB model (progress in
`llama-server.log`). You're up when you see `llama-server is ready.` and
`Started MiniAgentApplication`. The app listens on http://localhost:8080 ; llama-server on 8081.

Helper scripts: `ask.bat "question"` (one-shot), `chat.bat SESSION "message"` (multi-turn),
`rewind.bat` (undo last edit).

---

## HTTP endpoints

| Method & path | Body | Purpose |
|---------------|------|---------|
| `POST /ask` | `{"question":"..."}` | one-shot, no memory |
| `POST /chat` | `{"sessionId":"...?","message":"..."}` | multi-turn; returns sessionId to reuse |
| `GET /sessions` | - | list known session ids |
| `POST /rewind` | - | undo the most recent file edit |
| `GET /checkpoints` | - | list available rewind points |

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
| `delegate_research` | no | hand an open-ended task to the sub-agent |
| `web_search` | no | sub-agent only (DuckDuckGo) |
| `<server>_<tool>` | yes | any tools discovered from MCP servers |

---

## Configuration (`application.properties`)

```
server.port=8080                    # this app's REST API
agent.auto-approve=false            # true skips permission prompts
agent.stream=true                   # stream model tokens to the console
agent.compact-token-threshold=6000  # when to summarize old turns
agent.compact-keep-recent=6         # recent messages kept verbatim
agent.max-tokens=1024               # cap per single generation
agent.deadline-seconds=120          # wall-clock budget per /ask or /chat
agent.stream-max-chars=12000        # stream length backstop
agent.stream-max-seconds=90         # stream time backstop
```

`.imini/` holds runtime state: `sessions/`, `checkpoints/`, and any `mcp-<server>.log` files.

---

## MCP (optional, off by default)

Copy `mcp.example.json` to `mcp.json` and point it at any MCP server you have. On startup imini
launches each server, discovers its tools, and registers them. With no `mcp.json`, MCP is simply
off. See TESTING.md for a worked example.

---

## Caveats (it's a learning kit)

- The 3B model's tool-calling is imperfect; phrasing a prompt to name the tool helps. The engine
  has a `<tool_call>` text fallback and several runaway guards.
- `web_search` scrapes DuckDuckGo HTML - fine for learning, brittle for production.
- `run_command` and MCP tools execute real actions (after approval). Keep `auto-approve=false`.
- The MCP read is synchronous; a server that never replies can block that request thread.
- Token counting is chars/4; compaction is single-pass.
