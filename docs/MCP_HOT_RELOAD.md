# MCP hot-reload

`mini` discovers MCP-server tools from an `mcp.json` at the working directory. Historically that file was
read only at startup; **hot-reload** lets a newly installed MCP server become available without restarting.

## How to use

After editing `mcp.json` (e.g. adding a server), trigger a reload:

- **Tool:** call `reload_mcp` (admin-gated, mutating) from the agent.
- **HTTP:** `POST /admin/mcp/reload` (admin).

`mini` re-reads `mcp.json`, diffs it against the running servers, **stops** removed/changed servers, **launches**
added/changed ones, re-discovers their tools, and republishes the live tool set. The new tools are then usable
in the same session. Inspect state any time with `GET /admin/mcp`.

MCP stays **off** unless an `mcp.json` exists, and reload is **idempotent** — a no-op when nothing changed.

## What counts as a change

A server is *restarted* when its normalized spec differs in any of: `command`, `args`, `env`, `transport`,
or `url`. Servers only present in the old config are *removed* (their tools pruned); servers only in the new
config are *added*.

## Design (pure core)

- `McpConfig.ServerSpec` — a normalized, equals-comparable server definition.
- `McpConfig.diff(running, desired)` — a pure add/remove/restart/unchanged plan; `serversToStop()` =
  removed ∪ changed and `serversToStart()` = added ∪ changed express the registry delta.
- `McpManager.reload()` applies the plan (stop → prune tools by `<server>_` prefix → launch → re-discover),
  then runs a hook that calls `ToolRegistry.refreshMcpTools()` to swap the MCP tools in the live registry
  while leaving the built-ins untouched. The last result is retained for `GET /admin/mcp`.

The diff/spec/registry-delta logic is pure and unit-tested offline; tests that spawn a real MCP child gate on
the `node` integration family. See TESTING cases 644-645.
