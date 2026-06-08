# mini-agent — a low-end Claude Code, in ~5 small classes

A minimal tool-using agent harness over a local `llama-server` running
`Qwen/Qwen2.5-3B-Instruct`. It mirrors the architecture of a real coding agent stripped to
the load-bearing parts so you can see exactly which work is the **harness** and which is the
**model**.

## What's here

| File | Role | Maps to, in Claude Code... |
|------|------|----------------------------|
| `LlamaServerManager.java` | Launches & supervises `llama-server` | process/runtime bootstrap |
| `LlamaClient.java` | Calls `/v1/chat/completions` | the model — a stateless function |
| `Tool.java` / `ToolRegistry.java` | 5 tools + their JSON specs | the 40+ tools |
| `PermissionGate.java` | Approve mutating actions | the (huge) permission subsystem |
| `AgentLoop.java` | The think→act→observe loop | the "query engine" / orchestrator |
| `AgentController.java` | `POST /ask` | the REPL / SDK entry point |

The loop is ~50 lines. That is the whole point: the agent-ness is small; the intelligence is
in the model weights, not in this repo.

## Prerequisites

1. **`llama-server.exe`** from llama.cpp on your `PATH` (the build that supports `--jinja` and
   `-hf` auto-download). On first run it pulls the Qwen2.5-3B GGUF from Hugging Face.
2. **JDK 17+** and **Maven**.

## Run

```bash
mvn spring-boot:run
```

On startup the app launches `llama-server` (logs go to `llama-server.log`), waits for
`/health`, then serves the agent on `http://localhost:8080`.

Ask it something:

```bash
curl -X POST http://localhost:8080/ask ^
  -H "Content-Type: application/json" ^
  -d "{\"question\":\"What is the current top story on FoxNews.com?\"}"
```

(Use `\` line-continuations instead of `^` on macOS/Linux.)

For a mutating request, watch the **server console** — the permission gate will prompt
`Allow? (y/N)`. Set `agent.auto-approve=true` in `application.properties` to skip it.

## What happens for "top story on FoxNews.com"

1. Harness sends your question + the 5 tool specs to the model.
2. Model decides it needs live data and asks for `web_fetch(url=\"https://www.foxnews.com\")`.
3. Harness runs the HTTP GET, strips the HTML to text (mechanical), truncates it, returns it.
4. Harness feeds that text back; model reads it and **decides which item is the top story** and
   writes the answer.
5. Model returns text with no tool call → harness returns it to you.

The only "HTML interpretation" the code does is deleting tags (step 3). Choosing the headline
(step 4) is entirely the model.

## Known limitations (deliberately — these are the next lessons)

- **3B model.** Tool-calling reliability is modest. `AgentLoop.extractToolCalls` includes a
  fallback parser for `<tool_call>...</tool_call>` text, but expect occasional misfires.
- **Crude HTML stripping.** Swap the regex in `ToolRegistry.htmlToText` for `jsoup` for real pages.
- **No context compaction.** With `--ctx-size 8192`, long runs will overflow. Real harnesses
  summarize/trim old turns — a great thing to add next.
- **No streaming, one conversation at a time, no sandboxing.** `run_command` executes whatever
  the model asks (after approval) on your machine — keep `auto-approve=false`.
- **Single tool round budget** capped at 10 iterations to prevent runaway loops.

## Good next exercises

1. Add context compaction when the message list passes ~6k tokens.
2. Add streaming so you see the model think in real time.
3. Add a second "sub-agent" loop and have the main loop delegate a search to it.
4. Replace the regex HTML strip with `jsoup` and pass only the main article region.
