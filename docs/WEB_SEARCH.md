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
