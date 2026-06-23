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

## Verifying hot-reload

The pure config-diff and registry-delta logic is unit-tested in `McpConfigTest` (always offline). The full
live path is proven by `McpHotReloadIntegrationTest`, which drives the production `reload()` against the
bundled MCP stub server and asserts the **live** tool set (republished via the same
`ToolRegistry.republishMcp` the production hook uses): one server's tools appear; adding a second leaves the
first intact; removing a server prunes its tools; and an unchanged reload is a byte-identical no-op.

It is gated on the `node` family (it spawns a child process) and `json` (discovery parses JSON-RPC), so it
self-skips offline. To run it:

```
# locally (needs node on PATH)
IMINI_REQUIRE_NODE=1 ./mvnw -Dtest=McpHotReloadIntegrationTest test
```

In CI the `Integration tests` workflow sets `IMINI_REQUIRE_NODE=1` (and a real JSON mapper is present), so the
live reload path runs there; `scripts/integration-coverage.sh` already requires the `node` family, so a
silently-skipping test fails the build. `GET /admin/mcp` reports a per-server tool count (`tools_by_server`)
so a reload's effect on each server is observable.
