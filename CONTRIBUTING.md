# Contributing to imini

imini is an education-grade agent harness; changes should keep the build green and the docs honest. This
page consolidates the local and CI gates into one "before you push" checklist.

## One-time setup

```sh
sh scripts/install-hooks.sh        # Windows: scripts\install-hooks.cmd
```

This sets `core.hooksPath=.githooks` and marks the hooks executable. It enables two guards:

- **pre-commit** — blocks a commit if a required script lost its executable bit (`100755`) or a `*.sh`/`mvnw`
  file has CRLF endings. It prints the exact `git update-index --chmod=+x ...` fix.
- **pre-push** — runs `./run.sh check` (the docs reference/link checker plus its anchor/slug self-test) and
  blocks the push if either fails. Bypass a single push with `git push --no-verify`.

## Before you push

Run the same gates CI runs:

```sh
./run.sh check        # docs reference/link integrity + the anchor/slug self-test (both CI doc gates)
./mvnw test           # unit + golden-trace suite
```

`./run.sh check-docs` runs just the documentation checker; `./run.sh help` lists the subcommands.

## What CI enforces

- **`smoke.yml`** — POSIX `sh -n` over every `*.sh`, and `.githooks/check-scripts.sh` (executable-bit + LF
  hygiene, including that every tracked `.githooks/*` hook is `100755`). Cross-platform on Linux, macOS, and
  Windows.
- **`ci.yml`** — `scripts/check-docs.sh` (references, links, anchors, and that every script a workflow
  invokes exists), `scripts/check-docs-selftest.sh` (the slug regression guard), then the full test suite.
- **`eval-gate.yml`** (opt-in) — boots a small model and fails below a pass-rate threshold.

## Conventions

- Shell scripts are **POSIX `sh`** (no bash arrays, `[[ ]]`, or process substitution) — the smoke `sh -n`
  guard parses every `*.sh`.
- Docs cross-references (test classes, `.java` files, `TESTING.md` cases, Markdown links, `#anchor`s) are
  validated; see [`scripts/check-docs.sh`](scripts/check-docs.sh) and
  [`docs/WORKFLOW_WALKTHROUGH.md` §4](docs/WORKFLOW_WALKTHROUGH.md#4-how-each-branch-is-proven-the-golden-trace-suite).
- New behavior gets a `TESTING.md` case and, where it's control flow, a golden-trace test.
- **Dependency-gated tests use the shared `IntegrationGate`.** A test that needs an external dependency
  (SQLite, Node, git, a live model) calls `IntegrationGate.proceed("<dep>", "<label>", available)` instead of
  a bare `if (!available) return;`. By default it self-skips when the dependency is missing; in CI, setting
  `IMINI_REQUIRE_<DEP>=1` makes a missing dependency a hard failure. Each call prints an
  `[integration] <label> (<dep>) ran|skipped` marker that `scripts/integration-coverage.sh` audits. The
  switches today are `IMINI_REQUIRE_PERSISTENCE`, `IMINI_REQUIRE_NODE`, `IMINI_REQUIRE_GIT`, and
  `IMINI_REQUIRE_MODEL`.
- **Git-gated tests build an isolated repo via `GitRepoFixture`** (deterministic local identity, isolated from
  global/system git config) so they pass in a clean checkout, not only where git is pre-configured.
- **Node/MCP-gated tests locate the stub via `McpStubFixture`** (loads `/mcp/stub-server.js` from the test
  classpath, so it resolves regardless of working directory) and gate through `IntegrationGate("node", …)`.
- **JSON/discovery-gated tests probe for a real mapper** via `JsonProbe.realMapperAvailable()` and gate
  through `IntegrationGate("json", …)`, since `McpManager` parses JSON-RPC with `ObjectMapper` (a no-op in
  the offline scaffold). The full switch set is `IMINI_REQUIRE_PERSISTENCE`, `_NODE`, `_GIT`, `_JSON`,
  `_MODEL`.
  These tests run offline against a faithful minimal mapper supplied by the verification scaffold (not
  committed to `src/`, to avoid shadowing real Jackson); see [`docs/OFFLINE_JSON.md`](docs/OFFLINE_JSON.md).
- **Web-search parser tests gate on a real HTML parser** via `HtmlProbe.realParserAvailable()` and
  `IntegrationGate("html", …)` (the no-op jsoup stub can't parse), while the fusion/dedup/cache logic is pure
  and always runs offline; see [`docs/WEB_SEARCH.md`](docs/WEB_SEARCH.md).
- **Live web-search tests gate on the `network` family** (`IMINI_REQUIRE_NETWORK`) so they self-skip offline
  and run in CI; instant-answer JSON parsing gates on `json` like the other discovery tests.
- **Content distillation logic is pure** (`SearchDistiller` split/score/dedup, reusing
  `RetrievalService.lexicalScore`) and unit-tested offline; only page fetching touches the network (injected
  fetcher, null offline). See [`docs/WEB_SEARCH.md`](docs/WEB_SEARCH.md).
- **Untrusted fetched text is scrubbed for prompt-injection** (`SearchSafety.neutralizeInjections` +
  `Redact.scrubPii`) before distilled passages enter the context; domain-trust re-ranking
  (`SearchSafety.applyTrust`) is default-neutral and opt-in via a host penalty list. All pure/offline-tested.
- **Web search records pure per-query metrics** (`WebSearchMetrics`, exposed at `GET /admin/web-search` and a
  log/trace marker) and ships a fixture relevance eval (`WebSearchEval`, fixture-parse gated on `html`).
- **A self-hosted SearXNG engine** (`SearxngEngine`) is config-driven (`agent.web-search.searxng-base-url`,
  absent by default) with pure JSON parsing gated on `json`; the engine set/order is configurable via
  `agent.web-search.engines` and skips unknown/unconfigured engines gracefully.
- If scripts show up non-executable in git after an archive import, run `sh scripts/git-mark-exec.sh` and
  commit.
