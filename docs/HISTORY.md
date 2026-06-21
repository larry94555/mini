# Project history (completed work)

This file is the long-form record of completed work, moved out of `ROADMAP.md` to keep the roadmap
focused on what to build *next*. Newest first. Going forward, released changes are also tracked in
`CHANGELOG.md` (release-please / Conventional Commits); this file preserves the full pre-automation
history and the running prose summaries.

## Completed

- Recovery golden traces + shared scripted-agent fixture + node in CI: `RecoveryTraceTest` drives the real `AgentEngine` through its non-happy-path branches — a mutation denied in PLAN mode (`RECORD_PLAN`, nothing executed), an invalid-args call that becomes corrective feedback then a successful retry, and a repeated identical mutating call that trips the duplicate-call guard (execution capped, run stopped) — asserting the permission decision, the validation/guard messages, and the final answer for each; a shared `ScriptedAgent` test fixture (scripted `LlamaClient` + real-engine `buildEngine` + decision-recording permissions) now backs `GoldenTraceWorkflowTest`, `RecoveryTraceTest`, and `FakeModelHarnessTest` (the last upgraded to drive the real engine), removing the parallel harness; and `ci.yml` installs Node so the stdio MCP integration tests run in CI instead of self-skipping.
- Golden-trace workflow test + streaming SSE MCP + learning-path/workshop modules: `GoldenTraceWorkflowTest` drives the real `AgentEngine` loop with a scripted (model-free) `LlamaClient` through edit→stage→commit, asserting tool dispatch, the permission decision, hook firing, and the git-verified edit-trust summary in one trace (plus an MCP-prompt-slash-command trace); the HTTP MCP transport now consumes a terminating multi-event `text/event-stream`, skipping interim progress events to pick the JSON-RPC response (`McpManager.jsonFromHttpBody`), covered by a streaming-SSE integration test + a pure selector test; and `docs/WORKFLOW_WALKTHROUGH.md` is wired into `docs/LEARNING_PATH.md` (Module 13.5) and `docs/WORKSHOP.md` (Lab 6) with the new tests as checkpoints.
- Live MCP integration test + git-commit approval-flow test + workflow walkthrough doc: `McpLiveIntegrationTest` connects `McpManager` to a stub server over both transports (a node child process over stdio + a JDK `HttpServer` over HTTP) and asserts tools/resources/prompts discovery plus `read_resource` and the `/mcp__server__prompt` slash command returning rendered content (stdio half self-skips without node); `GitCommitApprovalFlowTest` drives a real repo through stage → approval → commit, asserting the staged diff is attached to the approval payload; and `docs/WORKFLOW_WALKTHROUGH.md` documents the edit→verify→commit loop, the six-event hook lifecycle, and the MCP lifecycle with mermaid diagrams. A small package-private `McpManager.connect()` test seam was added.
- MCP prompts as slash commands + SessionStart/Notification hooks + git_push & approval-diff: discovered MCP prompts are now invokable as `/mcp__<server>__<name>` slash commands (listed in `/help`, `key=value` args parsed, rendered prompt becomes the turn input); `HookService` gains `sessionStart` (first-turn context injection) and `notification` (fires when the agent requests approval) events; and a capability-gated, off-by-default `git_push` tool (`git.allow-push`) plus the staged diff (`git diff --cached --stat`) surfaced in the commit approval prompt complete the git workflow.
- Git write workflow + hook breadth + MCP resources/prompts/HTTP transport: new mutating `git_stage`/`git_commit`/`git_branch` tools (approval-gated, message via the `commit-message` skill) complete the edit→verify→commit loop; `HookService` gains `userPromptSubmit` (block-or-inject) and `stop` (append) turn-level events alongside the existing tool hooks; and the MCP client discovers `resources/list`+`resources/read` (a `<server>_read_resource` tool) and `prompts/list`+`prompts/get` (per-prompt tools) and can reach servers over an HTTP transport (`mcp.json` `transport:"http"`, plain-JSON or single-event SSE) as well as stdio.


- Live posture row + structured-payload toggle + posture Prometheus gauges: the overview current-posture row is rebuilt from overview.json on each poll (no longer stale until reload); alerts.slo-digest-structured (default true) lets receivers opt out of the digest object in the webhook payload; and the snapshot posture is exported as imini_alerts_digest_window_ratio/_delivery_ratio/_worst_route_ratio/_worst_success_route_ratio gauges.

- Overview posture row + structured webhook payload + report format choice: the digest section opens with a compact current-posture row (window/delivery vs targets, worst routes, mute/catch-up) from the live sloDigest() snapshot (now also in stats()); the scheduled webhook digest payload includes the snapshot as a structured digest object alongside the back-compat text field; and the report link/download lets the reviewer pick JSON or CSV.

