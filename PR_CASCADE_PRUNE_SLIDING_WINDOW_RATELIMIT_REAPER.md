## Cascade session prune + sliding-window rate limiting + scheduled rate-limit pruning

Three follow-throughs on the session-lifecycle and rate-limiting work: make session pruning clean up every
dependent table (not just four), add a sliding-window option to the rate limiter, and schedule periodic
pruning of stale rate-limit windows.

### Features
- **Cascade cleanup on session prune.** `SessionStore.pruneExpired` now deletes a session AND every
  `session_id`-keyed child row: owners, shares, titles, checkpoints, plans, plan_steps, plan_history,
  session_skill_state, session_settings, and bound scheduled_tasks (previously only the first three plus the
  session row were removed, leaving orphans). A new `SessionStore.sweepOrphans()` removes child rows whose
  parent session no longer exists — cleaning up data left behind by older builds — and runs on every reaper
  pass.
- **Sliding-window rate limiting.** `RateLimiter` gains a selectable algorithm: `fixed` (unchanged default)
  or `sliding`. The sliding-window counter weights the previous window's count by the fraction still in the
  trailing window, eliminating the burst-at-window-boundary weakness of the fixed window (where a client can
  send `2 * limit` requests straddling a boundary). Selectable via `auth.rate-limit-algorithm`. The pure
  arithmetic is a static, tested `slidingStep(...)`. Persistence is extended with a `prev_count` column.
- **Scheduled rate-limit pruning.** New `RateLimitReaper` periodically calls `RateLimiter.pruneStale` so the
  `rate_limits` table (or the in-memory map) doesn't accumulate windows for keys that have gone quiet. Runs
  every `auth.rate-limit-reap-interval-minutes` (default 10); disabled when rate limiting is off or the
  interval is 0. Mirrors `SessionReaper`.

### New files
- `src/main/java/com/example/imini/RateLimitReaper.java`
- `src/test/java/com/example/imini/SessionCascadePruneTest.java`
- `src/test/java/com/example/imini/SlidingWindowRateLimiterTest.java`

### Changed files
- `SessionStore.java` (cascade `deleteSessionCascade` + `SESSION_CHILD_TABLES` + `sweepOrphans`).
- `SessionReaper.java` (run `sweepOrphans` after each prune pass).
- `RateLimiter.java` (Algorithm enum, `slidingStep`, sliding allow paths, prev_count persistence,
  sliding-aware `pruneStale`).
- `Database.java` (migration: `ALTER TABLE rate_limits ADD COLUMN prev_count`).
- `AuthFilter.java` (`auth.rate-limit-algorithm` config; build limiter with the chosen algorithm; expose
  `limiter()` for the reaper).
- `src/main/resources/application.properties` (rate-limit algorithm + reap interval).
- `README.md`, `ROADMAP.md`, `TESTING.md` (cases 384-388).

### Behavior change
- Pruning a session now removes far more rows (the full cascade). This is the intended fix; nothing that
  should survive a session's deletion is touched.
- Rate limiting defaults are unchanged (`fixed`); set `auth.rate-limit-algorithm=sliding` to opt in.
- A new schema migration adds `rate_limits.prev_count` (applied automatically; defaults to 0, so existing
  fixed-window rows are unaffected).

### Honest scope
The orphan sweep targets the known `session_id`-keyed tables; it does not touch tables keyed by other ids
(e.g. memory pins keyed by owner@workspace). The sliding window is the standard "sliding-window counter"
approximation (previous-window weighting), not a per-request log — it is O(1) memory per key like the fixed
window, trading a small amount of accuracy for that. The rate-limit reaper prunes stale windows but does not
change limiter accuracy; it is purely storage hygiene.

### Testing
`./mvnw -Dtest=SessionCascadePruneTest,SlidingWindowRateLimiterTest,RateLimiterTest test`; full `./mvnw
test`. Manual check of the rate-limit reaper per TESTING.md case 388. Cases 384-388 in TESTING.md.
