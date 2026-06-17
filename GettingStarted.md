# Getting Started with imini

New here? This is your front door. **imini** is a small, readable program that turns a local AI model into
a coding-style *agent* — it calls the model, runs tools, asks permission before changing files, and
remembers sessions. It exists to be **learned from**: the whole point is that you can read every part.

This page gets you to a first working answer, then points you at the right documents in the right order.
It should take about 30 minutes to get running and through your first lesson.

---

## 1. Set up for a simple test

You need two things: **Java** (to run imini) and **llama-server** (the local AI engine). The full,
click-by-click install — including how to get a model file — is in **[`INSTALL.md`](INSTALL.md)**. The
short version once those are in place:

1. **Start imini.** From the project folder, in a terminal:

   ```
   run.bat
   ```

   The first start is slow (it downloads libraries and a ~2 GB model). You're ready when you see
   `llama-server is ready.` and `Started MiniAgentApplication`. **Leave this window open.**

2. **Ask it something.** Open a *second* terminal in the same folder:

   ```
   ask.bat "Say hello in one sentence."
   ```

   The answer prints after a moment, and the first window shows the model "thinking" token by token.
   That's the whole system working end to end: your prompt → the harness → the model → an answer.

3. **Stop it** by pressing **Ctrl+C** in the first window.

> On macOS/Linux use the `.sh` scripts if present, or the equivalent `curl` calls shown in the README's
> *HTTP endpoints* section. If anything misbehaves, the *If something goes wrong* part of `INSTALL.md`
> covers the common cases.

That's the "simple test." If `ask.bat` printed an answer, everything is wired correctly and you're ready
to learn how it works.

---

## 2. Recommended learning path

Follow these in order. Each step is small, and each builds on the last.

1. **Run the simple test above** so you have a working system to poke at.
2. **Read the [`docs/GLOSSARY.md`](docs/GLOSSARY.md)** (5 minutes) — eight terms that the rest of the
   material assumes: *harness, agent loop, tool call, plan mode, checkpoint, project memory, MCP,
   prompt-injection fencing*.
3. **Skim [`ARCHITECTURE.md`](ARCHITECTURE.md)** — sections 1 and 2 only for now (the mental model and the
   request lifecycle). You don't need the details yet; you need the shape.
4. **Work through [`docs/LEARNING_PATH.md`](docs/LEARNING_PATH.md)** — the heart of the curriculum: 14
   short modules, each telling you what to run, which files to read, and the one idea it demonstrates.
   Do them in order; stop whenever you want and come back.
5. **Read [`docs/TRACE_EDIT.md`](docs/TRACE_EDIT.md)** — a complete, annotated trace of one request from
   prompt to answer. This is the single most useful document for "how does it actually work?"
6. **Run the tests:** `mvn test`. These pass without a live model and show how an agent harness is made
   *reliable* (schema checks, path confinement, retry, duplicate-call guards). See `TESTING.md`.
7. **Read [`docs/CONCEPT_MAP.md`](docs/CONCEPT_MAP.md)** — maps each imini piece to its Claude Code
   counterpart, so the ideas transfer.

**Prefer a guided, timed session (alone or with a group)?** Use
**[`docs/WORKSHOP.md`](docs/WORKSHOP.md)** instead of steps 3–6 — it packages the same material into a
~90-minute hands-on workshop with five labs and a checkpoint (a `mvn test` target) after each.

You'll know you've "got it" when you can explain, in your own words, why the model and the harness are
separate, and how a tool call is validated and executed.

---

## 3. Documents available for the learning path

| Document | What it's for | When to read it |
|---|---|---|
| [`GettingStarted.md`](GettingStarted.md) | This page — the front door | First |
| [`INSTALL.md`](INSTALL.md) | Click-by-click install of Java, llama-server, and a model | Before your first run |
| [`docs/GLOSSARY.md`](docs/GLOSSARY.md) | Eight core terms in plain language | Right after your first run |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | The mental model, request lifecycle, and why each component exists | For the shape, then as reference |
| [`docs/LEARNING_PATH.md`](docs/LEARNING_PATH.md) | 14 hands-on modules — the main self-study curriculum | The core of your learning |
| [`docs/WORKSHOP.md`](docs/WORKSHOP.md) | A ~90-minute guided workshop with labs + test checkpoints | For a timed or group session |
| [`docs/TRACE_EDIT.md`](docs/TRACE_EDIT.md) | One request traced end to end | After a few modules |
| [`TESTING.md`](TESTING.md) | Every feature's test + manual scenario | When running `mvn test` / verifying |
| [`docs/CONCEPT_MAP.md`](docs/CONCEPT_MAP.md) | imini ↔ Claude Code concept mapping | Near the end, to transfer the ideas |
| [`README.md`](README.md) | Full feature reference and HTTP endpoints | As a lookup, anytime |
| [`ROADMAP.md`](ROADMAP.md) | What's done and what's next | If you want to contribute |

Start at the top, run the simple test, then let the learning path lead. Welcome aboard.