- Report bundle snapshot + download link + picker validation feedback: the digest-report bundle now includes the latest sloDigest() snapshot (JSON snapshot field + a # snapshot CSV section); the overview gains a Download report bundle link (digest-report CSV for the current range); and the date-picker surfaces the server-side range validation error inline (without pinning the view) instead of failing silently.

- Combined digest report bundle + copy-link + range validation: GET /admin/alerts/digest-report returns mute state + history + audit for a date range (JSON or one sectioned CSV); the overview gains a Copy report link button that copies the bundle URL for the current range; and the history/audit/report endpoints reject malformed or inverted date ranges with HTTP 400 + a clear message (pure rangeError).

- Digest history CSV + quick-range buttons + CSV download links: GET /admin/alerts/slo-digest/history gains ?format=csv (parity with digest-audit); the overview date-picker adds 24h/7d/30d quick-range buttons that set the window and apply; and History/Audit CSV download links export the current from/to range.

- Marker-faithful live trends + overview date-picker + window-ratio target line: the delivery-success trend now redraws its mute (square) / catch-up (diamond) markers on every auto-refresh via a JS trendSVG (previously markers were server-render only); the digest section gains a from/to date-picker that fetches the ranged history/audit and pauses the live poll while pinned; and the window-ratio trend overlays the SLO target reference line.

- Full digest trends + date-range filtering + mute/catch-up trend markers: the overview charts three trends across recent digests (delivery-success, window ratio, budget remaining), with the delivery-success trend annotated by mute (square) and catch-up (diamond) markers; the digest history and digest-audit endpoints accept ?from/?to/?days date-range filters (audit still supports ?format=csv).

- Structured digest history + trend chart + digest-audit CSV + catch-up audit: digest history rows are now versioned (v2) and carry structured metrics (window ratio, delivery-success, budget) so the overview charts a delivery-success trend across recent digests; the mute audit trail exports as CSV (GET /admin/alerts/digest-audit?format=csv); and a mute-expiry catch-up send records an alert_digest_catchup audit event. Legacy 4-field history rows still parse.

- Digest mute audit trail UI + reason-required-for-long-mutes + mute-expiry catch-up: the mute/unmute/auto-expire audit events are surfaced via GET /admin/alerts/digest-audit and a "Digest mute audit" section on the overview; a reason is mandatory for mutes beyond alerts.slo-digest-reason-required-hours (default 8, rejected with HTTP 400 otherwise); and a mute auto-expiry flags the next digest as a catch-up ((catch-up after mute) suffix + {catchup} placeholder) summarizing the silenced window.

- SLO digest mute accountability: mute/unmute/auto-expire are now recorded as audit-log events with the acting user (alert_digest_mute/unmute/mute_expired); a mute carries an optional reason note surfaced in the digest ([muted: reason]), a {muted_reason} template placeholder, the overview, and the audit outcome; and a requested duration is clamped to alerts.slo-digest-mute-max-hours (default 72, 0 = no cap).

- SLO digest mute observability + auto-expiry: the digest carries a muted flag (formatted as a [muted] prefix and a {muted} template placeholder), Prometheus exports imini_alerts_digest_muted / imini_alerts_digest_mute_until_seconds with an IminiAlertSloDigestMuted rule + Grafana panel, and a mute window now auto-expires (cleared + logged resumption on the next scheduler tick or post via expireMuteIfDue).

- SLO digest history + mute + bounded alert_meta: every posted/suppressed digest is recorded in a capped history (alert_meta, alerts.slo-digest-history-max, pruned each post) viewable at GET /admin/alerts/slo-digest/history and a Recent-digests section on the overview; scheduled digests can be muted for N hours (POST /admin/alerts/slo-digest/mute, persisted + restored) with a manual ?force override; overview gains Mute/Unmute controls.

- Persisted digest baseline + overview Send-digest button + digest via the delivery pipeline: the since-last-digest deltas baseline is persisted to a new alert_meta table (restored at startup) so deltas survive restarts; the overview page gets a CSRF-guarded "Send SLO digest now" button wired to POST /admin/alerts/slo-digest; and alerts.slo-digest-via-pipeline routes the digest through the normal retry/dead-letter pipeline instead of a one-shot probe.

- Configurable SLO digest + manual trigger + since-last deltas + worst-by-delivery-success: the digest message is templated via alerts.slo-digest-template (placeholders for every digest field incl. deltas); POST /admin/alerts/slo-digest (CSRF) sends one on demand; each digest carries deltas vs the previously posted digest (budget burned, delivery-success change, new dead-letters); and it now names the worst route by both latency SLO and delivery-success ratio.

- Scheduled SLO digest + report date-range + report target columns: AlertSloDigestScheduler (alerts.slo-digest-interval-minutes) periodically POSTs a posture summary (window budget remaining, delivery-success ratio, worst route) to alerts.slo-digest-url or the default webhook; GET /admin/alerts/slo-report gains ?from/?to/?days date-range filtering; and each report row now carries the effective slo_target/success_target and a pass flag for decision-ready monthly exports.

- Durable per-route windows + worst-trend route sort + downloadable SLO report: per-route rolling-window buckets are persisted to alert_slo_route_buckets (flushed/pruned/restored alongside the global window) so per-route trends survive restarts; the overview By-route table is sorted worst-trend-first (most-recent daily ratio ascending) so a degrading receiver surfaces at the top; and GET /admin/alerts/slo-report downloads the rolling-window daily good/total history (global + per route) as CSV (or ?format=json) for offline reporting.

- Richer SLO sparklines: the overview window sparkline is now window-length-aware (labeled with the configured days), draws a dashed target reference line, and carries per-day hover tooltips (today / Nd ago : ratio); per-route daily series (slo_window_series_by_route, in-memory) drive a mini-sparkline trend column in the By-route table; all live-update with the page via a shared JS sparkline builder.

- Bounded SLO bucket table + per-route success burn alerting + overview SLO sparkline: out-of-window alert_slo_buckets rows are pruned on each flush (windowFloorDay horizon) so the table stays bounded; a new IminiAlertRouteSuccessBurning multi-window rule pages when a single route burns its delivery-success budget; and the overview SLO summary now shows a daily success-ratio sparkline (slo_window_series) that live-updates with the page.

- Durable rolling-window buckets + per-route success-target overrides + overview SLO summary: the rolling-window SLO buckets are now persisted to SQLite (alert_slo_buckets, flushed on the reaper tick and at shutdown, restored at startup) so the 30-day window survives restarts; a route in alerts.routes can set a 6th field for its own delivery-success target (action|url|template|latency|target|success-target); and the overview page shows a live SLO summary (latency success ratio, budget remaining, rolling-window budget, delivery-success ratio).

- Rolling-window error-budget tracking + persisted hot-reload + per-route delivery-success SLO: a RollingWindow of daily good/total buckets backs imini_alerts_slo_window_* (budget_remaining over the last alerts.slo-window-days, default 30, rather than since-boot); POST /admin/alerts/reload now persists to alerts.config-override-file and re-applies it at startup so a live fix survives restart; and a delivery success-rate SLO (delivered vs dead-lettered, alerts.success-target) is computed globally and per route (imini_alerts_success_slo_*, imini_alerts_route_success_ratio) so a fast-but-erroring receiver pages.

- Per-route SLO objective overrides + error-budget-remaining + config hot-reload: a route in alerts.routes can carry its own latency/target (action|url|template|latency|target) used by the per-route SLO; the SLO now reports budget_used/budget_remaining (imini_alerts_slo_budget_remaining, per route too) alongside burn rate, with budget-exhausted ops rules; and POST /admin/alerts/reload re-parses actions/routes/tiers/SLO into the live AlertSink (returning the resolved config + warnings) so a misconfiguration is fixable without a restart.

- Objective-driven burn-rate metrics + self-test history/flap detection + per-route SLO: the SLO burn-rate ops rules now reference monotonic imini_alerts_slo_good_total/_total_total counters and the imini_alerts_slo_target gauge (objective lives only in alerts.slo-* config, no hard-coded le bucket to drift); the scheduled self-test keeps a bounded history with pass/fail flap detection (alerts.selftest-flap-threshold, imini_alerts_selftest_flapping, GET /admin/alerts/selftest); and success-ratio/burn is computed per route (imini_alerts_route_slo_*) so one degraded receiver pages even when the global SLO is green.

- Delivery-latency SLO with budget-burn alerting + per-route latency/success breakdown + a scheduled self-test: webhook latency now has an objective (alerts.slo-latency-ms/alerts.slo-target) with observed success ratio and error-budget burn exposed as imini_alerts_slo_* and multi-window burn-rate ops rules; per-route average latency is tracked (imini_alerts_route_latency_avg_ms) so a slow receiver is identifiable; and AlertSelfTestScheduler periodically runs the synthetic self-test (alerts.selftest-interval-minutes, optional live probe) exporting imini_alerts_selftest_ok with an IminiAlertSelfTestFailing rule.

- Startup config validation + a synthetic self-test endpoint + delivery-latency histograms: AlertSink now derives operator warnings from the resolved config (contradictions like a ladder with alerts.enabled=false, dedup-shared/persistent with no DB, unparsed tiers), logged at startup and surfaced in GET /admin/alerts/config; POST /admin/alerts/selftest pushes a synthetic alert through routing/dedup and (?send=true) does a live probe POST reporting status+latency; and webhook round-trip time is recorded as a Prometheus histogram (imini_alerts_delivery_latency_ms) with a new dashboard panel + p95 alert rule.

- ops runbook + dashboards/rules, an effective-config introspection endpoint, and a live auto-refreshing overview: the ops/ bundle gained Grafana panels and Prometheus rules for the newer signals (escalations, SLA breaches, per-tier ack latency, suppression storms) plus a response runbook; GET /admin/alerts/config returns the resolved alerting config (parsed tiers/SLAs, routes, dedup/retention, CSRF mode) with webhook URLs masked; and the overview page now polls GET /admin/alerts/overview.json to live-update its cards and tables (?refresh=<seconds>).

- SLA-breach re-escalation + alerting-overview dashboard + signed/rotating CSRF tokens: escalation tiers take an optional ack-SLA deadline (delay|url|template|sla) and a dead-letter that misses it is re-escalated (or re-paged at the top tier), counted as imini_alerts_sla_breaches; GET /admin/alerts/overview.html is a one-screen operator view (counters, per-route, per-tier + ack-SLA, top suppressed); and CSRF tokens are now HMAC-signed with a TTL (alerts.csrf-secret/alerts.csrf-ttl-seconds) so they validate statelessly and across instances when the secret is shared.

- CSRF guard for the viewer + dedup-digest summary panel + escalation-tier ack-SLA timing: the dead-letter viewer's state-changing actions now require a per-process CSRF token (alerts.admin-csrf; embedded in the page, sent as X-CSRF-Token, fetchable at GET /admin/alerts/csrf); the most-throttled dedup keys are exposed at GET /admin/alerts/digests and a "Top suppressed keys" viewer panel; and the time from escalation to ack is aggregated per tier (ack_sla_by_tier) and exported as imini_alerts_ack_latency_avg_ms/_max_ms{tier}.

- Escalation tier/ack visibility, per-tier metrics, and bulk dead-letter actions: each dead-letter now surfaces escalation_tier/escalated_at/acked_at in the JSON and the HTML viewer (a tier column + acked badge); escalations are counted per ladder tier and exported as imini_alerts_escalated_tier{tier}; and POST /admin/alerts/ack-all and /admin/alerts/replay-all bulk-act on every failed dead-letter matching the current action/status/q filter (with one-click buttons in the viewer) so a backlog can be cleared after an outage.

- Multi-tier escalation ladder + atomic claiming, an HTML dead-letter viewer, and dedup digests: alerts.escalate-tiers walks an un-acked dead-letter up an ordered delay|url ladder (atomic escalation_tier claim so concurrent reapers page each tier exactly once; legacy single-tier keys still work); GET /admin/alerts.html is a filterable, paginated dead-letter viewer with inline ack/replay/delete; and the reaper emits one dedup digest per key (alerts.dedup-digest, imini_alerts_digested) when a dedup window elapses with suppressions, so suppressed storms stay visible without flooding.

- Cluster-wide alert dedup + escalation on unacked + searchable dead-letter backlog: dedup windows now live in a shared alert_dedup SQLite table (alerts.dedup-shared) so throttling holds across instances, with a per-route suppressed metric (imini_alerts_route_suppressed{route}); un-acknowledged dead-letters older than alerts.escalate-after-minutes are re-paged once to alerts.escalate-url by the reaper (POST /admin/alerts/ack to silence, /admin/alerts/escalate to force, imini_alerts_escalated counter); and GET /admin/alerts/failed gained action/status/q/offset/limit search + total for navigating a large backlog.

- Dead-letter retention/aging, per-route Prometheus counters, and alert dedup/throttling: a background reaper (AlertDeadLetterReaper) ages out failed dead-letters older than alerts.dead-letter-retention-hours (plus an on-demand DELETE /admin/alerts/failed purge) so the durable store stays bounded; per-route delivery counters are tracked by action and exported as imini_alerts_route_sent/failed/dead_lettered{route} (replays re-attribute via a stored action column); and alerts.dedup-window-seconds collapses repeated identical (action+target) alerts within a window into one notification (imini_alerts_suppressed), so a denial storm cannot flood the on-call channel.

- Crash-safe alert replay + retry history, template validation/dry-run, and per-action routing: replaying a dead-letter now marks the row replaying and only deletes it on a confirmed 2xx (restoring it with updated attempts/last_error on repeat failure; stuck rows reset at startup), so alerts are never lost mid-replay; POST /admin/alerts/test renders a template against a sample event and reports validation issues (unknown placeholders, unbalanced braces/quotes), optionally sending one; and alerts.routes maps actions to their own webhook/template so e.g. spend_alert and capability_denied page different channels.

- Durable dead-lettering + replay, structured alert templates, and a Grafana/Prometheus ops bundle: dead-lettered webhook alerts are now persisted in the alerts_dead_letter SQLite table (surviving restarts, in-memory ring fallback) and replayable via POST /admin/alerts/replay; operators can shape the webhook payload with an alerts.template ({ts}/{time}/{user}/{action}/{target}/{outcome} placeholders, JSON-escaped) for Slack/PagerDuty/etc.; and ops/ ships a ready-made Grafana dashboard plus Prometheus alert rules over the imini_* metrics (security rates, alert-delivery pipeline, SLOs).

- Alert delivery buffer (retry + dead-letter) + Prometheus security/alert metrics + rate-limit retry-after hints: webhook alerts are now delivered through a buffered, retrying sender (exponential backoff, bounded dead-letter ring at GET /admin/alerts/failed, newest-dropped overflow); audit actions (capability_denied/spend_alert/tool_rate_limited) and alert delivery counters (sent/failed/retried/dead_lettered/dropped) are exported via the existing /metrics/prom endpoint (AuditMetrics listener + PromFormat); and a throttled tool now returns a retry-after hint (estimated seconds until its window frees) in the RATE_LIMITED message.

- Persisted tool rate limits + alert notification sink + audit viewer time-range & pagination: per-tool rate-limit window state is now persisted in the shared rate_limits SQLite table (tool:-prefixed keys), surviving restarts and shared across instances (tool-rate-limit.persistent, default on, in-memory fallback); a new AlertSink forwards selected audit events (capability_denied/spend_alert/tool_rate_limited by default) to a WARN log and an optional alerts.webhook-url as JSON, best-effort on a background thread; and the audit viewer (GET /admin/audit.html) gained since/until time-range filtering (ISO/date/epoch) and offset/limit pagination with Prev/Next that preserve filters.

- Audit-log viewer + configurable redaction patterns + per-tool rate limiting: a read-only HTML audit viewer at GET /admin/audit.html (filterable by user/action/target, highlighting capability_denied / spend_alert / tool_rate_limited) surfaces the durable security events; operators can add custom redaction regexes via redaction.patterns (applied after the built-ins, everywhere redaction runs); and per-tenant per-tool rate limits (tool-rate-limit.limits, e.g. web_fetch=10/60) throttle expensive tools using the sliding-window estimator, denying with RATE_LIMITED and an audited tool_rate_limited event.

- JSON-profile log redaction + audited capability denials & spend alerts + sub-agent/MCP capability scoping: a RedactingJsonEncoder scrubs secrets/PII from the output of the built-in JsonEncoder in the structured (json) logging profile (matching the console %rmsg converter); capability denials (capability_denied) and spend alerts (spend_alert) are now written to the audit log; capability scoping gained prefix matching (github_* covers a whole MCP server) and is propagated into delegated sub-agent runs via an effective-role thread-local, so a sub-agent cannot reach tools the caller is not allowed to use.

- Capability scoping + secret/PII redaction + spend alerts & usage dashboard: a CapabilityService restricts which tools each role may invoke (enforced in AgentEngine before execution; GET /admin/capabilities); Redact.scrubPii masks bearer tokens, key=value secrets, sk-/AWS/JWT tokens and emails in trace attributes (redaction.enabled) and in console logs (a %rmsg logback converter); CostService gained one-time per-tenant spend alerts (lower of an absolute or percent-of-quota threshold) surfaced in GET /admin/cost and a new HTML GET /admin/usage dashboard.

- OTLP export + trace propagation, CI eval gate, and tiered quotas on all run endpoints: the Tracer now continues an inbound W3C traceparent (cross-service propagation) and can export finished spans to an OTLP/HTTP collector as OTLP/JSON (tracing.otlp-endpoint, dependency-free, off-thread); an opt-in GitHub Actions workflow (eval-gate.yml) boots a tiny model and fails the build when the eval pass-rate is below a threshold; the monthly token quota is now enforced on /ask, /chat, and both streaming endpoints, with named tiers (cost.tiers + cost.tier-assignments) resolving a per-tenant quota.

- Eval harness + distributed tracing + per-tenant cost/quotas: POST /admin/eval runs a fixed suite through the live agent and reports a pass-rate (pure, tested scoring; self-skips offline); a dependency-free OpenTelemetry-style Tracer emits W3C spans for runs (GET /admin/traces, tracing.enabled); a CostService meters per-tenant tokens into a cost_ledger with micro-USD pricing (GET /admin/cost) and enforces a soft monthly token quota (HTTP 429 on /ask when exceeded).

- Cascade session prune + sliding-window rate limiting + scheduled rate-limit pruning: pruneExpired now deletes every session_id-keyed child table (checkpoints, plans, plan steps/history, skill state, settings, scheduled tasks) and a periodic orphan sweep removes child rows whose session is gone; the rate limiter gained a selectable sliding-window algorithm (auth.rate-limit-algorithm=sliding) that fixes the boundary burst; a RateLimitReaper periodically calls pruneStale so the rate_limits table stays bounded.

- Session expiry + streaming resilience + persistent rate limiting: a SessionReaper prunes sessions idle past
  agent.session-ttl-hours (with /sessions/summary + /sessions/prune); the streaming chat path now retries the
  connection step under the circuit breaker; the rate limiter persists its windows in SQLite so limits
  survive a restart.
- Circuit breaker + sandbox hardening + graceful shutdown: a three-state circuit breaker (CLOSED/OPEN/
  HALF_OPEN) wraps all llama-server calls so sustained outages fail fast after the threshold; the
  run_command sandbox now uses the workspace root as working directory and caps output at
  sandbox.max-output-bytes; RunService drains in-flight runs on SIGTERM before shutting down. ConfigValidator
  test CI failure fixed (parameter order).
- Reliability + hardening + per-run trace: transient llama failures now retry with exponential backoff +
  jitter (pure, tested `Retry.delayMs`). A startup `ConfigValidator` fails fast on contradictory config and
  warns on risky settings; a `Redact` helper masks secrets in logs. The admin recent-runs view became a
  per-run trace timeline with typed event chips.
- Durable SLO + end-to-end correlation + observability alerts: `GET /admin/slo?window=24h|7d|30m|all`
  computes success rate + p50/p95 from the persisted run_history (survives restart), via a pure, tested
  `RunHistoryStore.windowStatsFrom`. MDC correlation now extends from the request filter into the agent loop
  (`runId`/`session`) and scheduled tasks (`runKind`/`taskId`/`session`). The shipped `docs/observability/`
  alert rules + Grafana dashboard gained success-rate and p95 panels/alerts using the SLO gauges.
- Full-history NDJSON + JSON-log correlation + SLO panel: `GET /admin/runs/history.ndjson?since=&limit=`
  streams the entire persisted run_history (paginated), beyond the in-memory tail. The `json` Spring profile
  now also stamps MDC correlation fields (`reqId`/`path`/`user`) onto structured logs. `Metrics` computes
  p50/p95 latency + success rate, surfaced in the admin SLO line and as Prometheus gauges
  (`imini_run_latency_p50_ms`/`_p95_ms`/`imini_run_success_rate`).
- Operationalized readiness + degradation tests + trace export: the Docker image and compose service now
  wire a `HEALTHCHECK` to `/healthz`, the admin card shows a green/amber/red health dot, and `docs/DEPLOY.md`
  documents Docker/k8s probes. `GracefulDegradationTest` covers the DB-down / llama-unreachable paths
  (stores no-op, readiness reports degraded/down). New `GET /admin/runs.ndjson` exports the per-run trace as
  newline-delimited JSON for external tooling.
- Persistence coverage + readiness probe: a real-SQLite `PersistenceRoundTripTest` round-trips sessions
  (history/ownership/sharing), run history (context counts + event timeline), and plans; a
  `WorkspaceBundleRoundTripTest` round-trips a signed bundle (settings + durable memory) through real HMAC
  signing/verification. New `GET /healthz` readiness probe (db + llama reachability + context/memory
  snapshot) and `/admin/overview` now surfaces context totals + durable-memory state with the run timeline.
- Memory consolidation pass: a real-SQLite integration test (`MemoryStorePersistenceTest`) covers the
  durable-memory path end to end (note/pins/provenance, relevance seeding, analytics, hygiene prune); the
  long-lingering redundant `ContextFoldConfigIT.java` is finally deleted; the embed cache is now a bounded
  LRU (+`embed_cache` table pruned to `retrieval.embed-cache-max`); and the memory pipeline is summarized in
  the UI and documented in `docs/MEMORY.md`.
- Memory hygiene/decay + two-stage recall + embedding cache: durable facts unused (never injected/recalled)
  for `agent.memory-decay-days` are auto-pruned after a run (and via `POST /memory/hygiene`), pins never
  touched; promote-to-pin candidates are ordered by usage. `recall_memory` is now two-stage -- cheap-rank a
  shortlist, then optionally have the summary model pick/order the most relevant (`agent.memory-rerank`).
  Embedding-mode ranking caches each fact's vector (in-process + `embed_cache` table) to avoid re-embedding.
- Embedding-based memory ranking + recall_memory tool + memory analytics: durable-fact ranking now reuses a
  shared `RetrievalService.rankTexts` that scores by embedding cosine when `retrieval.embeddings=true` (else
  lexical, with fallback), used by both session seeding and a new `recall_memory` tool the agent can call
  mid-conversation. A `memory_stats` table records per-fact injected/recalled counts, surfaced in the
  Project memory card and at `/memory/analytics` to find prune candidates.
- Relevance-ranked memory injection + provenance + bundle export/import: a new session is seeded with the
  most relevant durable facts (pins always; top auto facts by `RetrievalService` lexical score to the first
  message, capped at `agent.memory-inject-max`) instead of the whole note. Pins moved to a `memory_pins`
  table carrying provenance (source + timestamp), shown on the chips. Durable memory (note + pins) now rides
  in the signed workspace bundle, so Export/Import workspace carries curated memory between machines.
- Promote-to-pin + per-workspace memory + quality guard: the Project memory card now suggests unpinned
  auto-note facts as one-click "promote to pin" candidates. Durable memory is scoped per workspace + owner
  (keyed by a hash of the working directory via `MemoryStore.workspaceId()`), so different projects keep
  separate notes. A quality guard (`agent.memory-max-chars`) consolidates an oversized auto note via the
  summary model (`ContextManager.consolidateMemoryIfNeeded`) before storing, with a head+tail fallback.
- Editable durable memory + persisted run timeline + preflight what-if: durable memory is now curatable --
  hand-edit the auto note and pin facts (kept verbatim, never overwritten, deduped on seed) via the Project
  memory card and `/memory/durable` (edit/pin/unpin). Each run now persists its actual context-event lines
  (`[fold]`/`[compact]`/`[trim]`, new `run_history.events` column) so a past run can be expanded to show its
  timeline, not just counts. The budget pre-flight gained a "use plan mode" what-if that switches to plan
  mode when a prompt would be trimmed.
- Per-run context report + durable cross-session memory + budget pre-flight: each run's folds/compactions/
  trims are attributed (thread-local tally in `Metrics`) and persisted with the run history (new
  `run_history` columns) and shown in the admin recent-runs list. A durable per-owner `[MEMORY]` note
  (`MemoryStore`, new `memory` table) now carries across sessions: a new session is seeded from it and it is
  updated after a run compacts; viewable/clearable in the Project memory card and via `/memory/durable`.
  A context-budget pre-flight (`/budget/preflight`) estimates prompt size vs the window and predicts
  compact/trim, shown live under the composer as you type.
- Unified context timeline + live-fold test + trace filter: compaction now emits a `[compact:<label>]`
  trace event (alongside `[fold:]`) and budget trims increment a `context_trim` counter, so folds,
  compactions and trims form one observable timeline -- summarized at /metrics under `context` and
  filterable in the web UI by event category (tools/guards/plan/fold/compact/other). Added
  `ContextFoldLiveTest` (real fold over an in-process HTTP stub server, no external model) and fixed the
  prior `*IT` test name so Surefire actually runs it (renamed to `ContextFoldConfigTest`). Removed the
  stale `PermissionGate.java` again (it had reappeared on the branch).
- Fold trace events in the UI + release dry-run smoke + repo cleanup: a fold during a run now streams a
  `[fold:<label>]` trace event (size in -> out) that renders highlighted in the web UI activity trace and in
  CLI output (`ContextManager.condenseToolResultTraced` + `AgentEngine`); `release.yml` gained a
  pull_request dry-run (builds jar + checksum, uploads as an artifact, no publish) on PRs touching the
  release plumbing; and the stale `PermissionGate.java` (superseded by `PermissionService`) was removed.
- Fold observability + @file folding + Trivy severity policy: folds now increment a `context_fold`
  counter (and `context_fold_fallback`) exposed at /metrics and /metrics/prom for the Grafana dashboard,
  and `ContextFoldConfigIT` proves the shipped defaults fold a ~100KB input; the fold is extended to
  oversized `@file`/`@directory` references (folded up to `context.refs.max-fold-file-kb` instead of
  skipped); and the Trivy gate now blocks fixable CRITICALs on PRs/pushes only while the weekly run reports
  (policy in docs/SECURITY.md).
- RLM-style bounded context fold + Trivy CRITICAL gate + release-please/CHANGELOG: a single tool result that
  vastly exceeds the window is now FOLDED (chunk -> summarize via the cheap model -> reduce -> recurse) by
  `ContextManager.condenseToolResult` instead of dropping its middle, reading every region once (lossy by
  compression, with graceful head+tail fallback; `agent.fold-*` settings). The supply-chain scan now FAILS
  on a fixable CRITICAL (`ignore-unfixed`, `.trivyignore` exceptions) while still reporting HIGH/CRITICAL to
  the Security tab. `release-please.yml` maintains a Conventional-Commit release PR that bumps `pom.xml` +
  `CHANGELOG.md` and tags; `release.yml` attaches the jar/checksum to that release.
- Release workflow + Dependabot + SBOM/supply-chain scan: a `v*` tag now builds the jar, checksums it, and
  publishes a GitHub Release (`release.yml`, with a tag-vs-pom version guard); `.github/dependabot.yml`
  opens weekly update PRs for Maven, GitHub Actions, and Docker; and `supply-chain.yml` generates a
  CycloneDX SBOM (Syft) and runs a Trivy dependency scan that reports into the Security tab. No app logic
  changed.
- Hard-fail hygiene guard + verified checksum pin + CI Maven cache: the script-hygiene check in CI is now a
  hard failure (not advisory); the wrapper downloads the official Apache Maven `.tar.gz` and verifies it
  against the pinned official SHA-512 (`scripts/pin-maven-checksum.sh` re-pins after a bump;
  `mvnw`/`mvnw.cmd`/`get-maven.ps1` all enforce it); CI caches the wrapper's `.maven` download; and a
  one-shot `scripts/git-mark-exec.sh` sets the executable bit on every script.
- Windows in CI + pinned wrapper checksum + script-hygiene guard: the smoke workflow now also runs on
  `windows-latest` (exercising `mvnw.cmd`/`get-maven.ps1` and a PowerShell `/health` probe); the wrapper
  can verify its Maven download against a SHA-256 (`scripts/pin-maven-checksum.sh` writes a verified value;
  `mvnw`/`mvnw.cmd`/`get-maven.ps1` all check it); and a `.gitattributes` + `.githooks/pre-commit` guard
  (shared `check-scripts.sh`, also run advisory in CI) keep scripts executable and LF so the earlier
  "Permission denied" regression cannot recur silently.
- Maven wrapper + cross-platform CI smoke test: added a lightweight `./mvnw` / `mvnw.cmd` (with
  `.mvn/wrapper/maven-wrapper.properties`) so the project builds with no system Maven (prefers a system
  `mvn`, else downloads a pinned Apache Maven into `.maven/`); `run.sh`/`run.bat` and `ci.yml` now use it.
  A new `smoke.yml` workflow builds and boots imini headless on Linux + macOS and probes `/health`, and
  shell-lints the POSIX scripts. (The previously-dangling `scripts/*.ps1` helpers are now committed.)
- Cross-platform run scripts (macOS/Linux/WSL) + OS-aware model binary: added POSIX `.sh` equivalents of
  every `.bat` (thin `curl` wrappers sharing `scripts/common.sh`, plus `run.sh`/`eval.sh`), and made
  `llama.binary` default per-OS (`llama-server` off Windows) so the managed-server path works everywhere
  with no config. Docs gained a macOS/Linux/WSL run section and per-OS install steps. No app logic changed.
- Key-management UI + multi-arch images + signed-index registry browser: a read-only `GET /workspace/keys`
  endpoint plus a Plugins-card **keys** panel show the verifier keyring (ids, expiry, revoked/expired/signer
  flags); the publish workflow now builds a linux/amd64 + linux/arm64 manifest; and the Browse-registry view
  shows the index-signature status as a banner. Pure `Keyring.describe` unit-tested.
- Key rotation/revocation + signed registry index + published demo image: keyring entries can carry an
  expiry (`key@<epochMillis>`) and ids can be revoked (`bundle.revoked-key-ids`), with `expired`/`revoked`
  verification statuses that imports refuse; the registry listing document is signable
  (`POST /plugin/registry/sign`, verified on fetch and gated by `plugins.require-signature`); and
  `docker-compose.published.yml` + a GHCR publish workflow run the demo from a prebuilt image with no local
  build. Pure `Keyring` (expiry/revocation) and `PluginRegistry.signablePayload` unit-tested.
- Verifier keyring + signed plugin packs + Docker demo stack: a `Keyring` lets a verifier trust several
  Ed25519 public keys (with key ids); plugin packs are signed on export and verified on install (optionally
  required via `plugins.require-signature`); and a `docker-compose.observability.yml` overlay brings up
  imini + model + Prometheus + Alertmanager + auto-provisioned Grafana in one command. Signing config is
  centralized in a new `SigningService` (which also restored the Ed25519 bundle verify path lost in a
  merge). Pure `Keyring` unit-tested.
- Public-key bundle signatures + durable scheduled-task run history + Alertmanager routing: bundles can be
  signed/verified with Ed25519 (mint keys via `POST /workspace/keygen`; signer sets the private key,
  verifiers only the public key), preferred over the existing HMAC; per-task run history now persists
  (`scheduled_task_runs` table, reloaded on startup); and `docs/observability/alertmanager.yml` plus a
  Prometheus `alerting` block route the bundled alert rules. Pure `BundleSignature` Ed25519 unit-tested.
- Scheduled-task run history + bundle signing + richer Grafana panels: each scheduled task keeps a short
  in-memory run log (`GET /schedule/runs`, plus a global `/schedule:<kind>` run-history/metrics feed);
  workspace/plugin bundles can be signed and verified with a shared-secret HMAC (`bundle.signing-secret`,
  pure `BundleSignature`); and the sample Grafana dashboard gains runs-by-endpoint
  (`imini_runs_by_endpoint`) and requests-by-key panels. Pure `BundleSignature` unit-tested.
- Workspace import preview + Prometheus alert rules + per-session run history: `POST
  /workspace/import/preview` dry-runs an import (create/overwrite/blocked + setting new/changed/unchanged,
  writing nothing) via pure `WorkspacePreview`; `docs/observability/alert-rules.yml` ships example
  Prometheus alerts (instance down, failure rate, queue backlog, latency) wired through `prometheus.yml`;
  and `GET /session/runs` returns one session's runs (exact-match, read-access-scoped) with a session
  toolbar button. Pure `WorkspacePreview` + `RunFilter.sessionEquals` unit-tested.
- Grafana dashboard sample + run-history filters + whole-workspace bundle: `docs/observability/` ships a
  Prometheus scrape config and a starter Grafana dashboard with a how-to; `GET /admin/runs` now filters by
  endpoint/outcome/session (pure `RunFilter`, unit-tested); and `GET /workspace/export` /
  `POST /workspace/import` back up or clone an entire setup (skills + agents + commands + settings) via a
  new `WorkspaceService` (pure `WorkspaceBundle` summary, unit-tested).
- Persist run history + scrape-friendly metrics + guided in-app tour: the recent-runs list is now durable
  (`run_history` table via `RunHistoryStore`, reloaded on startup, capped by `agent.run-history.persist-max`);
  `GET /metrics/prom` exposes the counters in Prometheus text format (pure `PromFormat`, unit-tested); and a
  dependency-free **? tour** button walks new users through the web-UI cards, ending at the learning docs.
- Run history view + resolved-mode-per-turn + registry publish helper: the admin dashboard now lists
  recent runs (endpoint, resolved mode, latency, outcome) via a bounded `RunHistory` buffer
  (`GET /admin/runs`, embedded in `/admin/overview`); every turn logs `[mode] running in <mode>` to its
  trace and records the resolved mode; and `POST /plugin/registry/entry` builds a registry index entry
  (with the pack's SHA-256) so you can host your own registry. Pure `RunHistory` + `modeSource` unit-tested.
- Durable per-session settings: a session remembers its default permission mode across restarts
  (`session_settings` table), layered as request mode > session default > global `ask`; `GET/POST
  /session/settings`, `POST /session/settings/clear`, and a toolbar dropdown. Pure
  `SessionSettingsResolver` (validation + precedence) unit-tested; completes the persistence story.
- Plugin registry index: browse a registry index (a JSON list of packs) and install by name, pinned to the
  registry's declared SHA-256 -- completing the plugin story (export -> install-by-URL -> discover).
  `GET /plugin/registry`, `POST /plugin/registry/install`, optional `plugins.registry-url` default, and a
  *Browse registry* UI flow. Pure `PluginRegistry` (parse/byName/search) unit-tested.
- Richer admin/observability views: `GET /admin/overview` consolidates uptime, run counts + success rate,
  latency, live concurrency, top tool calls, scheduled-task and plugin/skill summaries, server
  capabilities, and recent audit into one admin-only snapshot, rendered by a new web-UI *Admin overview*
  card. Pure `AdminFormat` (uptime humanize, top-N, success rate) unit-tested.
- Educational packaging / workshop materials: a newbie front-door `GettingStarted.md` (simple test +
  recommended learning path + a one-page document index), a guided `docs/WORKSHOP.md` (~90-minute, five
  labs, each with a `mvn test` checkpoint), and a plain-language `docs/GLOSSARY.md` (eight core terms);
  README "Start here" and "Recommended learning sequence" now lead with these. Docs-only, no code change.
- Image (multimodal) input, capability-gated: an image attached to a one-shot `ask` is sent to the model
  in the OpenAI `image_url` format when the model is vision-capable (`model.vision-enabled` or a `/props`
  probe); on a text-only model it is dropped with a note so the turn still runs. Pure `VisionContent`
  (data-URL + parts building) unit-tested; `VisionSupport` gates capability.
- Plugin registry (install-by-URL): install a pack straight from an http/https URL, fetching it and
  verifying its SHA-256 before writing (refuse on mismatch; unpinned allowed-but-flagged) -- mirrors the
  remote-skill install. Pure `PluginPack.sha256`/`matches` unit-tested; `POST /plugin/install-url` + UI.
- Durable settings + scheduled tasks: the token budget is persisted (`app_settings` via `SettingsStore`)
  and scheduled tasks are persisted to `scheduled_tasks` and reloaded on startup (overdue tasks fire
  shortly after restart, not instantly) -- runtime changes now survive a restart.
- Plugin packaging: export the workspace's skills + agents + commands as a portable JSON pack and install
  one back (`GET /plugin/export`, `POST /plugin/install`, web-UI *Plugins* card). Pure `PluginPack`
  (type/name validation + path sanitization, so installs can't escape `skills/`/`agents/`/`commands/`)
  is unit-tested; `PluginService` does the file I/O.
- `/loop` + scheduled local tasks: `/loop [check=<cmd>] [attempts=N] <goal>` is a bounded
  iterate-until-green command (make a change, run the Sandbox-screened check, repeat until it passes or
  the attempt budget is spent; supersedes the `loop` skill). Local **scheduled tasks** run a prompt after
  a delay or on an interval, unattended in AUTO mode, as run/plan/loop (in-memory, single-node). Pure
  `LoopCommand` + `Schedule` unit-tested; `ScheduledTasks` owns the ticker.
- Automatic plan-mode fallback: when a normal turn's assembled prompt would exceed the enforced token cap,
  the harness auto-runs it in plan mode (decompose into steps, each within budget) instead of trimming to
  force a one-shot answer -- on by default (`agent.plan.auto-fallback`), never re-triggers an explicit
  plan run; pure `PlanFallback` decision unit-tested.
- Configurable token budget (context-overflow fix): a per-call token budget (default 8500, set in the
  config file, the web UI's *Token budget* card, or `POST /settings/token-budget`) is enforced before
  every llama-server call -- the prompt is measured (real `/tokenize`, `chars/4` fallback) and, if over
  the cap (`budget − reserved`, clamped to the server's `n_ctx`), the message list is shrunk to fit
  (condense oversized messages, drop oldest middle turns, last-resort truncate). Prevents the
  "exceeds the available context size" 400. Pure `TokenBudget` (fit/estimate) unit-tested;
  `TokenBudgetService` holds the runtime value.
- Session fork / rename / export UX: friendly session titles (`/session/rename`, shown in the picker),
  one-click **fork** that copies conversation + plan history + todos into a new owned session
  (`/session/fork`), and a one-click **export** download; pure title/fork-name logic in `SessionNaming`
  (unit-tested), titles persisted via a new `session_titles` table.
- Memory parity polish (Priority 1 finish): `/init` now **improves an existing `CLAUDE.md` in place** --
  appending only the scaffold sections it is missing (append-only, content preserved; `InitDraft.augment`
  pure + tested) via the chat command and `POST /init?augment=true`; a web-UI *Project memory* card
  surfaces the `/memory` diagnostics (load order, source, reason, size); candidate ordering extracted to
  a pure, unit-tested `MemoryLoader.candidateOrder`.
- LSP-style code intelligence: `find_references` lists every whole-identifier *usage* of a symbol across
  the repo (declaration sites marked `[def]`), complementing `find_symbol` (definitions). Heuristic/regex
  identifier matching, not a typed resolver (`SymbolRefs` pure + unit-tested).
- Hunk-level approval: a staged preview is a list of per-edit hunks; `apply_previewed_patch` /
  `discard_previewed_patch` (and the web UI's per-hunk checkboxes + Apply selected/all) act on a chosen
  subset (`hunks="0,2"` / `"1-3"`), leaving the rest staged (`PreviewSelect` pure + unit-tested).
- Patch preview + review UX (Priority 5): `preview_patch` stages edits and returns a unified diff without
  writing; `apply_previewed_patch` re-validates against current files + snapshots; `discard_previewed_patch`
  drops it; a web-UI *Patch preview* card (and `GET /preview`, `POST /preview/apply|discard`) reviews
  before applying (`DiffRender` pure + unit-tested, `PreviewStore`).
- Forked skills (`context: fork`): a skill can run in an isolated sub-agent (scoped to its `allowed_tools`),
  returning only its final answer to the main thread -- the deferred Priority 3 item, now unblocked by
  the subagent registry.
- Custom subagent registry (Priority 4): named, tool-scoped subagents that run in their own isolated loop
  and return only a final answer -- built-in read-only `explore`/`review`/`debug`/`research`, plus
  `agents/*.md` (disk overrides built-ins). Surfaced via `/agents`, `/agent <name> <task>`, and a
  `delegate_agent` tool (`AgentLibrary` pure + unit-tested, `AgentRegistry`, generalized `SubAgent`).
- Skill frontmatter (Priority 3): `when_to_use` (feeds the auto-load scorer), `argument-hint` (shown in
  `/skills`), and `allowed_tools` (per-skill tool reminder on invocation), parsed by `SkillLibrary`.
- Bundled educational skills: `code-review`, `debug`, `batch`, and `loop` ship as `SKILL.md` files under
  `skills/`, each using `$ARGUMENTS` and pairing with `@file` references / the deterministic tools, so
  `/skills` is useful out of the box (load/parse asserted by `BundledSkillsTest`).
- `/skills` + direct `/<skill-name>` invocation: `/skills` lists available skills (descriptions +
  effective enabled-state); `/<skill-name> [args]` runs an enabled skill's body as the prompt with
  `$ARGUMENTS`/`$ARGS` substituted (args appended if no placeholder), logged as `[skill] invoked /<name>`
  on the trace. Built-in commands are reserved (`SkillInvocation` pure + unit-tested).
- `@file` / `@directory` prompt references: mentioning `@path` in a prompt inlines that file's content
  (or a directory's one-level listing) into what the model sees, inside a `<referenced-context>` block.
  Resolution is workspace-confined with file/total/dir-entry caps; unresolved tokens (e.g. `@mentions`)
  are left untouched; attachments are shown on the run trace (`ContextRefs` pure + unit-tested,
  `ContextRefService`). Completes Priority 2's core.
- `/init` (draft/update `CLAUDE.md`): a deterministic repository scan (build-system + language detection
  + layout) renders a `CLAUDE.md` scaffold and creates it if absent (never overwriting an existing file
  implicitly); `POST /init?write=&overwrite=` for explicit control (`RepoScan`/`InitDraft` pure +
  unit-tested, `InitService`). Completes Priority 1.
- Project memory (layered) + `/memory` diagnostics: loads `.claude/CLAUDE.md`, `CLAUDE.md`, `IMINI.md`,
  `AGENTS.md`, `.claude/rules/*.md`, and `CLAUDE.local.md` (in order) into the system prompt, inlines
  `@path` imports (depth/size/cycle guarded), and shows what loaded via the `/memory` command and
  `GET /memory/files` (`MemoryLoader` pure + unit-tested; `ProjectContext` rewritten).
- Skills: local/remote `SKILL.md`, registry, enable/disable, proposals,
  session overrides, and bundle export.
- Plan mode: execution, retry, re-planning, verification, persistence/resume,
  history, and per-step tool transcript.
- Edit trust: git-backed edit summaries, structured coding reports, schema
  validation, and per-step diff deltas.
- Sessions: export/import, integrity checks, migration, import preview,
  sharing, and ownership transfer.
- UI/ops: plan history, activity view, sharing surface, and audit entries.
