# Glossary

Eight terms the rest of the imini learning material assumes. Plain language, with "where to see it."

**Harness.** Everything around the model: the loop, the tools, permissions, sessions, memory, and the
web/CLI surface. The model only produces text and tool requests; the *harness* decides what actually
happens. imini is a harness you can read. *(See `AgentLoop`, `AgentEngine`.)*

**Agent loop.** The think → act → observe cycle. The model replies with either a final answer or a
**tool call**; the harness runs the tool, feeds the result back, and asks the model again — until it has a
final answer or a guard stops it. *(See `AgentEngine.converse`; traced in `docs/TRACE_EDIT.md`.)*

**Tool call.** A structured request from the model to do something — read a file, search the repo, run a
command. The harness *validates* it against a schema, runs it, and returns the result as data. Tools are
how the model affects the world; it can't touch anything the harness doesn't expose. *(See `ToolRegistry`,
`BuiltinTools`.)*

**Plan mode.** A mode where the harness asks the model to *propose* steps without executing any
file-changing actions. You get a plan to review instead of changes to undo. *(See `plan.bat`,
`PermissionService`.)*

**Checkpoint.** A saved snapshot taken before a file is changed, so an edit can be reversed (`rewind`).
A safe coding harness needs an undo story. *(See `CheckpointStore`, `rewind.bat`.)*

**Project memory.** Always-on instructions for a project, kept in an `IMINI.md` file and injected into the
system prompt every turn (e.g. "prefer concise prose"). Different from a one-off prompt or a slash command.
*(See `ProjectContext`; example in `IMINI.example.md`.)*

**MCP (Model Context Protocol).** A standard way to plug in *external* tool providers. The harness loads
them, exposes their tools, and fences their output — the model doesn't own them. *(See `McpManager`.)*

**Prompt-injection fencing.** Treating tool/web/MCP output as *data, not instructions*. A fetched web page
might say "ignore your rules"; the harness wraps such output and reminds the model not to obey it.
*(See `Untrusted`, and the fencing module in `docs/LEARNING_PATH.md`.)*

Once these eight make sense, the architecture and the learning-path modules will read easily.
