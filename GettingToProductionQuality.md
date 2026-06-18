# Getting to Production Quality

## Why this document exists

**imini is a teaching harness.** Its goal is to be *read*: every part is small, explicit, and
self-contained so you can see how an AI agent actually works — the loop, the tool calls, the context
management, the safety rails. That goal is sometimes in direct tension with production quality, where the
priorities are uptime, isolation, scale, compliance, and cost control rather than legibility.

This document maps the **full feature surface of a production-grade agent harness** and marks honestly where
imini stands against it. It is meant as:

- a **mental model** of everything a real deployment has to solve, and
- a **gap analysis** showing which of those imini teaches, which it sketches, and which it deliberately
  leaves out.

It is not a to-do list for imini. Many items below would *hurt* imini's pedagogical value if added (they
trade readability for robustness). The point is to understand the distance, not necessarily to close it.

## How to read the status markers

| Marker | Meaning |
|---|---|
| ✅ **Present** | imini implements a real, working version of this (sometimes simplified). |
| 🟡 **Partial** | imini has a teaching-grade version that demonstrates the idea but isn't production-complete. |
| ⬜ **Not yet** | imini doesn't address this; it's a genuine gap to production. |
| 🚫 **Out of scope** | A production concern that imini intentionally omits because it would obscure the teaching goal. |

A production system is not "imini plus a few features." It is a different *shape*: stateless application
nodes behind a load balancer, shared durable stores, a managed model layer, centralized secrets, and an
operations practice around it. Keep that shape in mind as you read.

---

## A. Model & inference layer

| Feature | Status | What production needs / why |
|---|---|---|
| Local model via llama.cpp | ✅ | imini runs one local llama.cpp server. Teaches the model/harness split cleanly. |
| Provider abstraction | ⬜ | A pluggable interface over multiple backends (hosted APIs, multiple local servers) so the harness isn't coupled to one engine. |
| Model routing & fallback | ⬜ | Route by task/cost/latency; fall back to a secondary model/provider when the primary is down or rate-limited. |
| Multiple model tiers | 🟡 | imini has a separate cheaper "summary model" for compaction — the seed of tiering. Production routes many tasks across cheap/strong models. |
| Streaming resilience | 🟡 | imini retries the stream *connection* under a circuit breaker; production also needs mid-stream resumption or graceful degradation. |
| Token accounting / context budgeting | ✅ | imini measures tokens and enforces a prompt budget, with fold/compact/trim. |
| Structured output / function-calling contracts | 🟡 | imini validates tool-call schemas; production hardens against malformed/hallucinated calls at scale and versions the contracts. |
| Prompt & model version management | ⬜ | Treat prompts and model choices as versioned, reviewable artifacts with rollout control (see §L, §M). |
| Inference caching | 🟡 | imini caches embeddings; production also caches/deduplicates completions where safe. |

---

## B. Reliability & resilience

| Feature | Status | What production needs / why |
|---|---|---|
| Retry with backoff + jitter | ✅ | imini retries transient (network/5xx) failures, never 4xx. |
| Circuit breaker | ✅ | imini opens a breaker after N consecutive failures and fails fast during a cooldown. |
| Graceful shutdown / drain | ✅ | imini drains in-flight runs on SIGTERM before exiting. |
| Timeouts everywhere | 🟡 | imini times out tool commands and HTTP calls; production audits *every* external call for a bounded timeout. |
| Bulkheads / concurrency caps | ✅ | imini bounds concurrent runs to model slots via a fair semaphore. |
| Backpressure / load shedding | 🟡 | imini queues on the semaphore; production sheds load (429/503) and signals clients before queues grow unbounded. |
| Idempotency keys | ⬜ | De-duplicate retried client requests so a network retry doesn't run the agent twice. |
| Durable async job queue | ⬜ | Long-running agent tasks survive a node restart via a real queue (not an in-process executor); at-least-once delivery with dedupe. |
| Dead-letter / poison-message handling | ⬜ | Failed jobs are quarantined and inspectable, not silently dropped or infinitely retried. |
| Disaster recovery (backup/restore, PITR) | ⬜ | Regular backups, tested restores, point-in-time recovery of the datastore. |
| Multi-region failover | ⬜ | Survive a region outage; relevant only at significant scale. |

---

## C. Scalability & performance

