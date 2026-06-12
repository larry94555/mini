# Trace: A Safe File Edit from Prompt to Final Answer

This document shows one complete Claude Code-style harness trace in `imini`. The exact model wording will vary, but the control flow should look like this.

The example uses a scratch file so the flow is safe to reproduce.

## Scenario

User goal:

```text
Create scratch-trace.txt with the text "status: draft", then change it to "status: final" and verify the diff.
```

Recommended mode:

```text
auto
```

Why `auto` for this trace?

- It removes the human approval pause from the example.
- Workspace confinement and checkpoints still apply.
- In normal work, use `ask` when you want approval before mutation.

## Step 1: HTTP entry

The request arrives at the controller.

```http
POST /ask
Content-Type: application/json

{
  "question": "Create scratch-trace.txt with the text 'status: draft', then change it to 'status: final' and verify the diff.",
  "mode": "auto"
}
```

In the streaming UI, the same request goes to `/ask/stream` and returns Server-Sent Events.

Key files:

- `AgentController.java`
- `RunService.java`
- `RunSink.java`
- `Sse.java`

## Step 2: AgentLoop builds the prompt

`AgentLoop` collects the pieces that become the model input:

- the base system prompt,
- optional project memory from `IMINI.md`, `CLAUDE.md`, or `AGENTS.md`,
- optional profile guidance from `AgentProfile`,
- slash-command expansion if the message starts with `/`,
- the user message,
- the available tool schemas.

Conceptually:

```json
[
  {
    "role": "system",
    "content": "You are imini... Use repo tools before editing..."
  },
  {
    "role": "user",
    "content": "Create scratch-trace.txt..."
  }
]
```

Key files:

- `AgentLoop.java`
- `ProjectContext.java`
- `AgentProfile.java`
- `SlashCommands.java`
- `ToolRegistry.java`

## Step 3: AgentEngine starts the loop

`AgentEngine` owns the think -> act -> observe loop.

The loop maintains:

- message history,
- deadline budget,
- duplicate-call counts,
- plan-mode records,
- interrupt and steering checks,
- tool-call validation,
- hook execution,
- tool-result fencing.

Key file:

- `AgentEngine.java`

## Step 4: The model asks to create the file

The model may emit a tool call like this:

```json
{
  "role": "assistant",
  "tool_calls": [
    {
      "id": "call_0",
      "type": "function",
      "function": {
        "name": "write_file",
        "arguments": "{\"path\":\"scratch-trace.txt\",\"content\":\"status: draft\\n\"}"
      }
    }
  ]
}
```

## Step 5: The harness validates the tool call

Before any tool runs, `SchemaValidator` checks the arguments against the tool schema.

For `write_file`, required fields are:

- `path`
- `content`

If the model had omitted `content`, the harness would not run the tool. It would return an `INVALID_ARGS` tool result so the model could repair the call.

Key file:

- `SchemaValidator.java`

## Step 6: PermissionService decides whether mutation is allowed

Because `write_file` is mutating, the permission layer runs.

In `auto` mode:

- rules are still checked,
- deny rules still win,
- workspace confinement still applies,
- but allowed in-workspace mutations do not prompt.

Conceptual decision:

```text
ALLOW: auto-approved, target path stays inside workspace
```

Key file:

- `PermissionService.java`

## Step 7: BuiltinTools creates a checkpoint and writes

The tool layer snapshots before mutation and then writes the file.

Conceptual tool result:

```text
Wrote /path/to/repo/scratch-trace.txt (snapshot saved for rewind).
```

Key files:

- `BuiltinTools.java`
- `CheckpointStore.java`
- `Database.java`

## Step 8: The tool result returns to the model

The harness appends a tool-result message:

```json
{
  "role": "tool",
  "tool_call_id": "call_0",
  "name": "write_file",
  "content": "Wrote /path/to/repo/scratch-trace.txt (snapshot saved for rewind)."
}
```

The model now has evidence that the file was created.

## Step 9: The model asks to edit the file

The next assistant turn may request:

```json
{
  "role": "assistant",
  "tool_calls": [
    {
      "id": "call_1",
      "type": "function",
      "function": {
        "name": "edit_file",
        "arguments": "{\"path\":\"scratch-trace.txt\",\"old_str\":\"status: draft\",\"new_str\":\"status: final\"}"
      }
    }
  ]
}
```

The same safety steps happen again:

1. schema validation,
2. permission decision,
3. path confinement,
4. checkpoint,
5. edit.

Conceptual tool result:

```text
Edited /path/to/repo/scratch-trace.txt (1 replacement; snapshot saved for rewind).
```

## Step 10: The model verifies with git_diff

A coding-quality run should verify its edits. The model may call:

```json
{
  "role": "assistant",
  "tool_calls": [
    {
      "id": "call_2",
      "type": "function",
      "function": {
        "name": "git_diff",
        "arguments": "{\"path\":\"scratch-trace.txt\"}"
      }
    }
  ]
}
```

Conceptual result:

```diff
diff --git a/scratch-trace.txt b/scratch-trace.txt
new file mode 100644
--- /dev/null
+++ b/scratch-trace.txt
@@
+status: final
```

If the file already existed, the diff would show the line replacement instead.

Key file:

- `CodebaseTools.java`

## Step 11: The final answer summarizes the work

A good final answer should be explicit:

```text
Created and updated scratch-trace.txt.

Changed files:
- scratch-trace.txt

Verification:
- Ran git_diff for scratch-trace.txt.
- The diff shows the file now contains: status: final

No tests were run because this was a documentation/text-file change.
```

## Step 12: State persists

After the run:

- the session history is persisted,
- checkpoints are persisted,
- todos may be persisted if used,
- metrics counters are updated,
- the UI can show the run output and approval history.

Key files:

- `SessionStore.java`
- `CheckpointStore.java`
- `Database.java`
- `Metrics.java`
- `static/index.html`

## Failure path: invalid args

If the model emits:

```json
{
  "name": "edit_file",
  "arguments": "{\"path\":\"scratch-trace.txt\"}"
}
```

The harness returns something like:

```text
INVALID_ARGS for edit_file: missing required field 'old_str'
```

The tool is not run. The model gets a chance to recover.

## Failure path: path escape

If the model emits:

```json
{
  "name": "write_file",
  "arguments": "{\"path\":\"../escape.txt\",\"content\":\"bad\"}"
}
```

The permission/sandbox layer returns:

```text
DENIED: '../escape.txt' is outside the workspace (...)
```

The tool is not run.

## Failure path: repeated tool call

If the model repeatedly asks for the same mutating call, the harness counts duplicates and eventually suppresses the loop.

Concept:

```text
The harness should not blindly execute repeated identical mutations.
```

## What this trace teaches

The important lesson is that the safety and reliability are not inside the model. They are in the harness:

- validation before execution,
- permissions before mutation,
- checkpoints before writes,
- deterministic tools for verification,
- durable state after the run,
- and honest final summaries.

That is the core architecture behind a Claude Code-style tool.
