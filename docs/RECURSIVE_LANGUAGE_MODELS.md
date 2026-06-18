# Recursive Language Models (RLM) — and why imini does not use them (yet)

This is a concept note. Recursive Language Models are **not** implemented in imini. The pattern is
important enough — and close enough to what imini already does — that it is worth explaining clearly: what
it is, what it buys you, why it does not fit imini's current architecture, and the specific circumstances
under which it (or a lighter variant) would be the right tool.

If you only remember one sentence: *imini already does the easy, safe 80% of the RLM idea; the remaining
20% requires a code sandbox and a stronger model than imini targets, so it is documented here rather than
built.*

---

## 1. What RLM is

A normal language-model call puts the entire prompt into the model's context window and runs one forward
pass: `answer = llm(prompt)`. When the prompt is larger than the window, you must drop or compress
something first, and whatever you drop the model never sees.

A Recursive Language Model keeps the same outer interface — string in, string out — but changes what
happens inside. The long prompt is **not** fed to the model. Instead it is placed as a *variable* in a
persistent code environment (a REPL), and a "root" model is given two powers:

1. it can write and run code that peeks at, slices, searches, and filters that variable; and
2. it can call a language model (often *itself*, recursively) on any snippet it carves out.

The root model iterates — explore a bit, sub-call on the interesting pieces, combine the results — and
when it has an answer it writes it to a designated `Final` variable, which ends the loop and is returned.

The slogan from the literature is that the prompt becomes part of the **environment** rather than the
**input**: the model goes from "here is a book, read it" to "here is a library, search it, dissect it, and
delegate parts of it to assistants." Context handling stops being an attention-window problem and becomes a
program-synthesis problem — the model writes the retrieval/decomposition strategy on the fly.

## 2. What RLM accomplishes

- **Effectively unbounded input.** Because the prompt lives in the environment and only small snippets ever
  enter a context window, an RLM can work over inputs one to two orders of magnitude larger than the base
  model's window.
- **Resistance to "context rot."** Long-context models often degrade as the window fills with mostly
  irrelevant tokens. An RLM only ever puts small, relevant slices in front of the model, so quality holds
  up on hard long-context tasks instead of decaying.
- **Unbounded, structured output.** Output can be accumulated in environment variables across many
  sub-calls, so the final result is not limited to one generation's length.
- **No lossy up-front compression.** Unlike truncation or summarize-then-answer, an RLM does not throw
  information away before it starts. Every region can be reached if the model decides it is relevant; the
  decision is made *during* the task, not before it.

The published results are strong, but note the conditions: the gains were demonstrated with capable
frontier models (and a small model **post-trained specifically to drive the RLM loop**). The paradigm
assumes a model that can reliably write correct decomposition code.

## 3. Why imini does not use RLM today

imini is an education-grade harness over a local `llama-server` running a small (~3B) model, with a
deliberate no-arbitrary-code safety posture. Three concrete mismatches make a faithful RLM the wrong fit
right now.

### 3a. RLM's engine is a code sandbox; imini deliberately has none

The defining mechanism of an RLM is a Turing-complete REPL that holds the prompt as a variable and runs
model-written code against it. imini's honest-scope rule is **"pattern sandbox ≠ syscall"**: its tools are
pattern-based, workspace-confined file operations (see `BuiltinTools.java`, `Sandbox.java`), not arbitrary
code execution. Adding a real REPL is a large new security surface that runs directly against imini's
education-grade, no-syscall design. (What a *genuine* sandbox would involve is covered in
[`WHATS_NOT_INCLUDED.md`](WHATS_NOT_INCLUDED.md).)

### 3b. The base model is small

RLM requires the root model to author correct slicing/decomposition/orchestration code and to choose
map-reduce strategies on the fly. The paradigm's wins come from frontier models or a model post-trained for
the loop. A vanilla ~3B model will not reliably synthesize that control flow, so the most valuable part of
RLM would be the least reliable part on imini's target model.

### 3c. It is heavier than imini's actual goal

The practical problem imini needs to solve is *staying under the per-call token cap without crashing*. RLM
is a quality-at-massive-scale paradigm that trades one call for many recursive calls. Using the full
framework just to fit a token budget is over-engineering, and imini already has lighter, deterministic
machinery aimed squarely at overflow (below).

### What imini does instead

When context approaches the limit, imini uses four mechanisms — and one of them is already LLM-based
folding:

| Mechanism | File | What it does | Nature |
|---|---|---|---|
| Accurate token counting | `ContextManager.java` | Asks `llama-server`'s `/tokenize` for the real count (falls back to chars/4) | exact measurement |
| Tool-result condensing | `ContextManager.condenseToolResult` | Shrinks an oversized tool output to head + tail before it enters history | lossy by **deletion** |
| LM compaction | `ContextManager.compactIfNeeded` | At a threshold, rolls older turns into a durable `[MEMORY]` note via a cheap summary model (`llama.summaryChat`); the code logs this as "folded" | lossy by **summarization** |
| Hard budget gate | `TokenBudget.fit` (in `LlamaClient`) | Truncates oversized messages, then drops oldest middle messages, then truncates the latest/system message so the call always fits | lossy by **deletion** |
| Sub-agent isolation | `SubAgent.java` | The model can delegate research to a second loop whose noisy context is discarded; only the clean summary returns | sub-call isolation |

