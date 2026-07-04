# Extending the harness — a recommendation

This document answers one question: **how should a user add their own code to `imini` to test out
the harness — from a small skill or tool all the way up to a deep, `pi`-style extension that changes
how the loop itself behaves — without forking the core?**

It is a *recommendation*, not a shipped feature. The current state is assessed honestly first, then a
staged design is proposed. The companion roadmap entry is **Track L — User-extensible harness**.

---

## TL;DR

- **Today** you can already extend a lot *without recompiling Java*: skills, subagents, slash commands,
  MCP tools, hooks, and plugin bundles are all file-based and hot-reloadable. This is the right surface
  for a "small user application" and it mostly needs **documenting and one worked example**, not new code.
- **The hard boundary** is that a new *built-in tool*, a *model router*, a *context strategy*, a *permission
  mode*, or any change to the *loop* requires editing core Java. There is no in-process seam for user code.
- **Recommendation:** add a first-class **Extension SPI** — a single `Extension` interface with a handful
  of typed extension points (contribute tools/agents/commands, wrap context, choose a model, observe loop
  events), discovered from `extensions/*.jar` via Java `ServiceLoader` **and** from Spring beans, every
  contribution **capability-gated and audited** the same way tools are today. That is the `pi`-style deep
  hook. Layer an **embedded scripting bridge** (GraalJS) on top so an extension can also be a small script
  file with no build step. Keep MCP as the **out-of-process / other-language** path.
- Build it in three tiers (below). Tier 2 is the load-bearing one.

---

## What "pi-style" means here

The brief asks for extensibility "similar to `pi`." Interpreting that as: **code-level, in-process plugins
that can reach the agent's real extension points** — not just declare a prompt, but add a tool, intercept
what context reaches the model, choose which model answers, and react to loop events — while staying
isolated from the core so an upgrade doesn't break the plugin and a plugin can't quietly break the core.
That is the target this recommendation aims at. (If a different `pi` was meant, the SPI shape below still
stands; only the scripting-language choice would change.)

---

## 1. What you can already do without recompiling

Verified against the source. All of these are discovered from disk at startup and (where noted)
hot-reloadable at runtime.

| Extension | Where it lives | Format | Hot-reload | Backing code |
|-----------|----------------|--------|:----------:|--------------|
| **Skill** | `skills/<name>/SKILL.md` | Markdown + YAML front-matter (`name`, `description`, `when_to_use`, `allowed_tools`, `context: fork`) | ✅ `reload` / `refresh_skills` | `SkillService`, `SkillLibrary` |
| **Subagent** | `agents/<name>.md` | Markdown + front-matter (`tools`, `model`) — overrides built-ins | ✅ (reloads from disk) | `AgentRegistry`, `SubAgent` |
| **Slash command** | `commands/<name>.md` | Prompt template with `$ARGS` | on restart | `SlashCommands` |
| **MCP tool(s)** | `mcp.json` | JSON-RPC server spec (stdio/HTTP) | ✅ `reload_mcp` | `McpManager`, `ToolRegistry` |
| **Hook** | `hooks.json` | Shell command per event | on restart | `HookService` |
| **Plugin bundle** | JSON pack (`imini-plugin/1`) | Skills + agents + commands, hash-verified | ✅ (writes the files above) | `PluginService`, `PluginPack` |
| **Plan-lifecycle binding** | `skills.lifecycle` property | `stage=skill,skill; …` | on restart | `SkillService`, `PlanLifecycle` |
| **Project context** | `IMINI.md` / `CLAUDE.md` | Markdown, appended to system prompt | per run | `AgentLoop`, `MemoryLoader` |

**For a small user application, this is already enough for three of the four common shapes:**

1. **A workflow** → write a *skill* (Markdown) + a *subagent* with a scoped tool list.
2. **A custom tool in another language** → write an *MCP server* (Node/Python), add it to `mcp.json`,
   call `reload_mcp`. No Java, no restart.
3. **An interception / policy** → write a *hook* for `preToolUse` / `postToolUse` / `userPromptSubmit` /
   `stop` / `sessionStart` (shell, gets tool name + args + result via env vars).
4. **A custom *built-in* tool, router, or context strategy in-process** → **not possible today** without
   editing core Java. This is the gap.

---

## 2. The hard boundary (what forces a core edit today)

`ToolRegistry`'s constructor wires every tool source explicitly:

```java
for (Tool t : builtins.all()) register(t);
for (Tool t : codebase.all()) register(t);
for (Tool t : gitWrite.all()) register(t);
// … projectTools, delegate, retrieval, skills, mcp …
```

