# Extending the Code — Getting Started

This is the hands-on companion to [`EXTENDING.md`](EXTENDING.md) (which explains the *why* and the full
design). Here you build extensions. An **extension** is a small user application that plugs into the
harness in-process — it can add tools, subagents, and slash commands, and observe the agent loop —
**without forking core Java and without a separate server**.

Every example below has runnable code in the [`examples/`](../examples) directory. The code compiles
against the real APIs and the whole set has been booted as live beans.

---

## The 30-second mental model

- An extension is a Spring `@Component` that implements the [`Extension`](../src/main/java/com/example/imini/Extension.java)
  interface. Every method has an empty default, so you implement only what you need.
- You **install** an extension by dropping its `.java` file into `src/main/java/com/example/imini/ext/`
  and rebuilding. That package is inside the app's component-scan path, so
  [`ExtensionRegistry`](../src/main/java/com/example/imini/ExtensionRegistry.java) discovers it
  automatically — no wiring.
- Everything an extension contributes goes through the **same guardrails as a built-in**: a contributed
  tool is schema-validated and permission-gated exactly like `write_file`.
- A master kill-switch, `extensions.enabled` (default `true`), disables all extensions at once. With no
  extensions installed, the harness is byte-identical to before.
- See what loaded at any time: `GET /admin/extensions` (admin key).

```java
// The whole interface — implement only what you need.
public interface Extension {
    default String name() { return getClass().getSimpleName(); }
    default List<Tool> tools(ExtensionContext ctx) { return List.of(); }
    default List<AgentLibrary.AgentDef> agents(ExtensionContext ctx) { return List.of(); }
    default List<Command> commands(ExtensionContext ctx) { return List.of(); }
    default void onEvent(LoopEvent event, ExtensionContext ctx) { }
    record Command(String name, String description, String template) { }
}
```

### Install & verify (the same three steps for every example)

1. Copy the example's `.java` file into `src/main/java/com/example/imini/ext/` (create the `ext` folder).
2. Build and run: `./mvnw -q -DskipTests package` then `./run.sh` (or `run.bat`).
3. Confirm discovery: `curl -s -H "X-Api-Key: <admin-key>" localhost:8080/admin/extensions`.

Uninstall = delete the file and rebuild, or flip `extensions.enabled=false`.

---

## The most important use cases

### 1. Add a custom tool the model can call — *the flagship*

**Use case.** The model needs to *do* something the built-in tools don't cover — run your own code. MCP
can do this but needs a separate process; a built-in would need a core edit. An extension tool runs
in-process and installs as one file.

**Example.** [`examples/01-custom-tool`](../examples/01-custom-tool) adds a read-only `word_count` tool.

**The code (the essence):**

```java
@Component
public class WordCountExtension implements Extension {
    @Override public List<Tool> tools(ExtensionContext ctx) {
        Map<String, Object> schema = /* {type:object, properties:{text:{type:string}}, required:[text]} */;
        return List.of(new Tool("word_count",
                "Count the words, characters, and lines in a piece of text. Read-only.",
                schema, /* mutating = */ false,
                args -> {
                    String text = String.valueOf(args.getOrDefault("text", ""));
                    int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
                    return "words=" + words + " chars=" + text.length() + " lines=" + text.lines().count();
                }));
    }
}
```

**Try it:** `ask.bat "Use word_count on: the quick brown fox."` → the model calls the tool and reports
`words=5`. A `mutating=true` tool would route through the approval flow instead.

---

### 2. Integrate an external system with a config-driven domain tool

**Use case.** Wire in an issue tracker / wiki / internal API as a tool, with its endpoint configurable per
environment — the realistic shape of a small app.

**Example.** [`examples/02-domain-tool`](../examples/02-domain-tool) adds `lookup_ticket`, reading its base
URL from config and returning ticket data (canned so it runs offline; the real HTTP call is shown in a
comment).

**The code (the essence):**

```java
@Override public List<Tool> tools(ExtensionContext ctx) {
    String baseUrl = ctx.property("ext.tickets.base-url", "https://tracker.internal");  // config-driven
    return List.of(new Tool("lookup_ticket", "Fetch a ticket by id. Read-only.",
            schema, /* mutating = */ false, /* untrusted = */ true,   // fence external output as data
            args -> {
                String id = String.valueOf(args.get("id")).trim().toUpperCase();
                // real impl: return httpClient.get(baseUrl + "/api/tickets/" + id);
                return CANNED.getOrDefault(id, "No ticket " + id);
            }));
}
```

**Note the two safety touches:** `ctx.property(...)` reads `application.properties`, and `untrusted=true`
tells the harness to fence the external output as data, not instructions. Because an extension is a Spring
bean, a production version just injects an HTTP client via its constructor.

**Try it:** `ask.bat "Look up ticket PROJ-1 and tell me its status."`

---

### 3. Ship a custom subagent

