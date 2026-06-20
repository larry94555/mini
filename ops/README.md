# imini ops bundle — Grafana dashboard & Prometheus alert rules

Turnkey monitoring for the `imini_*` metrics exposed at `GET /metrics/prom` (admin-only Prometheus text
exposition). See the main README's "Observability" section for the metric catalog.

## Prerequisites

- Prometheus scraping imini. Because `/metrics/prom` requires an admin key, scrape with a header:

  ```yaml
  # prometheus.yml
  scrape_configs:
    - job_name: imini
      metrics_path: /metrics/prom
      authorization:
        credentials: "<ADMIN_API_KEY>"   # sent as the bearer; or use a header relabel for X-API-Key
      static_configs:
        - targets: ["imini-host:8080"]
  ```

  If imini is keyed on `X-API-Key` rather than bearer auth, put a reverse proxy in front that injects the
  header, or run the scrape against an internal port. Adjust to your deployment.

## Alert rules — `prometheus/imini-alerts.yml`

Copy next to `prometheus.yml` and reference it:

```yaml
# prometheus.yml
rule_files:
  - imini-alerts.yml
```

Included alerts (tune thresholds to your traffic):

- **Security** — `IminiCapabilityDenialsSpiking`, `IminiToolRateLimitingHot`, `IminiSpendAlertFiring`.
- **Alerting pipeline** — `IminiAlertDeadLettersGrowing`, `IminiAlertsDropped`, `IminiAlertDeadLetterBacklog`
  (these watch the delivery buffer itself; pair them with `POST /admin/alerts/replay` once the receiver is
  healthy again).
- **Escalation & noise** — `IminiAlertSlaBreaches` (an alert went un-acked past its tier SLA and was
  re-escalated), `IminiAlertEscalating` (alerts climbing the ladder), `IminiAlertAckLatencyHigh` (acks taking
  >30m), `IminiAlertSuppressionStorm` (dedup collapsing a flood), `IminiAlertDeliveryLatencyHigh` (slow webhook), `IminiAlertDeliverySLOBurnFast`/`...BurnSlow` (error-budget burn), `IminiAlertSelfTestFailing`, `IminiAlertSelfTestFlapping`, `IminiAlertRouteSLOBurning`, `IminiAlertSLOBudgetExhausted`, `IminiAlertSLOWindowBudgetExhausted`, `IminiAlertDeliverySuccessBurnFast`, `IminiAlertRouteSuccessLow`. See the runbook below.
- **SLO** — `IminiRunSuccessRateLow`, `IminiRunLatencyP95High`.

## Grafana dashboard — `grafana/imini-dashboard.json`

Grafana → Dashboards → New → Import → upload the JSON, then pick your Prometheus datasource when prompted
(the dashboard declares a `DS_PROMETHEUS` input). Panels cover the SLO summary, security-event rates,
the alert-delivery pipeline (sent / failed / dead-lettered / dropped / backlog), escalations & SLA breaches, per-tier escalations and ack latency, dedup suppression/digests, delivery latency + SLO burn (global and per-route), self-test status/flapping, and tool/endpoint usage. The burn-rate rules reference the app's own `imini_alerts_slo_good_total`/`_total_total` counters and `imini_alerts_slo_target` gauge, so the objective lives only in `alerts.slo-*` config — change it without editing the rules.

## Runbook — responding to alerting-pipeline pages

The admin surface for all of these is the **overview page** `GET /admin/alerts/overview.html` (live
auto-refresh; add `?refresh=0` to freeze, `?refresh=5` for a 5s cadence) and the **dead-letter viewer**
`GET /admin/alerts.html`.

