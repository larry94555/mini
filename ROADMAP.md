# From education-grade to production-grade (low-end / free-model target)

imini is intentionally a teaching kit: single user, single box, llama.cpp models, clarity over
robustness. This is the plan to harden it into something you could actually run for a small team or
ship as a free/low-cost product, while keeping the "runs on a CPU with free GGUF models" spirit.
Roughly ordered by leverage.

## 1. Model serving & performance (the low-end bottleneck)
- **Pick the model deliberately.** A 3B is the floor; tool-calling reliability jumps a lot at 7B-8B
  (e.g. Qwen2.5-7B-Instruct, Llama-3.1-8B). Offer a small/medium/large profile.
- **Use GPU offload when present** (`-ngl`), and enable continuous batching / a slots pool in
  llama-server so multiple users share one process. Set `--parallel` and a sane `--ctx-size` per slot.
- **Quantization tradeoffs:** ship Q4_K_M as default, allow Q5/Q6 for quality, Q3 for tiny boxes.
- **Speculative decoding** (a tiny draft model) and **KV-cache reuse/prefix caching** for latency.
- **Health/restart supervision** of llama-server (auto-restart, readiness, version pinning).

## 2. Correctness & reliability of the agent loop
- **Constrained/grammar-based tool calling.** Use llama.cpp GBNF grammars or JSON-schema-constrained
  decoding so small models emit valid tool calls every time, instead of relying on the `<tool_call>`
  text fallback.
- **Tool-result and arg validation** against each tool's JSON schema before executing; structured
  errors the model can recover from.
- **Retries with backoff** for transient model/tool/network failures; idempotency keys for actions.
- **Real per-call timeouts** for MCP and shell tools on separate threads (today a hung MCP read can
  block); cancellation that actually interrupts blocked I/O.
- **Deterministic tests + evals.** A harness-level test suite and a small eval set (does it pick the
  right tool? stay in the workspace? recover from errors?) run in CI.

## 3. Concurrency & multi-user
- **Per-session everything.** Today interrupt/steer, todos, and permissions are global; key them by
  session/user. Make the engine fully stateless per request, state in stores.
- **Async, non-blocking requests.** Replace the blocking controller with streaming responses (SSE/
  WebSocket) to the client so users see tokens and can interrupt from the UI, not a second terminal.
- **A real job model:** queue long runs, surface progress, allow cancel; bound concurrency to the
  model's slot count with a fair scheduler.

## 4. Security & sandboxing (the scariest gap)
- **Sandbox `run_command` and file tools.** Run them in a container/jail (seccomp, read-only FS
  except a scratch dir, no network) instead of trusting allow/deny strings. This is the single
  biggest production blocker.
- **Stronger workspace isolation:** canonical-path checks, symlink escape prevention, per-session
  workdirs.
- **Harden prompt-injection handling** beyond fencing: capability scoping (a "read-only" run can't
  call mutating tools), provenance tracking, and human-in-the-loop for high-risk actions.
- **Secrets management** for MCP servers and any API keys; never log them.
- **AuthN/AuthZ** on the HTTP API; rate limiting; input size limits; CORS.

## 5. Context & memory at scale
- **Vector store / retrieval** for project memory and long histories instead of a single summary
  note; embed files and recall on demand (RAG).
- **Tiered, accurate compaction** with a token budgeter per model; cache tokenization.
- **Durable, queryable session storage** (SQLite/Postgres) instead of JSON files; migrations.

## 6. Persistence & data
- Move `.imini/` JSON to a real database; transactional checkpoints; retention/cleanup policies.
- Versioned, content-addressed checkpoints with a true diff/restore history (not just last-N).

## 7. Observability & ops
- **Structured logging, metrics, tracing** (tokens, latency, tool counts, costs/throughput per run).
- **Cost/usage accounting** even for "free" local models (GPU-seconds, queue time).
- Config via env/profiles; secrets out of files; reproducible builds; Docker image; one-command deploy.

## 8. Product surface
- A web UI (streaming, diffs for edits, approve/deny buttons, plan review, todo board) -- this is a
  large fraction of what makes Claude Code usable, and is mostly harness, not model.
- Packaging: an installer that bundles llama-server, downloads a model, and launches the app.

## Suggested order
1) GPU/batching + a 7B profile (biggest quality/throughput win), 2) grammar-constrained tool calls
+ schema validation (reliability), 3) sandboxing run_command (safety), 4) async streaming API +
per-session state (multi-user), 5) DB-backed persistence + retrieval memory, 6) auth + observability,
7) web UI. Each step is independently shippable.