A `Tool` is just `new Tool(name, description, params, mutating, untrusted, Function<Map,String> executor)`
— a trivial value object. Yet there is **no seam** for a user to contribute one: you must add a line here
and rebuild. The same is true for:

- **Model routing / model choice** — hard-coded to primary + a fixed `summary-model` in `LlamaClient`.
- **Context strategy** — what reaches the model is fixed in `ContextManager` / `ProjectContext` / `RetrievalService`.
- **Permission modes** — `PermissionService.Mode` is a closed enum.
- **Loop behavior** — `AgentEngine` is not observable/extensible from outside except via shell hooks.
- **Retrieval ranking** — `RetrievalService` / `Bm25` are internal.

The two gaps that matter most for "test out the harness" are **in-process tools** and **model
routing / context interception**, because those are where the interesting harness tradeoffs live (and
where Tracks E–J of the roadmap want the learner to experiment).

**Why the file-based surface isn't enough on its own:** MCP tools are out-of-process (a Node/Python
runtime, JSON-RPC, latency) and cannot see harness internals; hooks are fire-and-forget shell that can
*block* a tool but can't *add* one, *route* a model, or *shape* context. Nothing lets a user run **Java
in the same process, at a real extension point, under the same guardrails**.

---

## 3. Recommendation — a three-tier extension model

### Tier 1 — Make the existing surface a first-class "user app" (small, mostly docs)

Ship the file-based surface *as a product*:

- An `extensions/` (or `apps/`) convention that bundles a skill + agent + command + `mcp.json` fragment
  + `hooks.json` fragment as one directory, installable/removable as a unit (extend `PluginPack` to carry
  hook + mcp fragments, not only skill/agent/command).
- **One worked example** in the repo: e.g. `extensions/notes-app/` — a subagent + a skill + a tiny MCP
  tool — with a README that a user copies to start their own. This is the single highest-leverage change:
  the mechanism exists, the *on-ramp* doesn't.
- A `/extensions` admin view listing what's installed and from where.

This tier needs almost no new engine code and immediately makes "write a small app to test the code" real.

### Tier 2 — An in-process **Extension SPI** (the load-bearing recommendation, the `pi`-style hook)

Add one interface with narrow, typed extension points, and discover implementations from both Spring beans
and dropped-in jars. Sketch:

```java
public interface Extension {
    default String name() { return getClass().getSimpleName(); }

    /** Contribute built-in-equivalent tools (same Tool value object used everywhere). */
    default List<Tool> tools(ExtensionContext ctx) { return List.of(); }

    /** Contribute agents / slash commands programmatically. */
    default List<AgentProfile> agents(ExtensionContext ctx) { return List.of(); }
    default List<SlashCommand> commands(ExtensionContext ctx) { return List.of(); }

    /** Shape what reaches the model (context engineering) — see, add, or trim messages. */
    default Messages wrapContext(Messages in, ExtensionContext ctx) { return in; }

    /** Choose which model answers this turn (routing) — return null to defer. */
    default ModelChoice route(RouteRequest req, ExtensionContext ctx) { return null; }

    /** Observe/annotate loop events (typed successor to shell hooks). */
    default void onEvent(LoopEvent e, ExtensionContext ctx) {}
}
```

Design rules that keep this safe and faithful to the repo's philosophy:

- **Discovery, not wiring.** `ToolRegistry`, `AgentRegistry`, `SlashCommands`, `ContextManager`,
  `LlamaClient`, and `AgentEngine` each take an injected `List<Extension>` (Spring collects all beans
  implementing the interface) **plus** anything `ServiceLoader` finds on an `extensions/*.jar` classpath.
  Replace the hard-coded `register(...)` calls with a loop over `for (Extension x : extensions) …`.
  Default-empty = byte-identical to today.
- **Same guardrails as tools.** An extension's tools go through `SchemaValidator` / `PermissionService` /
  `Sandbox` unchanged. Routing and context-wrapping run under a capability check (`CapabilityService`), are
  written to `AuditLog`, and are traced (`Tracer`) so an extension's influence is visible, never silent.
- **Capability-scoped + default-closed.** Loading external jars is off unless `extensions.enabled=true`
  and the jar is on an explicit allow-path; a `manifest` (name, version, sha256, requested capabilities)
  is required and hash-verified, mirroring `PluginRegistry`/`SkillManifest` signing.
