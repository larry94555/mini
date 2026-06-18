# Getting Started with imini

New here? This is your front door. **imini** is a small, readable program that turns a local AI model into
a coding-style *agent* — it calls the model, runs tools, asks permission before changing files, and
remembers sessions. It exists to be **learned from**: the whole point is that you can read every part.

This page gets you to a first working answer, then points you at the right documents in the right order.
It should take about 30 minutes to get running and through your first lesson.

---

## 1. Pick your platform and run the simple test

You need two things: **Java 17+** (to run imini) and **llama-server** (the local AI engine). The full,
step-by-step install is in **[`INSTALL.md`](INSTALL.md)**. The short version, once those are in place:

### macOS

```sh
# Make scripts executable once
chmod +x *.sh scripts/*.sh

# Terminal 1 — start imini
./run.sh

# Terminal 2 — ask something
./ask.sh "Say hello in one sentence."
```

### Linux

```sh
chmod +x *.sh scripts/*.sh
./run.sh
# (second terminal)
./ask.sh "Say hello in one sentence."
```

### WSL (Windows Subsystem for Linux)

Open your WSL terminal (e.g. Ubuntu from the Windows Start menu) and navigate to the imini folder.
Everything runs inside WSL; you can open `http://localhost:8080` in your Windows browser.

```sh
chmod +x *.sh scripts/*.sh
./run.sh
# (second WSL terminal)
./ask.sh "Say hello in one sentence."
```

### Windows (native)

Open a Command Prompt in the imini folder (open the folder in File Explorer → click the address bar →
type `cmd` → press Enter):

```
run.bat
```
Then, in a second Command Prompt in the same folder:
```
ask.bat "Say hello in one sentence."
```

---

**What to expect on first start:**
- Libraries download (a few minutes).
- The AI model downloads (~2 GB, 5–20 minutes depending on your internet). Progress is written to
  `llama-server.log` in the imini folder.
- You are ready when you see:
  ```
  llama-server is ready.
  Started MiniAgentApplication in ... seconds
  ```
  **Leave this terminal open.** Press **Ctrl+C** to stop.

If `ask.sh` / `ask.bat` printed an answer, everything is wired correctly and you are ready to learn how
it works. If something went wrong, see the *Troubleshooting* section in [`INSTALL.md`](INSTALL.md).

---

## 2. Recommended learning path

Follow these in order. Each step is small, and each builds on the last.

1. **Run the simple test above** so you have a working system to poke at.
2. **Read [`docs/GLOSSARY.md`](docs/GLOSSARY.md)** (5 minutes) — eight terms that the rest of the
   material assumes: *harness, agent loop, tool call, plan mode, checkpoint, project memory, MCP,
   prompt-injection fencing*.
3. **Skim [`ARCHITECTURE.md`](ARCHITECTURE.md)** — sections 1 and 2 only for now (the mental model and
   the request lifecycle). You don't need the details yet; you need the shape.
4. **Work through [`docs/LEARNING_PATH.md`](docs/LEARNING_PATH.md)** — the heart of the curriculum:
   14 short modules, each telling you what to run, which files to read, and the one idea it demonstrates.
   Do them in order; stop whenever you want and come back.
5. **Read [`docs/TRACE_EDIT.md`](docs/TRACE_EDIT.md)** — a complete, annotated trace of one request from
   prompt to answer. This is the single most useful document for "how does it actually work?"
6. **Run the tests:** `./mvnw test` (or `mvnw.cmd test` on Windows). These pass without a live model and
   show how an agent harness is made *reliable* (schema checks, path confinement, retry, duplicate-call
   guards). See `TESTING.md`.
7. **Read [`docs/CONCEPT_MAP.md`](docs/CONCEPT_MAP.md)** — maps each imini piece to its Claude Code
   counterpart, so the ideas transfer.

**Prefer a guided, timed session (alone or with a group)?** Use
**[`docs/WORKSHOP.md`](docs/WORKSHOP.md)** instead of steps 3–6 — it packages the same material into a
~90-minute hands-on workshop with five labs and a checkpoint (an `./mvnw test` target) after each.

You will know you have "got it" when you can explain, in your own words, why the model and the harness
are separate, and how a tool call is validated and executed.

---

## 3. Documents available for the learning path

| Document | What it's for | When to read it |
|---|---|---|
| [`GettingStarted.md`](GettingStarted.md) | This page — the front door | First |
| [`INSTALL.md`](INSTALL.md) | Step-by-step install of Java, llama-server, and a model (all four platforms) | Before your first run |
| [`docs/GLOSSARY.md`](docs/GLOSSARY.md) | Eight core terms in plain language | Right after your first run |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | The mental model, request lifecycle, and why each component exists | For the shape, then as reference |
| [`docs/LEARNING_PATH.md`](docs/LEARNING_PATH.md) | 14 hands-on modules — the main self-study curriculum | The core of your learning |
| [`docs/WORKSHOP.md`](docs/WORKSHOP.md) | A ~90-minute guided workshop with labs + test checkpoints | For a timed or group session |
| [`docs/TRACE_EDIT.md`](docs/TRACE_EDIT.md) | One request traced end to end | After a few modules |
| [`TESTING.md`](TESTING.md) | Every feature's test + manual scenario | When running `./mvnw test` / verifying |
| [`docs/CONCEPT_MAP.md`](docs/CONCEPT_MAP.md) | imini ↔ Claude Code concept mapping | Near the end, to transfer the ideas |
| [`README.md`](README.md) | Full feature reference and HTTP endpoints | As a lookup, anytime |
| [`ROADMAP.md`](ROADMAP.md) | What's done and what's next | If you want to contribute |

---

## 4. Script reference

| Script | macOS / Linux / WSL | Windows (native) |
|---|---|---|
| Start imini | `./run.sh` | `run.bat` |
| Ask a question | `./ask.sh "..."` | `ask.bat "..."` |
| Run tests | `./mvnw test` | `mvnw.cmd test` |

On macOS/Linux/WSL, run `chmod +x *.sh scripts/*.sh` once if you see "permission denied".

Start at the top, run the simple test, then let the learning path lead. Welcome aboard.
