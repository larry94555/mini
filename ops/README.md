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
- **SLO** — `IminiRunSuccessRateLow`, `IminiRunLatencyP95High`.

## Grafana dashboard — `grafana/imini-dashboard.json`

Grafana → Dashboards → New → Import → upload the JSON, then pick your Prometheus datasource when prompted
(the dashboard declares a `DS_PROMETHEUS` input). Panels cover the SLO summary, security-event rates,
the alert-delivery pipeline (sent / failed / dead-lettered / dropped / backlog), and tool/endpoint usage.

> These files are plain text you are meant to edit — thresholds, scrape interval, and panel layout are
> starting points, not prescriptions.