- **Isolated classloader** per extension jar so a bad extension can't shadow core classes; failures are
  contained (a throwing extension is disabled with an audited error, the run continues).
- **Deterministic tests.** Because tools/routing/context are pure-ish functions, an extension is testable
  with the existing `ScriptedAgent` fixture — no model needed.

This is the piece that turns "small user application" into something that can genuinely **change the
harness**: add real tools, route models by your own policy, and shape context — the experiments Tracks
E–J ask for — without touching core.

### Tier 3 — Scripting bridge + typed event bus (reach + ergonomics)

- **Embedded scripting.** Register an `Extension` written as a **script** (GraalJS is the natural choice —
  polyglot, sandboxable, no build step) loaded from `extensions/*.js`. Same interface, same guardrails,
  but a user edits a file and `reload`s — the fast path for experimentation, closest to the file-based
  ergonomics of skills. (Groovy/JBang are alternatives if a JVM language is preferred.)
- **Generalize hooks into a typed event bus.** The shell `HookService` becomes one *subscriber* to the
  same `LoopEvent` stream extensions see, so shell and in-process extensions share one lifecycle model
  (`preToolUse`, `postToolUse`, `userPromptSubmit`, `stop`, `sessionStart`, plus new `preModelCall`,
  `route`, `contextAssembled`).
- **Richer out-of-process protocol.** MCP already covers tools/resources/prompts across languages; extend
  the same client so an MCP server can also serve a *context resource* or a *routing hint*, giving
  non-JVM users a slice of Tier 2 over the wire.

---

## 4. A worked "small user application" (target UX after Tier 2/3)

A user wants to test **cost-aware routing + a domain tool + injecting a house style guide into context**.
As one dropped-in extension (`extensions/house/house.js` or a jar):

```js
export default {
  name: "house",
  tools: () => [ tool("lookup_ticket", "Fetch a ticket by id", {id: "string"},
                      args => http.get(`https://tracker.internal/${args.id}`)) ],
  // Context engineering: prepend the team style guide to every turn.
  wrapContext: (msgs) => msgs.withSystem(read("extensions/house/STYLE.md")),
  // Routing: cheap model for short/plan turns, primary otherwise.
  route: (req) => req.promptTokens < 400 ? model("summary") : null,
  onEvent: (e) => { if (e.type === "stop") metrics.inc("house.turns"); }
};
```

`reload` picks it up; `lookup_ticket` is now a validated, permission-gated tool; every turn is routed and
carries the style guide; the influence is visible in the trace and the audit log. No fork, no restart,
no separate server. **That** is the bar the recommendation sets.

---

## 5. Recommended rollout (first PRs)

1. **Tier 1 example + `extensions/` bundle** — extend `PluginPack` to carry `mcp.json`/`hooks.json`
   fragments; add `extensions/notes-app/` + docs. (Small, high on-ramp value.)
2. **`Extension` interface + Spring-bean discovery for tools only** — replace `ToolRegistry`'s hard-coded
   `register` calls with a provider loop; prove an in-process bean can add a validated tool. Default-empty,
   byte-identical. (The seam that unlocks everything.)
3. **Extend the SPI to agents/commands + `LoopEvent` observation** — the typed successor to hooks.
4. **`wrapContext` + `route` extension points** — context engineering and model routing (pairs directly
   with roadmap Track G's router and Track E's caching experiments).
5. **`extensions/*.jar` ServiceLoader + isolated classloader + manifest/capability gating + audit** — the
   default-closed, signed, sandboxed external-code path.
6. **GraalJS scripting bridge + `/extensions` admin view + docs + golden tests.**

Each PR is independently shippable with a deterministic test, and every one is byte-identical to today
when no extension is present — the same discipline the rest of the roadmap holds to.

## 6. Risks & how the design contains them

- **Untrusted extension code.** Contained by: default-closed, explicit allow-path, hash-verified manifest,
  isolated classloader, capability scoping via `CapabilityService`, and full audit/trace of every
  contribution. An extension is exactly as trusted as an MCP server or a signed plugin today — no more.
- **Silent influence.** Every routing decision, context mutation, and tool call from an extension is
  traced and audited; nothing an extension does is invisible.
- **Core coupling / upgrade breakage.** The narrow, defaulted interface + isolated classloader keep
  extensions off the core's internals, so the engine can evolve without breaking plugins.
- **Scope creep.** Tiers 1–2 deliver 90% of the value; Tier 3 is optional polish — do not let the
  scripting bridge block the SPI.
