# Example 5 — a loop lifecycle observer (`tool-audit`)

**Use case:** react to what the agent does — count tool calls, log for audit, feed your own metrics —
without changing the loop and without a separate shell script.

**What this demonstrates:** an `Extension` implementing `onEvent(LoopEvent, ...)`. The harness delivers
a `PRE_TOOL_USE` before each tool runs and a `POST_TOOL_USE` after, carrying the tool name, session, args,
and (post) the result. Observe-only — it cannot block a tool. Exceptions are caught and logged, so a buggy
observer never breaks a run.

**The code:** [`ToolAuditExtension.java`](ToolAuditExtension.java).

## Install

1. Copy `ToolAuditExtension.java` into `src/main/java/com/example/imini/ext/`.
2. Rebuild + run. `GET /admin/extensions` shows `tool-audit` (it contributes no tools/agents/commands —
   it only observes).

## Try it

```bat
ask.bat "Use repo_tree, then read Tool.java."
```

**Observe:** the app log shows one `tool ... called N time(s)` line per tool call, with the running
per-tool counter and the last result size. Set `extensions.enabled=false` and the observer goes silent.
