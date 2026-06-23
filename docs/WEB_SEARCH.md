# Web search

`imini`'s `web_search` tool is a **free**, **token-light**, multi-engine search. This note covers how it
works, how to configure it, and how it is tested. It is the first step of ROADMAP Track C.

## How it works

1. **Engines.** A configurable, ordered set of free `SearchEngine` backends (default `duckduckgo,mojeek`).
   Each engine does its own HTTP + parsing and returns structured `SearchResult`s (title, url, snippet,
   sourceEngine, fetchedAt).
2. **Resilience.** Each engine runs behind its own `CircuitBreaker`; a failing/blocked engine is recorded and
   skipped while its breaker is open, so the others still produce results. The DuckDuckGo backend falls back
   from the HTML endpoint to the stabler DDG-Lite layout, detects block/anomaly pages, and retries with
   browser-like `User-Agent`/`Accept-Language` headers.
3. **Fusion + dedup.** Results are merged with Reciprocal Rank Fusion (`SearchFusion`) and deduped by a
   canonical URL key (`SearchUrls` — unwraps DuckDuckGo `uddg=` redirects, strips tracking params like
   `utm_*`/`fbclid`, normalizes host/scheme/trailing slash). A result that ranks highly across multiple
   engines floats to the top.
4. **Provenance + token economy.** The fused list is rendered compactly with each hit's source engine, so the
   model gets citable, low-token output. All ranking/fusion/dedup happens in Java — **no model tokens**.

## Configuration

| Setting | Default | Meaning |
| --- | --- | --- |
| `agent.web-search.engines` | `duckduckgo,mojeek` | Ordered, comma-separated engine names. |
| `agent.web-search.max-results` | `6` | Max fused results returned. |
| `agent.web-search.cache-ttl-seconds` | `0` | Cache TTL in seconds; `0` disables caching. |

## Caching (free + token-saving)

With a positive `cache-ttl-seconds`, fused results are cached keyed by the normalized query: in SQLite
(`web_search_cache`) when persistence is available, with an in-memory fallback otherwise. Within the TTL a
repeated query is served from cache with no network hit and no new tokens. With the default `0`, caching is
disabled and behavior is byte-identical to no cache.

## No paid APIs

Every engine is free and key-less. To add breadth/privacy you can run your own SearXNG and add it to the
engine list (a future Track C step); nothing here calls a paid search API.

## Instant answers (direct, cited)

For factual/lookup queries, an `InstantAnswerEngine` consults two free structured sources and, when it finds a
confident match, returns a single direct answer that `WebSearchService` surfaces **ahead** of the ranked
results (deduped against them by canonical URL):

- the **DuckDuckGo Instant Answer API** (`AbstractText` + `AbstractURL`), confident only when both a direct
  answer and a citable source URL are present;
- the **Wikipedia REST summary** endpoint (`extract` + `content_urls.desktop.page`), skipping disambiguation
  pages.

The result uses the normal `SearchResult` shape with `sourceEngine="instant"`, so it is cited and token-light
like any other hit. The engine sits behind its own circuit breaker and falls back to ranked results when no
confident answer exists. Toggle with `agent.web-search.instant-answers` (default `true`). Response parsing is
pure (`parseDuckDuckGo`/`parseWikipedia`), so it is golden-tested from recorded JSON fixtures (gated on a real
JSON mapper via `IntegrationGate("json", …)`).

## Live integration test

`WebSearchLiveTest` performs a real end-to-end query and asserts non-empty fused results with provenance. It
gates on the `network` family, self-skipping unless `IMINI_REQUIRE_NETWORK` is set; the `Integration tests`
workflow sets it so the live path is exercised in CI.

## Content distillation (cited passages)

For higher answer quality at *lower* token cost, `web_search` can return the few best **cited passages**
instead of just result snippets. When `agent.web-search.distill=true`, `SearchDistiller`:

1. fetches the top `agent.web-search.distill-top-n` results' pages (reusing `HtmlExtractor.mainText`);
2. splits each page's clean text into bounded passages (pure);
3. scores passages against the query with `RetrievalService`'s lexical scorer (BM25-style, **no LLM tokens**);
4. removes near-duplicate passages across sources (token-Jaccard ≥ 0.8, pure);
5. returns the top `agent.web-search.distill-max-passages` passages, each tagged with its source URL.

