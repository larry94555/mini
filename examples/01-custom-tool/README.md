# Example 1 — a custom in-process tool (`word_count`)

**Use case:** you want the model to be able to do something the built-in tools don't cover — call your
own code — without standing up an MCP server (a separate process) or editing core Java.

**What this demonstrates:** an `Extension` that contributes one read-only `Tool`. The tool is
schema-validated and dispatched by the harness exactly like a built-in; because it's `mutating=false`
it runs without an approval prompt.

**The code:** [`WordCountExtension.java`](WordCountExtension.java) — a `@Component` implementing
`Extension.tools(...)` that returns a single `word_count` tool.

## Install

1. Copy `WordCountExtension.java` into `src/main/java/com/example/imini/ext/` (create the `ext` folder;
   it is inside the app's component-scan path, so the bean is discovered automatically).
2. Rebuild and start: `./mvnw -q -DskipTests package && ./run.sh` (or `run.bat`).
3. Confirm it loaded: `curl -s localhost:8080/admin/extensions` (admin key) — you should see
   `word-count` with `tools: ["word_count"]`.

## Try it

```bat
ask.bat "Use the word_count tool to measure this sentence: the quick brown fox."
```

**Observe:** the model calls `word_count` and reports `words=5 chars=... lines=1`. Turn the whole
mechanism off with `extensions.enabled=false` and the tool disappears — proving it's a clean, reversible
addition.