| Alert | What it means | First response |
| --- | --- | --- |
| `IminiAlertDeadLettersGrowing` / `IminiAlertDeadLetterBacklog` | Webhook delivery is failing past retries | Fix the receiver, then `POST /admin/alerts/replay-all?status=failed` (or per-id in the viewer) |
| `IminiAlertsDropped` | The in-flight buffer is saturated | Raise `alerts.queue-capacity` or shed load; deliveries are being lost while saturated |
| `IminiAlertEscalating` | Un-acked dead-letters are climbing the ladder | Acknowledge handled ones with `POST /admin/alerts/ack?id=...`; check the on-call channel for the escalation tier |
| `IminiAlertSlaBreaches` | An alert blew its tier ack-SLA and was re-escalated | Ack or resolve immediately; review the tier's SLA on `GET /admin/alerts/config` if it's too tight |
| `IminiAlertAckLatencyHigh` | Acks are slow (>30m max) | Review per-tier ack latency on the overview page; adjust on-call rotation or tier URLs |
| `IminiAlertSuppressionStorm` | Dedup is collapsing a flood of duplicates | Inspect the top suppressed keys at `GET /admin/alerts/digests`; fix the upstream cause |
| `IminiAlertDeliveryLatencyHigh` | The webhook receiver is slow (p95 >2s) | Probe it with `POST /admin/alerts/selftest?send=true`; check the receiver before it starts failing |
| `IminiAlertDeliverySLOBurnFast` / `...BurnSlow` | Delivery-latency error budget is burning (multi-window) | Find the slow route on the overview page; the fast variant means the 30-day budget is gone in ~2 days |
| `IminiAlertSelfTestFailing` | The scheduled synthetic self-test isn't passing | Alert wiring is likely broken — check `GET /admin/alerts/config` warnings and `POST /admin/alerts/selftest?send=true` |
| `IminiAlertSelfTestFlapping` | The self-test is oscillating pass/fail | Intermittent delivery problem; inspect the run history at `GET /admin/alerts/selftest` |
| `IminiAlertRouteSLOBurning` | One route is burning its budget while the global SLO may look fine | Find the degraded receiver via the per-route SLO panel; check that route's webhook |
| `IminiAlertSLOBudgetExhausted` / `IminiAlertRouteSLOBudgetExhausted` | The error budget (global or per-route) is fully spent (`budget_remaining < 0`) | The SLO is being missed over the window; the budget panel shows runway — treat as a sustained-degradation signal, not a transient blip |
| `IminiAlertSLOWindowBudgetExhausted` | The rolling-window (e.g. 30-day) latency budget is spent | The SLO will be missed for the period; this is the monthly-report signal, not a blip |
| `IminiAlertDeliverySuccessBurnFast` | Alerts are dead-lettering faster than the success budget allows | Receiver is rejecting/erroring; check the dead-letter backlog and receiver health |
| `IminiAlertRouteSuccessLow` | One route's 2xx delivery ratio is below target | That receiver is up-but-erroring; check its endpoint/auth |
| `IminiAlertRouteSuccessBurning` | A route is burning its delivery-success budget (multi-window) | Failing deliveries are concentrated on that receiver; check it before the global success SLO degrades |
| `IminiAlertSloDigestMuted` | Scheduled SLO digests are currently muted | Expected during a known-degraded window; it auto-expires and digests resume. Surfaced so a silent digest isn't mistaken for healthy |

The overview page `GET /admin/alerts/overview.html` now also shows an **SLO summary** (latency success ratio, error budget remaining, rolling-window budget, delivery-success ratio) that live-updates with the page. When a database is available the rolling-window SLO buckets are persisted (table `alert_slo_buckets`) so the window survives a restart; rows that age out of the horizon are pruned on the reaper tick so the table stays bounded.

To confirm what's actually configured (parsed tiers + SLAs, routes, dedup/retention, CSRF mode), hit
`GET /admin/alerts/config` — it returns the effective resolved config with webhook URLs masked, so a
mistyped tier or route that silently parsed to nothing is easy to spot. The config endpoint also returns a
`warnings` array of detected misconfigurations (also logged at startup). To verify the pipeline end-to-end
without waiting for a real incident, `POST /admin/alerts/selftest` reports how a synthetic alert resolves
(routing/dedup/URL); add `?send=true` for a live probe POST that reports the receiver's HTTP status and
round-trip latency.

> These files are plain text you are meant to edit — thresholds, scrape interval, and panel layout are
> starting points, not prescriptions.
