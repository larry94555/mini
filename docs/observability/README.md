# Observability: scraping imini with Prometheus + Grafana

imini exposes its in-process metrics in the Prometheus text exposition format at **`GET /metrics/prom`**
(admin only). This folder has a ready-to-use scrape config and a starter Grafana dashboard.

## 1. Turn on metrics and set an admin key

Metrics and run history are on by default. Set an admin API key (see the main README's *Safety notes* /
auth section) so the scraper can authenticate; `/metrics/prom` is admin-only.

## 2. Point Prometheus at imini

Edit [`prometheus.yml`](prometheus.yml), replacing `REPLACE_WITH_ADMIN_API_KEY` with your admin key (it is
sent as a Bearer token; the `X-API-Key` header also works). Then run:

```
prometheus --config.file=prometheus.yml
```

Confirm the target is UP at `http://localhost:9090/targets`. You can sanity-check the raw feed with:

```
curl "localhost:8080/metrics/prom" -H "X-API-Key: <admin-key>"
```

## 3. Import the Grafana dashboard

In Grafana: **Dashboards -> New -> Import**, upload [`grafana-dashboard.json`](grafana-dashboard.json), and
select your Prometheus data source when prompted. You get panels for runs (ok/failed), run latency
(avg/max), tool calls by name, concurrency (active/queued/limit), uptime, and approximate output tokens.

## 4. (Optional) alerting rules

[`alert-rules.yml`](alert-rules.yml) ships example Prometheus alerting rules: instance down, high run
failure rate (>20% over 5m), queue backlog (runs waiting for a slot), and high average latency. They are
referenced from `prometheus.yml` via `rule_files`. Wire an Alertmanager to actually deliver them, and tune
the thresholds to your traffic. Because imini's counters reset on restart, the failure-rate rule uses a
5-minute `increase(...)` ratio rather than lifetime totals.

## Metrics reference

| Series | Meaning |
|---|---|
| `imini_counter{name="..."}` | Named event counters (e.g. `runs_ok`, `runs_failed`, `runs_started`, `tool_calls`) |
| `imini_tool_calls{tool="..."}` | Tool calls by tool name |
| `imini_requests_by_key{key="..."}` | Requests by API-key label |
| `imini_run_latency_avg_ms` / `imini_run_latency_max_ms` | Run latency (average / max) |
| `imini_concurrency_active` / `_queued` / `_limit` | Live run concurrency |
| `imini_uptime_seconds` | Process uptime |
| `imini_approx_output_tokens` | Approximate model output tokens |

## Honest scope

The counters are **in-process**: they reset when imini restarts and are not aggregated across nodes, and
there are no histograms/percentiles. Scraping into Prometheus is exactly how you get retention and
history on top of those instantaneous counters. Built-in alerting is not part of imini itself; the bundled `alert-rules.yml` shows how to add it at the
Prometheus layer.