| Feature | Status | What production needs / why |
|---|---|---|
| Stateless application nodes | ⬜ | imini keeps state in a local SQLite file, so it's effectively single-node. Production moves session/run/rate-limit state to a **shared** store so any node can serve any request. |
| Shared datastore (Postgres/managed) | ⬜ | SQLite is perfect for teaching and single-box use; horizontal scale needs a networked, concurrent-write DB. |
| Horizontal autoscaling | ⬜ | Scale nodes on load (requests, queue depth, latency) — requires the statelessness above. |
| Connection pooling | ⬜ | Pooled DB and HTTP connections sized for concurrency. |
| Caching tier (shared) | 🟡 | imini caches in-process (embeddings, LRU); production adds a shared cache (e.g. Redis) so cache survives restarts and is shared across nodes. |
| Load testing & capacity planning | ⬜ | Known throughput/latency curves; documented capacity per node. |
| Cold-start / model warm-up handling | 🟡 | imini's `/healthz` reports "degraded" while the model warms; production manages warm pools and pre-pulled weights. |

---

## D. Security & access control

| Feature | Status | What production needs / why |
|---|---|---|
| Authentication | 🟡 | imini supports API keys. Production typically needs SSO/OIDC, short-lived tokens, and per-user identity. |
| Authorization (RBAC) | 🟡 | imini has admin/user roles and per-path admin gating. Production needs fine-grained, resource-level authorization. |
| API key rotation & revocation | ⬜ | Rotate keys without downtime; revoke compromised keys immediately. |
| Rate limiting | ✅ | imini has per-key fixed-window limiting, now persisted across restarts. Production often wants tiered/quota-based limits (see §J) and a distributed limiter. |
| Input validation & request limits | 🟡 | imini caps command length and output; production caps request body size, nesting depth, and validates all inputs centrally. |
| Prompt-injection defenses | 🟡 | imini fences untrusted tool output and teaches the concept; production layers detection, output filtering, and tool-permission scoping. |
| TLS / transport security | ⬜ | Terminate TLS (usually at a gateway/ingress); enforce HTTPS, HSTS. |
| CORS / CSRF policy | ⬜ | Explicit, least-privilege browser security policy for the UI/API. |
| Dependency & vuln scanning | ✅ | imini has a supply-chain CI workflow. Production adds continuous scanning + alerting + patch SLAs. |
| Penetration testing / red-teaming | ⬜ | Regular adversarial testing of both the app and the model behavior. |

---

## E. Multi-tenancy & isolation

| Feature | Status | What production needs / why |
|---|---|---|
| Tenant model | ⬜ | imini is single-tenant (workspace-scoped memory is the closest analog). Production isolates tenants' data, config, and quotas. |
| Per-tenant data isolation | ⬜ | Hard guarantees that tenant A can never read tenant B's sessions/memory/files — enforced in the datastore and the code paths. |
| Per-tenant configuration & quotas | ⬜ | Limits, model access, and features vary by tenant/plan. |
| Noisy-neighbor protection | 🟡 | imini's global concurrency cap is a blunt version; production isolates resources per tenant. |
| Workspace/file confinement | ✅ | imini confines reads/writes to a workspace root — the right instinct, scoped per install rather than per tenant. |

---

## F. Data, persistence & privacy

| Feature | Status | What production needs / why |
|---|---|---|
| Durable persistence | ✅ | imini persists sessions, runs, plans, memory, checkpoints in SQLite. |
| Schema migrations | ✅ | imini has an append-only, versioned migration list. |
| Data lifecycle / retention | 🟡 | imini prunes run-history and (opt-in) idle sessions; production needs configurable retention + **cascade cleanup of dependent rows** and orphan sweeps. |
| Encryption at rest | ⬜ | Sensitive data (sessions, memory, secrets) encrypted on disk / in the managed DB. |
| Encryption in transit | ⬜ | TLS between every hop (client↔app, app↔DB, app↔model). |
| PII detection & redaction | ⬜ | Detect and redact personal data in prompts, logs, and stored history. |
| Right to deletion / export | 🟡 | imini can export/import a signed workspace bundle; production needs per-user data export and verifiable deletion (GDPR/CCPA). |
| Backups & restore drills | ⬜ | Automated, tested, monitored backups. |
| Data residency | ⬜ | Store/process data in required jurisdictions. |

---

## G. Secrets & key management

| Feature | Status | What production needs / why |
|---|---|---|
| Secrets kept out of logs | ✅ | imini masks secrets and scrubs known secret strings from log lines. |
| Config validation / fail-fast | ✅ | imini validates config at startup and refuses contradictory settings. |
| Centralized secret manager | ⬜ | Secrets from a vault/KMS, not env files; short-lived, rotated, access-audited. |
| Signing keys & artifact signing | 🟡 | imini signs workspace bundles (HMAC/Ed25519). Production signs releases/containers and verifies provenance. |
| Key rotation | ⬜ | Rotate signing/encryption/API keys on a schedule and on compromise. |

---

## H. Tool execution & sandboxing