**Use case.** A focused, tool-scoped specialist the main agent can delegate a subtask to — returning only
its conclusion, keeping its intermediate work out of the main context. Shipped as code, not an
`agents/*.md` file.

**Example.** [`examples/03-custom-subagent`](../examples/03-custom-subagent) adds a read-only `stylecheck`
subagent.

**The code (the essence):**

```java
@Override public List<AgentLibrary.AgentDef> agents(ExtensionContext ctx) {
    return List.of(new AgentLibrary.AgentDef("stylecheck",
            "Check code against this team's style rules (read-only).",
            List.of("read_file", "view", "grep", "repo_tree"),   // read-only tool scope
            "",                                                   // default model
            "You are a style-check subagent. Using only the read-only tools, report violations of ..."));
}
```

**Try it:** `ask.bat "/agent stylecheck src/main/java/com/example/imini/Tool.java"`. A disk
`agents/stylecheck.md` overrides this (disk wins over extensions wins over built-ins).

---

### 4. Ship a slash command

**Use case.** A reusable prompt shortcut for your team, bundled in code with the rest of your extension.

**Example.** [`examples/04-slash-command`](../examples/04-slash-command) adds `/shout`.

**The code (the essence):**

```java
@Override public List<Command> commands(ExtensionContext ctx) {
    return List.of(new Command("shout", "Reply in emphatic UPPERCASE.",
            "Rewrite the following in emphatic, ALL-CAPS shouting, keeping the meaning:\n\n$ARGS"));
}
```

`$ARGS` (or `$ARGUMENTS`) is replaced by the text after the command; the expansion becomes the prompt.

**Try it:** `ask.bat "/shout we shipped the extension api"`.

---

### 5. Observe the agent loop

**Use case.** React to what the agent does — count tool calls, log for audit, feed your own metrics —
without changing the loop and without a shell script. The typed, in-process successor to a `postToolUse`
hook. **Observe-only:** it cannot block a tool.

**Example.** [`examples/05-lifecycle-observer`](../examples/05-lifecycle-observer) adds `tool-audit`,
which counts and logs each completed tool call.

**The code (the essence):**

```java
@Override public void onEvent(LoopEvent event, ExtensionContext ctx) {
    if (event.type() == LoopEvent.Type.POST_TOOL_USE) {
        long n = counts.computeIfAbsent(event.tool(), k -> new AtomicLong()).incrementAndGet();
        ctx.log().info("tool " + event.tool() + " called " + n + " time(s); "
                + "result " + (event.result() == null ? 0 : event.result().length()) + " chars");
    }
}
```

The harness delivers `PRE_TOOL_USE` before each tool and `POST_TOOL_USE` after. A throw inside `onEvent`
is caught and logged — a buggy observer never breaks a run.

**Try it:** `ask.bat "Use repo_tree, then read Tool.java."` and watch the log.

---

### 6. Bundle a whole small app in one extension

**Use case.** Package a cohesive feature — tools + a specialist subagent + a shortcut command + an
observer — as a single installable unit. This is what the extension API is *for*.

**Example.** [`examples/06-mini-app`](../examples/06-mini-app) is `notes-app`: `note_add`/`note_list`
tools, a `notes` subagent scoped to them, a `/notes` command, and an observer — all in one class.

**Try it:**

```bat
chat.bat demo "Remember the release is Friday, and Priya owns the changelog."
chat.bat demo "/notes"
```

One installed file, four capabilities, all toggled together by `extensions.enabled`.

---

## How this stays safe

- **Same guardrails as built-ins.** Contributed tools are schema-validated and permission-gated; a
  `mutating=true` tool routes through `PermissionService`/`Sandbox` like `write_file`.
- **Fenced external output.** Set `untrusted=true` on tools whose output comes from outside (Example 2) and
  the harness treats it as data, not instructions.
- **Isolation.** A throwing extension is logged and skipped — it can't crash startup or a run. Extension
  tool names can't shadow core/MCP tools (collisions are skipped and warned).
- **Off by one switch.** `extensions.enabled=false` disables everything; with nothing installed, behavior
  is byte-identical to stock.
- **Observable.** `GET /admin/extensions` shows exactly what loaded and what each contributed; loop events
  and tool calls are traced.

## Where to go deeper

- The design, the current-vs-target extension surface, and the roadmap for external `*.jar` / GraalJS
  loading: [`EXTENDING.md`](EXTENDING.md) and **Track L** in [`../ROADMAP.md`](../ROADMAP.md).
- The SPI source: [`Extension.java`](../src/main/java/com/example/imini/Extension.java),
  [`ExtensionRegistry.java`](../src/main/java/com/example/imini/ExtensionRegistry.java),
  [`ExtensionContext.java`](../src/main/java/com/example/imini/ExtensionContext.java),
  [`LoopEvent.java`](../src/main/java/com/example/imini/LoopEvent.java).
- The tests that pin the behavior: `ExtensionRegistryTest` and `ExtensionToolTraceTest`.
