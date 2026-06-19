## OTLP export + trace propagation, CI eval gate, and tiered quotas on all run endpoints

Builds out the three observability/multi-tenancy features just landed: turn the single-process tracer into
real distributed tracing, make the eval harness an automatic CI quality gate, and extend quotas to every
run endpoint with per-tenant tiers.

### Features
- **OTLP export + cross-service trace propagation.** `Tracer` now reads an inbound W3C `traceparent`
  header (captured in `AuthFilter`, exposed via `RequestContext`) and *continues* the caller's trace —
  the run's root span shares the caller's `traceId` and points at their span as parent. It can also
  **export** each finished span to an OTLP/HTTP collector as OTLP/JSON when `tracing.otlp-endpoint` is set
  (e.g. `http://localhost:4318/v1/traces`), using only the JDK HTTP client — no OpenTelemetry SDK
  dependency. Export is best-effort and runs on a daemon thread, so a slow or down collector never blocks
  or breaks a request. All four run endpoints (`/ask`, `/chat`, `/ask/stream`, `/chat/stream`) now open a
  span via `startWithContext`.
- **CI eval gate.** New opt-in workflow `.github/workflows/eval-gate.yml` boots a tiny GGUF + llama-server
  + imini, runs the in-process suite via `POST /admin/eval`, and **fails the build when the pass-rate is
  below a threshold** (`min_pass_rate`, default 0.75) or when the suite self-skips (no model). It is
  deliberately *not* part of the default unit-test run: it needs a model and CPU inference, so it triggers
  on manual `workflow_dispatch` or when a PR carries the `run-eval-gate` label.
- **Tiered quotas on all run endpoints.** The monthly token quota is now enforced on `/chat`,
  `/ask/stream`, and `/chat/stream` in addition to `/ask` (HTTP 429 once over quota; streaming endpoints
  reject before the stream opens). Quotas can be **tiered**: `cost.tiers` defines named quotas
  (`free=100000,pro=5000000`) and `cost.tier-assignments` maps tenants to tiers (`alice=pro,bob=free`); an
  unassigned tenant uses the default `cost.monthly-token-quota`. Resolution is a pure, tested function.

### New files
- `.github/workflows/eval-gate.yml`
- `src/test/java/com/example/imini/TracePropagationTest.java`
- `src/test/java/com/example/imini/TieredQuotaTest.java`

### Changed files
- `Tracer.java` (inbound `traceparent` parsing + `startWithContext`; OTLP/JSON serializer `otlpJson`;
  best-effort `exportOtlp`; `tracing.otlp-endpoint` / `tracing.service-name` config).
- `RequestContext.java` (carry the inbound `traceparent` for the request).
- `AuthFilter.java` (capture the inbound `traceparent` header).
- `CostService.java` (tier parsing + `resolveQuota` + `quotaFor`/`tierOf`; quota now resolved per tenant;
  tiers surfaced in `summary()`).
- `AgentController.java` (quota check + span + cost record on `/chat`, `/ask/stream`, `/chat/stream`;
  `/ask` now seeds its span from the inbound context).
- `src/main/resources/application.properties` (OTLP + tier config).
- `README.md`, `ROADMAP.md`, `TESTING.md` (cases 396-402).

### Behavior change
- All four run endpoints now enforce the (tiered) monthly quota; previously only `/ask` did. With the
  default quota of 0 (unlimited) and no tiers configured, behavior is unchanged.
- When `tracing.enabled=true`, runs continue an inbound `traceparent` instead of always starting a new
  trace. With tracing off (default) there is no change.
- OTLP export only happens when `tracing.otlp-endpoint` is non-blank; otherwise it is a no-op.
- No new schema migrations (reuses `trace_spans` and `cost_ledger`).

### Honest scope
The OTLP export path POSTs to a collector, which cannot be exercised in this repo's offline unit tests —
so the **span serialization** (`otlpJson`) and **header parsing** (`parseTraceparent`) are unit-tested
purely, while the network POST is best-effort at runtime and self-disables when no endpoint is set. The
exporter sends one span per request (no batching) and supports the OTLP/HTTP JSON encoding only (not
protobuf/gRPC); a high-volume deployment would want a batching exporter. Propagation reads `traceparent`
but not `tracestate` (vendor data), which imini does not produce. The CI eval gate depends on a tiny model
and on the upstream llama.cpp release asset name; if that asset name changes, the download step needs a
pinned tag. Token counts feeding cost/quota remain the existing approximations (input via `/tokenize`
when reachable, output via ~4 chars/token).

### Testing
`./mvnw -Dtest=TracePropagationTest,TieredQuotaTest,TracerTest,CostServiceTest,EvalHarnessTest test`;
full `./mvnw test`. Live/manual checks for OTLP export, propagation, the CI gate, and per-endpoint quota
enforcement per TESTING.md cases 397, 399-401. Cases 396-402 in TESTING.md.
