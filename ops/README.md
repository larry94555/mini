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
  >30m), `IminiAlertSuppressionStorm` (dedup collapsing a flood). See the runbook below.
- **SLO** — `IminiRunSuccessRateLow`, `IminiRunLatencyP95High`.

## Grafana dashboard — `grafana/imini-dashboard.json`

Grafana → Dashboards → New → Import → upload the JSON, then pick your Prometheus datasource when prompted
(the dashboard declares a `DS_PROMETHEUS` input). Panels cover the SLO summary, security-event rates,
the alert-delivery pipeline (sent / failed / dead-lettered / dropped / backlog), escalations & SLA breaches, per-tier escalations and ack latency, dedup suppression/digests, and tool/endpoint usage.

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

To confirm what's actually configured (parsed tiers + SLAs, routes, dedup/retention, CSRF mode), hit
`GET /admin/alerts/config` — it returns the effective resolved config with webhook URLs masked, so a
mistyped tier or route that silently parsed to nothing is easy to spot.

> These files are plain text you are meant to edit — thresholds, scrape interval, and panel layout are
> starting points, not prescriptions.
