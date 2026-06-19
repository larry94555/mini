## Eval harness + OpenTelemetry-style tracing + per-tenant cost accounting & quotas

Three additions that move imini from "reliable" toward "measurable and multi-tenant-aware": a way to score
agent quality, distributed tracing for runs, and per-tenant token/cost metering with quotas.

### Features
- **Eval harness.** `EvalHarness` runs a fixed suite of `Case`s (prompt + expectation) through the live
  agent and scores each answer, so prompt/model/refactor changes can be checked for *quality* regressions,
  not just that the build is green. Scoring (`CONTAINS` / `REGEX` / `EQUALS_NORMALIZED`) is pure and fully
  unit-tested; the runner self-skips (`{skipped:true}`) when the model is unreachable, mirroring the
  integration tests. Exposed at `POST /admin/eval` (admin). Ships a tiny default suite; supply your own for
  a real domain.
- **Distributed tracing (OpenTelemetry-compatible, dependency-free).** New `Tracer` emits spans following
  the OTel data model — 128-bit `trace_id`, 64-bit `span_id`, `parent_id`, name, timing, attributes,
  status — with W3C `traceparent` for propagation. Spans nest within a run via a per-thread stack, live in
  a bounded in-memory ring, and (when `tracing.persist`) append to the `trace_spans` table. Viewable at
  `GET /admin/traces`. Off by default (`tracing.enabled=false`) with zero overhead; deliberately no OTel
  SDK dependency (the classpath is intentionally tiny, and an explicit tracer is more legible for a teaching
  harness). Wired around `/ask`.
- **Per-tenant cost accounting & quotas.** New `CostService` records each run's input/output tokens against
  the calling tenant in a `cost_ledger`, derives an integer micro-USD cost from
  `cost.input-usd-per-million` / `cost.output-usd-per-million` (0 for a free local model), and enforces a
  soft `cost.monthly-token-quota` (a tenant over quota gets HTTP 429 on `/ask`). Per-tenant monthly usage is
  at `GET /admin/cost`.

### New files
- `src/main/java/com/example/imini/EvalHarness.java`
- `src/main/java/com/example/imini/Tracer.java`
- `src/main/java/com/example/imini/CostService.java`
- `src/test/java/com/example/imini/EvalHarnessTest.java`
- `src/test/java/com/example/imini/TracerTest.java`
- `src/test/java/com/example/imini/CostServiceTest.java`

### Changed files
- `Database.java` (migrations: `cost_ledger` + index, `trace_spans` + index).
- `AgentController.java` (inject the three services; quota check + trace span + cost record around `/ask`;
  new `GET /admin/traces`, `GET /admin/cost`, `POST /admin/eval`).
- `src/main/resources/application.properties` (tracing / cost / eval config).
- `README.md`, `ROADMAP.md`, `TESTING.md` (cases 389-395).

### Behavior change
- `/ask` now: (1) rejects with HTTP 429 when the caller is over the monthly token quota (only if
  `cost.monthly-token-quota > 0`), (2) opens a trace span (only if `tracing.enabled`), and (3) records token
  usage to the cost ledger (if `cost.enabled`, default true; cost is 0 at default prices). With defaults
  (quota 0, tracing off, prices 0) behavior is unchanged except that a zero-cost ledger row is written per
  run when persistence is on.
- Two new schema migrations add `cost_ledger` and `trace_spans` (applied automatically on startup).

### Honest scope
Token counts use the existing approximations: input via the model's `/tokenize` when reachable, output via
the ~4-chars/token heuristic (the harness has no exact output-token feed from the streaming path). Cost is
therefore as accurate as those counts and the configured prices — fine for budgeting and relative
comparison, not billing-grade. The tracer is a single-process tracer: it models the OTel span shape and
emits `traceparent`, but does not ship an OTLP exporter or cross-service propagation receiver (it stores
spans locally for `/admin/traces`); pointing it at a real collector would be a follow-up. The eval runner
requires a live model, so its suite-level test self-skips offline; only the pure scoring is verified in CI.
Quotas are enforced on `/ask`; other run entry points (`/chat`, streaming) are not yet gated.

### Testing
`./mvnw -Dtest=EvalHarnessTest,TracerTest,CostServiceTest test`; full `./mvnw test`. Manual checks for the
live-model and tracing paths per TESTING.md cases 392, 394, 395. Cases 389-395 in TESTING.md.