| Feature | Status | What production needs / why |
|---|---|---|
| Command screening (allow/deny) | ✅ | imini screens shell commands against allow/deny lists with a max length. |
| Path confinement | ✅ | imini confines file reads/writes to the workspace root. |
| Output caps & timeouts | ✅ | imini caps command output bytes and kills runaway processes. |
| Working-directory confinement | ✅ | imini launches tool processes with the workspace as the working directory. |
| Optional container wrapping | 🟡 | imini can wrap commands in a configured container command — the hook for real isolation. |
| True process isolation | ⬜ | Production runs untrusted tool code in containers/microVMs (gVisor, Firecracker) with seccomp/AppArmor, not just string screening. |
| Network egress control per tool | ⬜ | Restrict which hosts a tool can reach; default-deny egress. |
| Resource limits (CPU/mem/FDs) | ⬜ | cgroup-style limits per tool execution. |
| Capability scoping & tool versioning | ⬜ | Each tool declares the capabilities it needs; tools are versioned and individually enable/disable-able per tenant. |
| Human-in-the-loop approval | ✅ | imini gates mutating tools behind approval. Production adds approval audit trails and policy-based auto-approval. |

---

## I. Observability & operations

| Feature | Status | What production needs / why |
|---|---|---|
| Health & readiness probes | ✅ | imini exposes `/health` (liveness) and `/healthz` (readiness incl. DB, model, circuit-breaker state). |
| Metrics | ✅ | imini exposes a snapshot and Prometheus text (`/metrics/prom`) with counters, latency, and SLO gauges. |
| Structured logging | ✅ | imini has a JSON logging profile with per-request and per-run MDC correlation IDs. |
| Distributed tracing (OpenTelemetry) | ⬜ | End-to-end spans across services (gateway → app → model → tools), not just correlated logs. |
| Dashboards & alerts | ✅ | imini ships Prometheus alert rules and a Grafana dashboard. |
| SLOs & error budgets | 🟡 | imini computes success-rate and p50/p95 (in-memory + durable over a window). Production formalizes SLOs, error budgets, and burn-rate alerts. |
| Trace/run export | ✅ | imini exports per-run NDJSON and a full persisted run-history feed. |
| Runbooks & incident response | ⬜ | Documented on-call, runbooks, paging, postmortems. |
| Audit log | 🟡 | imini records privileged actions. Production needs tamper-evident, retained, exportable audit logs. |

---

## J. Cost, quotas & billing

| Feature | Status | What production needs / why |
|---|---|---|
| Token/cost accounting | 🟡 | imini approximates output tokens in metrics. Production tracks precise input/output tokens and cost per request/tenant. |
| Per-tenant quotas | ⬜ | Hard and soft limits on usage; enforcement and graceful denial. |
| Budgets & spend alerts | ⬜ | Alert and/or cut off when a tenant or the system approaches a spend ceiling. |
| Usage metering for billing | ⬜ | Accurate, auditable usage records suitable for invoicing. |
| Cost-aware routing | ⬜ | Choose cheaper models/paths when acceptable for the task. |

---

## K. Safety, moderation & content policy

| Feature | Status | What production needs / why |
|---|---|---|
| Prompt-injection fencing | 🟡 | imini fences untrusted content and teaches the risk; production adds layered detection and response. |
| Input/output content moderation | ⬜ | Screen prompts and responses for disallowed content per a written policy. |
| Jailbreak / abuse resistance testing | ⬜ | Continuous adversarial evaluation of model behavior. |
| PII / sensitive-data handling | ⬜ | Detect, redact, and avoid storing sensitive data (overlaps §F). |
| Safety policy & escalation | ⬜ | A written content policy, enforcement, and a path to human review. |
| Abuse rate limiting & anomaly detection | 🟡 | imini rate-limits per key; production detects abusive patterns and reacts. |

---

## L. Quality: evaluation, testing & prompt management