The tool then emits the distilled passages followed by a compact `— sources —` list for navigation. All
splitting/scoring/dedup is pure and unit-tested offline; only the page fetch touches the network, and the
fetcher is null offline so distillation self-skips. Distillation is **off by default**, so behavior is
unchanged unless enabled.

## Trust & safety

Because distillation feeds untrusted external page text to the model, the pipeline defends against two risks
(both pure, no network, no LLM):

- **Prompt-injection scrubbing (on by default).** Each distilled passage is run through
  `SearchSafety.neutralizeInjections` (which neutralizes directives like "ignore all previous instructions",
  `system:`/`assistant:` role lines, and `<|…|>`/`<system>` tags into a `[redacted-instruction]` marker) and
  the existing `Redact.scrubPii` (secrets/PII), before it enters the context. Toggle with
  `agent.web-search.scrub-injections` (default `true`); the patterns are conservative to avoid mangling
  ordinary prose.
- **Domain-trust re-ranking (default-neutral).** `SearchSafety.applyTrust` stably re-ranks fused results using
  a small configurable host penalty list (`agent.web-search.trust-penalties`, comma-separated
  `host=penalty`, matching sub-domains) with an https tie-breaker. With no penalties configured it is a
  **no-op** (ordering byte-identical), so you opt in by listing the SEO-spam/low-quality hosts you want
  down-ranked.

Result URLs are also normalized at the result level: `SearchUrls.unwrapRedirect` unwraps DuckDuckGo
(`uddg=`) and generic `url=`/`q=` redirect wrappers (Google/Bing/aggregators) and strips tracking params, so
returned/deduped URLs point at the real target.

## Testing

- **Pure logic (offline, always runs):** `SearchFusionTest` covers redirect-unwrapping, tracking-param
  stripping, canonical-URL dedup, reciprocal-rank fusion, the cache codec, and rendering. `WebSearchParseTest`
  covers TTL cache hit/miss and the pure block-detection + probe-interpretation logic.
- **Parser golden tests (gated):** `WebSearchParseTest` parses recorded HTML fixtures under
  `src/test/resources/websearch/` (DuckDuckGo HTML, DDG-Lite, Mojeek). These need a real HTML parser, so they
  gate through `IntegrationGate.proceed("html", …)` (probe: `HtmlProbe.realParserAvailable()`): they self-skip
  under the no-op jsoup stub and run in CI, where the `Integration tests` workflow sets `IMINI_REQUIRE_HTML=1`
  and `scripts/integration-coverage.sh` fails the build if an `html` test skipped.
- **Live search (gated):** any test that hits the network gates on the `network` family
  (`IMINI_REQUIRE_NETWORK`) so it self-skips offline.

See TESTING.md (cases 627-628), CONTRIBUTING.md, and ROADMAP Track C.

## Observability and evals

The pipeline records lightweight, in-memory per-query metrics (pure Java, no LLM tokens, no new dependency):
which engines ran vs were skipped (circuit open), whether an instant answer was surfaced, cache hit/miss,
result count, and distilled-passage count. Aggregates plus a small ring buffer of recent queries are exposed
at the admin endpoint:

```
GET /admin/web-search    # admin-gated, like the other /admin routes
```

It returns `total_queries`, `cache_hits` + `cache_hit_rate`, `instant_surfaced`, a per-engine
`ran`/`skipped` breakdown, and the recent-query list. Each query also emits a one-line
`[web-search] q-len=… ran=[…] skipped=[…] instant=… cache=… results=… passages=…` marker to the logs/trace
path, so a run's web-search behavior is visible without the endpoint.

A small **offline relevance eval** scores the pipeline from recorded fixtures with pure checks
(`WebSearchEval`): does the expected URL/domain appear in the fused top-N, or an expected token in a distilled
passage. The fixture-parse portion gates on a real HTML parser (`IntegrationGate("html", …)`), so it
self-skips under the no-op jsoup stub and runs in CI; the scorers themselves are pure and always run. See
TESTING cases 635-636.
