# Testing imini, feature by feature

Each test lists the **setup** you need, the **command/prompt** to run, and **what to observe**.

## Before you start

1. Start the app with `run.bat` and wait for `Started MiniAgentApplication`.
2. Keep that window visible -- the model's thinking, tool calls, permission prompts, and guard
   messages all print there.
3. Use a **second** terminal in the imini folder for `ask.bat` / `chat.bat` / `rewind.bat`.
4. Leave `agent.auto-approve=false` so you see the permission gate. (For tests that change config,
   edit `src/main/resources/application.properties` and restart the app.)

Tip: the 3B model sometimes ignores a tool. If a test doesn't trigger the tool, re-run it or make
the prompt more explicit (the prompts below already name the tool to be reliable).

---

## 1. Streaming (watch the model think)

- **Setup:** `agent.stream=true` (default).
- **Run:** `ask.bat "In one sentence, what is a large language model?"`
- **Observe (app console):** a line `[main thinking]` followed by text appearing **token by token**,
  then the final answer in your second terminal.

---

## 2. web_fetch + jsoup, and the fetch-failure fix

- **Setup:** internet access.
- **Run (success):** `ask.bat "Use web_fetch on https://text.npr.org and list three headlines you see."`
  (`text.npr.org` is plain HTML with no JavaScript, ideal for fetching.)
- **Observe:** console shows `[main:tool] web_fetch {url=https://text.npr.org}`, then an answer with
  real headlines. The tool returns clean `TITLE: ...` + text, not tag soup.
- **Run (failure path):** `ask.bat "Use web_fetch on https://httpstat.us/404 and tell me what happened."`
- **Observe:** the tool returns `ERROR: HTTP 404 ...` and the model reports the page could not be
  fetched -- instead of inventing content (this is the behavior that fixes the old CNN loop).

---

## 3. view + edit_file + the permission gate

- **Setup:** create a file named `notes.txt` in the imini folder with exactly:
  ```
  Project: imini
  Status: draft
  Owner: me
  ```
- **Run:** `ask.bat "In notes.txt, change the line 'Status: draft' to 'Status: final'."`
- **Observe:** the model may `view` the file first, then call `edit_file`. In the **app console** a
  prompt appears: `[permission] Tool 'edit_file' wants to run with: {...}  Allow? (y/N):`. Type `y`
  and press Enter. Then check the file (or `ask.bat "view notes.txt"`) -- it now says `Status: final`.
- **Also test denial:** repeat the prompt but answer `n`. Response says it was denied; file unchanged.
- **Also test a bad edit:** `ask.bat "In notes.txt replace 'Status: pending' with 'Status: done'."`
  -> `edit_file` returns `ERROR: old_str was not found` because that text isn't there.

---

## 4. Checkpoint / rewind

- **Setup:** do Test 3 first so `notes.txt` is now `Status: final` and a snapshot exists.
- **See rewind points:** open `http://localhost:8080/checkpoints` in a browser (lists snapshots).
- **Run:** `rewind.bat`
- **Observe:** response `{"result":"Rewound .../notes.txt to its state from ..."}`, and `notes.txt`
  reverts to `Status: draft`. (Snapshots live in `.imini/checkpoints/`.)

---

## 5. Sessions (multi-turn memory)

- **Setup:** none.
- **Run:**
  1. `chat.bat work1 "Remember that my favorite color is teal."`
  2. `chat.bat work1 "What is my favorite color?"`
- **Observe:** the second answer says **teal** -- the conversation is remembered.
- **Contrast:** `ask.bat "What is my favorite color?"` (one-shot) does **not** know it.

---

## 6. Session persistence / resume

- **Setup:** do Test 5 first (session `work1` now knows your color).
- **Run:** stop the app (Ctrl+C in the app window), restart with `run.bat`, then once it's ready:
  `chat.bat work1 "What is my favorite color?"`
- **Observe:** it still answers **teal**, loaded from `.imini/sessions/work1.json`. Open that file to
  see the stored transcript, and `http://localhost:8080/sessions` to list known sessions.

---

## 7. Sub-agent delegation

- **Setup:** internet access. (DuckDuckGo scraping is occasionally flaky; re-run if a search returns
  no results.)
- **Run:** `ask.bat "Use delegate_research to find what the James Webb Space Telescope is, then give me a two-sentence summary."`
- **Observe (app console):** a separate trace prefixed `[sub thinking]` and `[sub:tool] web_search ...`
  / `[sub:tool] web_fetch ...`, then the **main** agent returns the summary. The sub-agent's noisy
  search results never enter the main answer -- that's the context isolation point.

---

## 8. Context compaction

- **Setup:** temporarily set `agent.compact-token-threshold=1200` in `application.properties` and
  restart (the default 6000 is too high to trigger quickly by hand).
- **Run (same session each time):**
  1. `chat.bat big1 "List ten facts about the ocean."`
  2. `chat.bat big1 "List ten facts about mountains."`
  3. `chat.bat big1 "What was the very first thing I asked you?"`
- **Observe:** somewhere along the way the console prints
  `[compaction:main] ~NNNN tokens -> summarized K older messages, kept M recent.` The agent still
  answers step 3 correctly using the summary. **Restore** the threshold to 6000 afterward.

---

## 9. Runaway / loop guards

- **Generation cap:** set `agent.max-tokens=64`, restart, run
  `ask.bat "Write a detailed 500-word essay about clouds."` -> the reply is cut short (~64 tokens),
  proving one generation can't run away. **Restore** `agent.max-tokens=1024`.
- **Time budget:** set `agent.deadline-seconds=5`, restart, run a task needing several slow web calls
  like `ask.bat "Use delegate_research to compare three news sites' top stories in detail."` ->
  console shows `[guard:main] time budget of 5s exceeded` and a graceful "stopped" message.
  **Restore** `agent.deadline-seconds=120`.
- **Repetition / duplicate-call (observed in the wild):** if the model repeats one line 8 times you'll
  see `[guard] stream stopped: repetition (...)`; if it calls the same tool with identical args
  repeatedly you'll see `[guard:main] suppressed duplicate call to ...`. These trigger on their own
  when the small model gets stuck -- the earlier "Live Updates" loop is exactly what they prevent.

---

## 10. MCP client (optional)

- **Setup:** requires **Node.js** installed (so `npx` works). Copy `mcp.example.json` to `mcp.json`
  and trim it to just the filesystem server:
  ```json
  { "mcpServers": { "filesystem": { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "."] } } }
  ```
  Restart the app. The first run downloads the npm package (a minute or two).
- **Observe at startup:** lines like `[mcp] filesystem -> tool filesystem_list_directory`. **Note the
  exact tool names printed** -- they come from the server.
- **Run:** `ask.bat "Use the filesystem_list_directory tool to list the files in the current directory."`
  (substitute the actual tool name from the startup log if it differs).
- **Observe:** the permission gate fires (MCP tools are treated as mutating); approve with `y`, and the
  model returns the directory listing produced by the external MCP server -- a tool that lives in a
  separate process, not in imini.
- **Turn it off:** delete `mcp.json` and restart.

---

## Quick reference

| Want to test | Command |
|--------------|---------|
| One-shot question | `ask.bat "..."` |
| Multi-turn (memory) | `chat.bat SESSION "..."` |
| Undo last edit | `rewind.bat` |
| List sessions | open `http://localhost:8080/sessions` |
| List rewind points | open `http://localhost:8080/checkpoints` |
