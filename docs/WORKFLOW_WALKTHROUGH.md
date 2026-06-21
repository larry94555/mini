# Workflow walkthrough: edit → verify → commit, hooks, and MCP

This is a guided tour of the three workflow surfaces that make imini feel like a real coding agent: the
**edit → verify → commit loop**, the **hook lifecycle**, and the **MCP server lifecycle**. Everything here
is accurate to the code as of the current `tier3` head; file/method names are given so you can read along.

The intent is educational: each diagram is small and maps directly to a few methods, so you can open the
source next to it and see exactly where each arrow lives.

---

## 1. The edit → verify → commit loop

A coding turn is a loop: the model proposes a tool call, the permission layer decides whether it may run,
the tool runs, and after any file change imini appends a **git-verified** summary so the answer can't
overstate what happened. When the work is ready, the new write-side git tools close the loop with a commit.

```mermaid
flowchart TD
    U[User prompt] --> A[AgentEngine.run]
    A --> L{model emits<br/>tool call?}
    L -- "read tool<br/>(read_file, grep, git_diff)" --> RUN[execute, return result]
    L -- "mutating tool<br/>(edit_file, git_commit...)" --> P[PermissionService.decide]
    P -- ALLOW --> RUN
    P -- DENY --> BLK[return denial to model]
    P -- RECORD_PLAN --> PLAN[record in plan, skip exec]
    RUN --> L
    L -- "no more calls" --> V[Edit trust: git status + diff --stat]
    V --> ANS[final answer + verified edits]
```

The **commit** half is three mutating tools in `GitWriteTools` — `git_stage`, `git_commit`, `git_branch`
(plus `git_push`, off by default). They sit on the same `PermissionService` gate as `edit_file` and
`run_command`, so a commit is approved the same way any other mutation is. The recommended path:

```mermaid
sequenceDiagram
    participant M as Model
    participant G as Git tools
    participant P as PermissionService
    participant R as Repo
    M->>G: edit_file (write changes)
    M->>G: git_stage (paths or all)
    G->>P: approve? (mutating)
    P-->>G: ALLOW
    G->>R: git add
    M->>G: git_diff staged=true (review)
    M->>M: commit-message skill drafts message
    M->>G: git_commit message=...
    G->>P: approve? (payload includes staged diff)
    P-->>G: ALLOW
    G->>R: git commit
    G-->>M: Committed. HEAD now: <sha> <subject>
```

Two details worth seeing in the source:

- **The staged diff is surfaced for approval.** `PermissionService.decideRemote` attaches
  `git diff --cached --stat` (via `GitInspector.diffCachedStat`) to the approval payload for
  `git_commit`/`git_stage`, so the reviewer sees what will land before clicking approve.
- **`git_commit` refuses an empty commit.** If nothing is staged (and `all=true` was not passed) it returns
  a clear error instead of creating an empty commit, and it reports the new HEAD on success.

---

## 2. The hook lifecycle

Hooks are optional shell commands configured in `hooks.json` (off entirely when the file is absent). There
are now six events spanning the whole turn. `HookService` owns them; the call sites are in `AgentEngine`,
`AgentLoop`, and `PermissionService`.

```mermaid
flowchart TD
    S[New session] -->|first turn| SS[sessionStart<br/>inject session context]
    SS --> UPS[userPromptSubmit<br/>block turn OR inject context]
    UPS --> LOOP[tool loop]
    LOOP --> PRE[preToolUse<br/>non-zero exit blocks the tool]
    PRE --> EXEC[tool executes]
    EXEC --> POST[postToolUse<br/>stdout appended to result]
    POST --> LOOP
    LOOP --> STOP[stop<br/>stdout appended to answer]
    EXEC -. needs approval .-> NOTE[notification<br/>fire-and-forget]
```

| Event | When | Effect | Env it receives |
|---|---|---|---|
| `sessionStart` | first turn of a session | stdout injected as `<session-context>` | `IMINI_SESSION` |
| `userPromptSubmit` | before a turn | non-zero exit blocks; stdout injected ahead of the prompt | `IMINI_PROMPT` |
| `preToolUse` | before a tool | non-zero exit blocks the tool (output becomes the result) | `IMINI_TOOL`, `IMINI_ARGS` |
| `postToolUse` | after a tool | stdout appended to the tool result | `+ IMINI_RESULT` |
| `notification` | agent requests approval | fire-and-forget (e.g. desktop ping) | `IMINI_NOTIFY`, `IMINI_TOOL` |
| `stop` | turn finishes | stdout appended to the final answer | `IMINI_PROMPT`, `IMINI_ANSWER` |

The guiding rule: **a hook failure never bricks the turn.** `pre`/`userPromptSubmit` can *intentionally*
block (non-zero exit), but a hook that crashes is logged and ignored.

---

## 3. The MCP server lifecycle

MCP lets imini borrow tools, resources, and prompts from external servers. It is off unless an `mcp.json`
exists. `McpManager` connects to each server (stdio child process or HTTP), runs the handshake, then
discovers everything the server offers and registers it into the normal tool registry.

```mermaid
sequenceDiagram
    participant I as McpManager
    participant S as MCP server
    I->>S: initialize
    S-->>I: capabilities
    I->>S: notifications/initialized
    I->>S: tools/list
    S-->>I: tools  -> register <server>_<tool>
    I->>S: resources/list
    S-->>I: resources -> register <server>_read_resource
    I->>S: prompts/list
    S-->>I: prompts -> register <server>_prompt_<name><br/>and /mcp__server__name slash command
```

Once connected:

- **Tools** become `<server>_<tool>` and run through the permission gate (MCP output is treated as
  untrusted and fenced).
- **Resources** are read via a `<server>_read_resource` tool (no args lists them; a `uri` reads one,
  calling `resources/read`).
- **Prompts** are exposed two ways: as a `<server>_prompt_<name>` tool, and as a slash command
  `/mcp__<server>__<name>` (parsed in `McpManager.renderPromptCommand`, dispatched from `AgentLoop`). The
  rendered prompt (`prompts/get`, with `key=value` arguments substituted) becomes the turn's input.

Transports are pluggable behind a small `Transport` interface: a stdio child process (newline-delimited
JSON-RPC) or an HTTP endpoint (`transport:"http"`, plain-JSON or single-event SSE). The
`McpLiveIntegrationTest` drives both against a stub server.

---

## Where to read next

- `GitWriteTools.java`, `GitInspector.java`, `PermissionService.java` — the commit loop and approval gate.
- `HookService.java` (+ call sites in `AgentEngine`/`AgentLoop`) — the hook lifecycle.
- `McpManager.java` — the MCP client, transports, and prompt slash commands.
- `docs/TRACE_EDIT.md` — a concrete end-to-end trace of a single edit.
- `TESTING.md` cases 549-556 — how each of these is tested (including the live MCP integration test).