| Feature | Status | What production needs / why |
|---|---|---|
| Unit & integration tests | ✅ | imini has a broad unit suite plus real-SQLite integration tests that self-skip when persistence is unavailable. |
| Deterministic offline tests | ✅ | imini's core logic (parsing, budgeting, retry, breaker, percentiles) is pure and testable without a live model. |
| Agent evaluation harness (evals) | ⬜ | Measure agent *quality* — task success rate, tool-use correctness, regressions — on a fixed suite, gating releases. |
| Golden/regression traces | 🟡 | imini documents an annotated end-to-end trace; production runs automated regression on representative traces. |
| Prompt experimentation & A/B | ⬜ | Compare prompt/model variants on metrics before rollout. |
| Load & soak testing | ⬜ | Sustained-load and long-running stability tests. |
| Chaos / fault injection | ⬜ | Deliberately inject failures (model down, DB slow) to validate resilience (imini's graceful-degradation tests are a small step here). |

---

## M. Deployment, release & supply chain

| Feature | Status | What production needs / why |
|---|---|---|
| Containerization | ✅ | imini ships a Dockerfile with a health check and compose files. |
| CI build & test | ✅ | imini has CI for build/test, smoke, and publishing. |
| Kubernetes manifests / probes | 🟡 | imini documents liveness/readiness probes; production ships full manifests (HPA, PDB, resource requests/limits, secrets). |
| Progressive delivery (canary/blue-green) | ⬜ | Roll out app, prompt, and model changes gradually with automatic rollback on regression. |
| Infrastructure as code | ⬜ | Reproducible environments (Terraform/Helm) under version control. |
| SBOM & artifact provenance (SLSA) | 🟡 | imini has a supply-chain workflow; production generates an SBOM, signs artifacts, and attests build provenance. |
| Reproducible builds & pinned deps | ✅ | imini pins its Maven wrapper and dependencies. |
| Release automation & changelog | ✅ | imini uses release automation and keeps a changelog. |

---

## N. Compliance, audit & governance

| Feature | Status | What production needs / why |
|---|---|---|
| Audit trail of privileged actions | 🟡 | imini logs them; production makes them tamper-evident and retained per policy. |
| Compliance posture (SOC2/ISO/GDPR) | ⬜ | Controls, evidence, and processes for the regimes you operate under. |
| Data processing agreements / residency | ⬜ | Contractual and technical data-handling guarantees. |
| Access reviews & least privilege | ⬜ | Periodic review of who can access what. |
| Policy as code | ⬜ | Encode authorization/safety/retention policy where it can be tested and enforced. |
| Change management / approvals | 🟡 | imini's PR-per-change discipline is the seed; production formalizes review, sign-off, and traceability. |

---

## O. User experience & API surface

| Feature | Status | What production needs / why |
|---|---|---|
| HTTP API | ✅ | imini exposes a documented HTTP API and a web UI. |
| API versioning & deprecation policy | ⬜ | Versioned endpoints; a contract for breaking changes. |
| OpenAPI / SDKs | ⬜ | Machine-readable API spec and generated client SDKs. |
| Pagination / filtering everywhere | 🟡 | imini paginates run history; production applies consistent pagination/filtering across list endpoints. |
| Accessibility (a11y) & i18n | ⬜ | Accessible, localizable UI. |
| Admin console | 🟡 | imini has an admin dashboard (runs, SLO, health, memory); production expands tenant/user/quota management. |

---

## A pragmatic path: tiers, not a checklist

Production readiness is reached in layers. A useful ordering for a team taking a harness like imini toward
production:

**Tier 1 — Don't lose data or fall over (single-tenant, low scale).**
Durable persistence ✅, migrations ✅, backups/restore ⬜, retry ✅, circuit breaker ✅, graceful shutdown ✅,
timeouts 🟡, health/metrics/logging ✅, secrets out of logs ✅, TLS ⬜, basic auth 🟡.

**Tier 2 — Be safe and operable (real users, single region).**
SSO/short-lived tokens ⬜, fine-grained authz ⬜, encryption at rest/in transit ⬜, secret manager ⬜,
true tool isolation ⬜, content moderation ⬜, audit trail hardening 🟡, dashboards/alerts ✅, runbooks ⬜,
evals ⬜.

**Tier 3 — Scale and multi-tenancy.**
Stateless nodes + shared datastore ⬜, autoscaling ⬜, durable job queue ⬜, per-tenant isolation & quotas ⬜,
cost accounting/billing ⬜, distributed tracing ⬜, progressive delivery ⬜.

**Tier 4 — Enterprise & compliance.**
SOC2/GDPR controls ⬜, data residency ⬜, multi-region failover ⬜, policy as code ⬜, SBOM/SLSA 🟡,
formal SLOs & error budgets 🟡.

Notice that imini is genuinely solid on **Tier 1 reliability and operability** — that's where a readable
teaching harness and a production system overlap most, and it's the most instructive place to be thorough.
The distance to Tiers 2–4 is mostly *infrastructure shape* (statelessness, shared stores, isolation,
identity, compliance) rather than agent logic — which is exactly why a teaching harness stops where it does.

## What imini deliberately should *not* become

Some production features would actively damage imini's purpose. Keeping these **out** is a feature, not a gap:

- 🚫 **Heavy infra dependencies** (Kafka, Redis, Postgres clusters) — they'd bury the agent logic that imini
  exists to show. SQLite-in-a-file is a teaching virtue.
- 🚫 **A managed cloud control plane** — operational complexity that teaches nothing about agents.
- 🚫 **Closed/abstracted internals** — production systems hide complexity behind frameworks; imini's value is
  that nothing is hidden.
- 🚫 **Premature multi-tenancy** — it would complicate every data path for a concept orthogonal to "how an
  agent works."

The right way to use this document with imini is as a **map**: read a section, find the corresponding code
(or its absence), and understand *why* the production version diverges. The gap between imini and production
is itself one of the most valuable things imini can teach.
