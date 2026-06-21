# Trace: A Grand Tour — edit→commit, delegation, and MCP in one session

`docs/TRACE_EDIT.md` walks a single safe edit from prompt to final answer. This trace is the *grand tour*:
one realistic session whose model touches **several** workflow branches in sequence — an edit→commit with a
hook firing, a delegation to a named subagent, and an MCP tool call — so you can see how the pieces compose
in a single turn. Each step names the key files and the **golden-trace test** that proves it (see also
[`WORKFLOW_WALKTHROUGH.md` §4](WORKFLOW_WALKTHROUGH.md#4-how-each-branch-is-proven-the-golden-trace-suite), which maps every branch to its test).

This is a teaching narrative. The exact tool calls a real model emits will vary; the harness behavior —
validation, the permission gate, hooks, delegation isolation, MCP fencing, the git-verified edit summary —
is what is fixed and asserted.

## Scenario

The user asks, in one message:

> "Finalize `app.txt` and commit it, then have the research subagent find the current spec version, and read
> the `greeting` resource from the docs MCP server. Summarize what you did."

Mode is `auto` (mutations are auto-approved in-workspace; deny rules and workspace confinement still apply —
see `TRACE_EDIT.md` Step 6). The workspace is a git repo with `hooks.json`, an `agents/` directory, and an
`mcp.json` pointing at a `docs` server.

## Step 1: HTTP entry and loop start

The request enters at `AgentController` (`/chat` or `/chat/stream`), `AgentLoop` assembles the system prompt
(base + project memory + tool schemas), and `AgentEngine.run(...)` begins the loop with label `"main"`. This
is identical to `TRACE_EDIT.md` Steps 1–3 and is not repeated here.

Key files: `AgentController.java`, `AgentLoop.java`, `AgentEngine.java`.

## Step 2: The model edits the file

The model emits an `edit_file` tool call:

```json
{
  "role": "assistant",
  "tool_calls": [
    { "id": "call_0", "type": "function",
      "function": { "name": "edit_file",
        "arguments": "{\"path\":\"app.txt\",\"old_str\":\"status: draft\",\"new_str\":\"status: final\"}" } }
  ]
}
```

`SchemaValidator` checks the arguments; `PermissionService` returns `ALLOW` (auto-approved, in-workspace);
`BuiltinTools.editFile` snapshots a checkpoint and writes. The tool result returns the change confirmation.

Key files: `SchemaValidator.java`, `PermissionService.java`, `BuiltinTools.java`.
Proven by: `GoldenTraceWorkflowTest.editStageCommitTrace` (the edit lands and the gate returns `ALLOW`).

## Step 3: The model stages and commits — and a hook fires

The model calls `git_stage` then `git_commit`. Both are mutating and pass the gate in `auto`. Because
`hooks.json` registers a `preToolUse` hook matching `git_commit`, the harness runs that shell command
*before* the commit (e.g. a lint or a marker write); its failure would block the commit. After the tool runs,
a `postToolUse` hook can append context to the tool result:

```text
<commit output>
[post-hook]
<stdout of the post hook>
```

When the turn later ends, `stop` hooks fire and their stdout is appended to the final answer under a
`[stop-hook]` line.

Key files: `GitWriteTools.java`, `HookService.java` (+ call sites in `AgentEngine`).
Proven by: `GoldenTraceWorkflowTest.editStageCommitTrace` (asserts the `preToolUse` marker file is written
and the `stop` hook output is appended to the answer). Hook lifecycle: `WORKFLOW_WALKTHROUGH.md` §2.

## Step 4: The model delegates to a named subagent

To find the spec version, the model hands a focused task to the `research` subagent rather than searching in
its own context:

```json
{
  "role": "assistant",
  "tool_calls": [
    { "id": "call_1", "type": "function",
      "function": { "name": "delegate_agent",
        "arguments": "{\"name\":\"research\",\"task\":\"find the current spec version\"}" } }
  ]
}
```

`ToolRegistry.delegateToAgent` looks the agent up in `AgentRegistry`, scopes it to that agent's (read-only)
tools, and calls `SubAgent.run(...)` — a **nested** `AgentEngine.run` with label `"sub"`. The subagent runs
its *own* loop (its own `web_search`/`web_fetch` calls and a final answer); all of that intermediate context
stays inside the sub-conversation. Only the subagent's final answer returns to the parent, as the
`delegate_agent` tool result:

```text
The current spec version is 2.3 (per spec.example.org, fetched just now).
```

That isolation — the parent's window only ever sees the clean result — is the whole point of subagents.

Key files: `ToolRegistry.java`, `AgentRegistry.java`, `SubAgent.java`.
Proven by: `SubAgentHandoffTraceTest.parentDelegatesToSubagentAndIncorporatesItsResult` (the sub's tool runs
in its nested loop and its answer returns into the parent transcript). Delegation lifecycle:
`WORKFLOW_WALKTHROUGH.md` §3a.

### If the subagent fails

If the subagent's tool throws, `safeExec` turns it into an `ERROR:` result the *sub* sees, and the sub still
returns a final answer to the parent — the parent does not crash. If the subagent loops on the same call, its
own duplicate-call guard stops it and the parent receives `[stopped: the model kept repeating the same tool
call.]` as the delegate result. Proven by: `SubAgentFailureTraceTest`.

## Step 5: The model calls an MCP tool

`mcp.json` declared a `docs` server, so at startup `McpManager` connected, ran the handshake, and registered
its capabilities as namespaced tools: `docs_<tool>`, a `docs_read_resource` tool, and `docs_prompt_<name>`.
The model reads the `greeting` resource:

```json
{
  "role": "assistant",
  "tool_calls": [
    { "id": "call_2", "type": "function",
      "function": { "name": "docs_read_resource", "arguments": "{\"uri\":\"mem://greeting\"}" } }
  ]
}
```

`McpManager` issues `resources/read` over the server's transport (stdio or HTTP, including a multi-event or
unbounded `text/event-stream`). The returned content is wrapped as **untrusted** (`Untrusted.java`) and
fenced before it reaches the model, so server text is treated as data, not instructions.

Key files: `McpManager.java`, `Untrusted.java`.
Proven by: `McpLiveIntegrationTest` (stdio + HTTP discovery and invocation, multi-server `<server>_<tool>`
namespacing, terminating + unbounded SSE). MCP lifecycle: `WORKFLOW_WALKTHROUGH.md` §3.

## Step 6: The final answer, with a git-verified edit summary

With no more tool calls, the model writes its summary. Because `agent.verify-edits` (or the coding report) is
on, `AgentLoop` appends a block computed from the *real* git state — not the model's claims:

```text
---
Edits (verified with git):
- changed files: app.txt (M)
- git diff --stat: 1 file changed, 1 insertion(+)
```

Then `stop` hooks append their output. The final answer the user sees is the model's prose + the verified
edit block + any stop-hook text.

Key files: `AgentLoop.java`, `EditSummary.java`, `GitInspector.java`.
Proven by: `GoldenTraceWorkflowTest.editStageCommitTrace` (the edit-trust summary names the changed file).

## Step 7: State persists

The session history, checkpoints, run record, and any audit/cost entries are written to SQLite (in-memory
fallback when no driver is present). The next turn can resume, rewind a checkpoint, or inspect metrics. See
`TRACE_EDIT.md` Step 12.

Key files: `SessionStore.java`, `CheckpointStore.java`, `RunRecorder.java`, `Database.java`.

## Branches this tour did not take (but the suite proves)

| Branch | Golden-trace test |
| --- | --- |
| Plan mode — record a mutation without executing it | `RecoveryTraceTest.planModeRecordsButDoesNotExecute` |
| Invalid args → corrective feedback → retry | `RecoveryTraceTest.invalidArgsBecomeFeedbackThenRecover` |
| Duplicate-call guard | `RecoveryTraceTest.duplicateCallGuardStopsRepetition` |
| Capability scoping — out-of-scope tool denied + audited | `CapabilityScopingTraceTest.toolOutsideRoleScopeIsDeniedAndNotExecuted` |
| Per-tenant rate limiting — `RATE_LIMITED` | `CapabilityScopingTraceTest.toolOverRateLimitReturnsRateLimited` |
| MCP prompt as a turn input (`/mcp__server__prompt`) | `GoldenTraceWorkflowTest.mcpPromptSlashCommandTrace` |
| Subagent failure propagation | `SubAgentFailureTraceTest` |

## What this trace teaches

- A single turn can chain mutation, delegation, and external tools, and each composes through the *same*
  dispatch loop: validate → capability check → rate limit → permission decide → pre-hook → execute →
  post-hook → record.
- Delegation is just a nested loop with a scoped tool set; isolation is what keeps the parent's context clean.
- MCP output is fenced as untrusted no matter how it arrives (stdio or streaming HTTP).
- The edit summary the user sees is computed from git, not asserted by the model — trust comes from
  verification, not narration.
- Every branch above is backed by a deterministic, model-free golden trace (the `ScriptedAgent` fixture +
  the real `AgentEngine`), so the behavior in this narrative is continuously checked, not just described.
  See [`WORKFLOW_WALKTHROUGH.md` §4](WORKFLOW_WALKTHROUGH.md#4-how-each-branch-is-proven-the-golden-trace-suite) and `TESTING.md` cases 557-571.