In other words, imini already does LLM-based "folding" of conversation history into durable memory, and it
already has the isolated sub-LM-call primitive that RLM relies on — it just does not run model-written code
over a prompt-as-variable.

## 4. What imini already shares with the RLM philosophy

The *philosophy* of RLM — do not stuff the long thing into the prompt; give the model a handle and let it
pull what it needs — is already half-present in imini:

- **Context as environment.** `@file` / `@directory` references, `RetrievalService` (search indexed
  snippets), and the memory-search features fetch content on demand instead of dumping everything into one
  call.
- **Sub-call isolation.** `SubAgent` is exactly the RLM idea of a sub-LM whose intermediate context never
  pollutes the parent window; only its result returns.
- **LLM folding.** `ContextManager` compaction summarizes older history with a model into a durable note —
  the conversation-history version of RLM's "reduce."

The piece imini lacks is RLM's programmatic, model-driven navigation of a single oversized input.

## 5. When RLM — or an RLM-inspired fold — would be appropriate

Use this as a decision guide.

**A full, faithful RLM is appropriate when all of these hold:**

- inputs routinely exceed the model's window by a large factor (e.g. whole repositories, multi-megabyte
  logs, long document collections);
- the task needs *global* reasoning over that input (cross-references, timelines, "find the one fact buried
  anywhere"), so naive truncation would discard the relevant part;
- you have a **capable model** (frontier-class, or one trained/tuned to drive the loop) that can write
  correct decomposition code; and
- you can afford a **real sandboxed code-execution environment** with the isolation and resource limits it
  demands.

**A full RLM is *not* appropriate when:** the model is small, you have no sandbox, latency/cost per task
matters more than maximal long-context quality, or the real need is simply "stay under the token cap." That
is imini's situation today.

**A lighter, RLM-inspired bounded fold is appropriate** when you have oversized *single inputs* but the
constraints above are not met — i.e. imini's realistic case. Rather than a REPL of model-written code, this
is structured (host-language) orchestration that, when one input exceeds the budget, **chunks it →
sub-summarizes each chunk with the cheap model → reduces → recurses if the digest is still too big.** It
captures RLM's "read everything via sub-calls" benefit without a sandbox and without depending on the model
to write code.

### How the fold would differ from imini's current behavior

> **Now implemented.** As of the context-fold change, this bounded fold ships in imini:
> `ContextManager.condenseToolResult` folds a single tool result larger than `agent.fold-threshold-chars`
> by chunking it, summarizing each chunk with the cheap summary model, and recursing until the digest fits
> (`agent.fold-*` settings; `agent.fold-enabled=false` restores the prior head+tail-only behavior). It
> degrades to head+tail if the summary model is unavailable. It applies to large tool results (incl. retrieval) and to oversized `@file`/`@directory` references, each fold is counted via the `context_fold` metric, and each fold emits a `[fold:<label>]` trace event visible in the web UI run trace. The rest of this section describes the design.

The narrow, concrete delta over what the code does today:

- **Coverage of a single oversized input.** Today an over-budget single item (a huge `@file` or a tool
  result still large after head+tail condensing) is **truncated** — the middle is cut and never seen. A
  fold would **chunk and sub-summarize every region**, so all of it is read at least once. Truncation loses
  *coverage*; folding loses *resolution*.
- **Recursion.** `compactIfNeeded` is essentially one pass and `TokenBudget.fit` is single-pass deletion;
  neither folds its own output. An RLM-style reduce recurses until the result fits.
- **Automatic, not model-invoked.** `SubAgent` isolation only fires when the model picks the research tool.
  A fold would apply the same isolation automatically whenever any single input exceeds the cap.

Honest caveat: the fold is still lossy (by compression) and a ~3B summarizes imperfectly. Its value shows
up specifically when a single input vastly exceeds the window **and** the task depends on content that
truncation would drop. For everyday local use, imini's existing condense + compaction + fit already keep
sessions coherent, so the fold is a margin improvement, not a foundational one.

---

## References

- Recursive Language Models (paper): <https://arxiv.org/abs/2512.24601>
- Reference implementation: <https://github.com/alexzhang13/rlm>
- imini context-handling code: `ContextManager.java`, `TokenBudget.java`, `TokenBudgetService.java`,
  `LlamaClient.java`, `SubAgent.java`
- Broader list of things imini omits on purpose: [`WHATS_NOT_INCLUDED.md`](WHATS_NOT_INCLUDED.md)
