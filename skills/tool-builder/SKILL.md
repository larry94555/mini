---
name: tool-builder
description: Before settling for built-in tools, research whether a locally installable tool (e.g. an MCP server) would better serve the request, then get permission, install it, and expose it through mini's existing discovery.
when_to_use: Use before/while selecting a tool to implement a request, when no existing tool fits well and a purpose-built, freely installable tool likely exists (e.g. a specialized data, API, or domain MCP server). Skip when an adequate built-in or already-registered tool exists.
argument-hint: the capability the request needs a tool for
allowed_tools: web_search, web_fetch, bash
---
Find, with permission install, and expose a better-fit local tool for the capability: $ARGUMENTS

Treat installation as privileged: never install anything without explicit user approval.

1. Check what already exists. Review the currently registered tools (built-ins and any MCP tools already
   loaded). If one adequately fits, use it and stop — do not add dependencies needlessly.

2. Research candidates. Use `web_search` for an installable tool for the capability, biased toward Model
   Context Protocol (MCP) servers, since mini discovers MCP tools via `mcp.json`. `web_fetch` the candidate's
   official page/repo to confirm: it is free/open, actively maintained, installable by a standard mechanism
   (e.g. `npx`/`pip`/a release binary), and exposes the capability needed. Prefer well-known, primary sources.

3. Propose to the user and get permission. Present, concisely: the candidate, what it does, why it fits
   better than the built-ins, the exact install command, and what it will be allowed to access. Ask for
   explicit approval. Do NOT proceed without a clear yes. If the user declines, fall back to the best
   available built-in tool.

4. Install (only after approval). Use the sandboxed exec tool (`bash`) to run the standard install command
   the user approved (e.g. `npx -y <package>` is launched on demand by the MCP runtime; `pip install <pkg>`;
   download a release). Verify the install succeeded (version/help check). Do not run install steps the user
   did not approve.

5. Register it for discovery. mini discovers MCP-server tools from an `mcp.json` at the working directory,
   shaped like common MCP clients:
       { "mcpServers": { "<name>": { "command": "npx", "args": ["-y", "<package>"], "env": {} } } }
   Add (or merge) an entry for the new server. On startup mini launches each configured server, asks it for
   its tools, and registers them alongside the built-ins.

6. Make it live and confirm. IMPORTANT: mini reads `mcp.json` at startup, so a newly added server is not
   available until mini is restarted — there is no hot-reload yet. Tell the user this plainly and ask them to
   restart mini, then confirm the new tool appears in the tool list before using it for the request.

Safety: only install free, reputable tools from primary sources; never pipe untrusted scripts into a shell;
keep the granted access as narrow as the task needs; and re-confirm with the user if the install command or
scope differs from what was approved. If anything is uncertain, prefer the built-in tool over installing.
