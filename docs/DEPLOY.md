# Deploying imini

imini is a single Spring Boot jar that talks to a llama.cpp server. It serves HTTP on port `8080`.

## Health endpoints

- `GET /health` — bare liveness (`{"status":"ok"}`), always cheap.
- `GET /healthz` — **readiness**: overall `status` (`ok` / `degraded` / `down`), database availability,
  llama-server reachability + context window, uptime, the context-management summary, and durable-memory
  presence. It returns HTTP 200 whenever the app is serving (even when `degraded`, e.g. the llama-server is
  still warming up), so use the JSON `status` field for fine-grained gating and the HTTP code for "is it up".

`/healthz` makes one lightweight upstream probe (`/props` on the llama-server) per call, so poll it on a
sensible interval (seconds), not per-request.

## Docker

The image declares a `HEALTHCHECK` that curls `/healthz`, so `docker ps` shows `healthy`/`unhealthy`:

```
docker build -t imini .
docker run -p 8080:8080 imini
docker inspect --format '{{.State.Health.Status}}' <container>
```

`docker-compose.yml` sets the same healthcheck on the `imini` service.

## Kubernetes

Use `/health` for liveness (don't restart for a warming-up dependency) and `/healthz` for readiness:

```yaml
livenessProbe:
  httpGet: { path: /health, port: 8080 }
  initialDelaySeconds: 20
  periodSeconds: 20
readinessProbe:
  httpGet: { path: /healthz, port: 8080 }
  initialDelaySeconds: 30
  periodSeconds: 15
  failureThreshold: 3
```

Because `/healthz` returns 200 while `degraded`, a strict readiness gate that should hold traffic until the
model is reachable can instead check the JSON `status` via an exec probe, or front it with a sidecar — the
HTTP-code probe above treats "serving but degraded" as ready, which is usually what you want for a UI.

## Observability

- `GET /metrics` (snapshot) and `GET /metrics/prom` (Prometheus text) for scraping.
- `GET /admin/overview` (admin) powers the dashboard: runs, latency, tools, **context-management totals**, and
  **durable-memory** state, plus a recent-runs timeline.
- `GET /admin/runs.ndjson` (admin) streams recent runs as newline-delimited JSON (one run per line, with
  per-run fold/compact/trim counts and the event timeline) for piping into external log/trace tooling.
- The admin card shows a health dot (green `ok` / amber `degraded` / red `down`) and a `runs.ndjson` download.
