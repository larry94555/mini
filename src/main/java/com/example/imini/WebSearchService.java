package com.example.imini;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs an ordered, configurable set of free {@link SearchEngine}s behind per-engine {@link CircuitBreaker}s,
 * fuses their results ({@link SearchFusion}, RRF + canonical-URL dedup), and returns a single ranked list
 * with provenance — so one engine being down or blocked never yields empty results. Optionally caches fused
 * results (TTL), in SQLite via {@link Database} when available with an in-memory fallback, so repeated
 * queries cost neither network nor tokens. All fusion/dedup/ranking happens here in Java (no LLM tokens).
 *
 * <p>Free only: no paid APIs. Engines are selected by {@code agent.web-search.engines}.
 */
@Component
public class WebSearchService {

  private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

  private final List<SearchEngine> engines;
  private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
  private final Database db; // may be null / unavailable
  private final Map<String, CacheEntry> memCache = new ConcurrentHashMap<>();

  @Value("${agent.web-search.engines:duckduckgo,mojeek,searxng}")
  private String enginesCsv = "duckduckgo,mojeek,searxng";
  @Value("${agent.web-search.max-results:6}")
  private int maxResults = 6;
  @Value("${agent.web-search.cache-ttl-seconds:0}")
  long cacheTtlSeconds = 0; // 0 disables caching (byte-identical to no cache)
  @Value("${agent.web-search.instant-answers:true}")
  boolean instantAnswers = true;
  @Value("${agent.web-search.distill:false}")
  boolean distill = false;
  @Value("${agent.web-search.distill-top-n:3}")
  int distillTopN = 3;
  @Value("${agent.web-search.distill-max-passages:3}")
  int distillMaxPassages = 3;
  @Value("${agent.web-search.scrub-injections:true}")
  boolean scrubInjections = true;
  @Value("${agent.web-search.trust-penalties:}")
  String trustPenaltiesCsv = "";
  @Value("${agent.web-search.searxng-base-url:}")
  String searxngBaseUrl = "";

  /** Page fetcher (url -> clean text) for distillation; null offline so distillation self-skips. */
  java.util.function.Function<String, String> pageFetcher;

  private final WebSearchMetrics metrics = new WebSearchMetrics();
  // Last-query components stashed by searchInternal so the public entry points can record one accurate metric.
  private List<String> lastRan = List.of();
  private List<String> lastSkipped = List.of();
  private boolean lastInstant = false;
  private boolean lastCacheHit = false;

  /** Observability snapshot for the /admin/web-search endpoint. */
  public WebSearchMetrics metrics() {
    return metrics;
  }

  private void recordMetric(String query, int resultCount, int passages) {
    WebSearchMetrics.Query q = new WebSearchMetrics.Query(
        query, lastRan, lastSkipped, lastInstant, lastCacheHit, resultCount, passages);
    metrics.record(q);
    log.info("{}", q.marker()); // emit to logs/trace path (Alt 1)
  }

  /** Fused, deduped, ranked results across the enabled engines (with caching when enabled). */
  public List<SearchResult> search(String query) {
    List<SearchResult> fused = searchInternal(query);
    recordMetric(query, fused.size(), 0);
    return fused;
  }

  /** Settable clock for tests. */
  long nowOverrideMs = -1;

  @Autowired
  public WebSearchService(Database db) {
    this.db = db;
    HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build();
    this.engines = List.of(new InstantAnswerEngine(http), new DuckDuckGoEngine(http), new MojeekEngine(http),
        new SearxngEngine(http, () -> searxngBaseUrl));
    // Real page fetcher for distillation: fetch + extract main text (reusing HtmlExtractor).
    this.pageFetcher = url -> {
      try {
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
            .header("User-Agent", "Mozilla/5.0 (compatible; mini-agent/0.4)")
            .header("Accept", "text/html,application/xhtml+xml")
            .timeout(java.time.Duration.ofSeconds(20))
            .GET().build();
        java.net.http.HttpResponse<String> resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
          return "";
        }
        return HtmlExtractor.mainText(resp.body(), url);
      } catch (Exception e) {
        return "";
      }
    };
  }

  /** Test constructor: explicit engines + database, no real HTTP. */
  WebSearchService(List<SearchEngine> engines, Database db) {
    this.engines = List.copyOf(engines);
    this.db = db;
  }

  /** Test hook: override the configured engine order/subset. */
  void setEnginesCsvForTest(String csv) {
    this.enginesCsv = csv;
  }

  private long now() {
    return nowOverrideMs >= 0 ? nowOverrideMs : System.currentTimeMillis();
  }

  private List<SearchEngine> enabledEngines() {
    Map<String, SearchEngine> byName = new LinkedHashMap<>();
    for (SearchEngine e : engines) {
      if ("instant".equalsIgnoreCase(e.name())) {
        continue; // instant is surfaced separately, not fused
      }
      // Gracefully omit an engine that has no configuration (e.g. SearXNG with no base URL).
      if (e instanceof SearxngEngine sx && !sx.configured()) {
        continue;
      }
      byName.put(e.name().toLowerCase(Locale.ROOT), e);
    }
    if (enginesCsv == null || enginesCsv.isBlank()) {
      return new ArrayList<>(byName.values());
    }
    List<SearchEngine> out = new ArrayList<>();
    for (String s : enginesCsv.split(",")) {
      SearchEngine e = byName.get(s.trim().toLowerCase(Locale.ROOT));
      if (e != null) {
        out.add(e);
      }
    }
    return out.isEmpty() ? new ArrayList<>(byName.values()) : out;
  }

  /** The special "instant" engine (direct cited answers), if configured; surfaced ahead of ranked results. */
  private SearchEngine instantEngine() {
    if (!instantAnswers) {
      return null;
    }
    for (SearchEngine e : engines) {
      if ("instant".equalsIgnoreCase(e.name())) {
        return e;
      }
    }
    return null;
  }

  private CircuitBreaker breakerFor(String name) {
    return breakers.computeIfAbsent(name, n -> new CircuitBreaker("web:" + n, 3, 30_000L));
  }

  /** Fused, deduped, ranked results across the enabled engines (with caching when enabled). */
  private List<SearchResult> searchInternal(String query) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    String key = SearchUrls.normalizeQuery(query);

    List<SearchResult> cached = cacheGet(key);
    if (cached != null) {
      lastRan = List.of();
      lastSkipped = List.of();
      lastInstant = false;
      lastCacheHit = true;
      return cached;
    }
    lastCacheHit = false;

    List<List<SearchResult>> perEngine = new ArrayList<>();
    List<String> ran = new ArrayList<>();
    List<String> skipped = new ArrayList<>();

    // Direct cited answer first (if available + confident), behind its own circuit breaker.
    List<SearchResult> instant = List.of();
    SearchEngine ia = instantEngine();
    if (ia != null) {
      CircuitBreaker cb = breakerFor(ia.name());
      if (cb.allowCall()) {
        try {
          List<SearchResult> r = ia.search(query);
          cb.recordSuccess();
          ran.add(ia.name());
          if (r != null && !r.isEmpty()) {
            instant = r;
          }
        } catch (Exception e) {
          cb.recordFailure();
          log.info("[web-search] instant answer failed: {}", e.getMessage());
        }
      } else {
        skipped.add(ia.name());
      }
    }

    for (SearchEngine engine : enabledEngines()) {
      CircuitBreaker cb = breakerFor(engine.name());
      if (!cb.allowCall()) {
        log.info("[web-search] skipping {} (circuit open)", engine.name());
        skipped.add(engine.name());
        continue;
      }
      try {
        List<SearchResult> r = engine.search(query);
        cb.recordSuccess();
        ran.add(engine.name());
        if (r != null && !r.isEmpty()) {
          perEngine.add(r);
        }
      } catch (Exception e) {
        cb.recordFailure();
        log.info("[web-search] engine {} failed: {}", engine.name(), e.getMessage());
      }
    }

    List<SearchResult> ranked = SearchFusion.fuse(perEngine);
    // Trust re-rank (default-neutral: a no-op unless agent.web-search.trust-penalties is configured).
    ranked = SearchSafety.applyTrust(ranked, SearchSafety.parsePenalties(trustPenaltiesCsv));
    // Prepend the instant answer, deduped against the ranked list by canonical URL.
    List<SearchResult> combined = new ArrayList<>();
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (SearchResult r : instant) {
      if (seen.add(SearchUrls.canonicalKey(r.url()))) {
        combined.add(r);
      }
    }
    for (SearchResult r : ranked) {
      if (seen.add(SearchUrls.canonicalKey(r.url()))) {
        combined.add(r);
      }
    }
    List<SearchResult> fused = combined;
    if (fused.size() > maxResults) {
      fused = new ArrayList<>(fused.subList(0, maxResults));
    }
    if (!fused.isEmpty()) {
      cachePut(key, fused);
    }
    lastRan = ran;
    lastSkipped = skipped;
    lastInstant = !instant.isEmpty();
    metrics.recordAnswered(WebSearchEval.distinctSourceEngines(fused));
    return fused;
  }

  /** Compact, token-light text rendering with provenance (what the tool returns to the model). */
  public String searchText(String query) {
    List<SearchResult> results = searchInternal(query);
    int passages = 0;
    String body;
    if (distill && pageFetcher != null && !results.isEmpty()) {
      List<SearchDistiller.Passage> ps =
          SearchDistiller.distill(query, results, pageFetcher, distillTopN, distillMaxPassages, scrubInjections);
      passages = ps.size();
      String distilled = SearchDistiller.render(ps);
      body = distilled.isBlank()
          ? SearchFusion.render(results, maxResults)
          : distilled + "\n\n— sources —\n" + SearchFusion.render(results, maxResults);
    } else {
      body = SearchFusion.render(results, maxResults);
    }
    recordMetric(query, results.size(), passages);
    return body;
  }

  // ----- caching (TTL); SQLite via Database with in-memory fallback; disabled when ttl <= 0 -----

  private boolean cacheEnabled() {
    return cacheTtlSeconds > 0;
  }

  List<SearchResult> cacheGet(String key) {
    if (!cacheEnabled()) {
      return null;
    }
    long ttlMs = cacheTtlSeconds * 1000L;
    long now = now();
    if (db != null && db.available()) {
      List<CacheEntry> rows = db.query(
          "SELECT results, cached_at FROM web_search_cache WHERE q_key = ?",
          rs -> new CacheEntry(rs.getString(1), rs.getLong(2)), key);
      if (!rows.isEmpty() && now - rows.get(0).cachedAt <= ttlMs) {
        return SearchCodec.decode(rows.get(0).payload);
      }
      return null;
    }
    CacheEntry e = memCache.get(key);
    if (e != null && now - e.cachedAt <= ttlMs) {
      return SearchCodec.decode(e.payload);
    }
    return null;
  }

  void cachePut(String key, List<SearchResult> results) {
    if (!cacheEnabled()) {
      return;
    }
    String payload = SearchCodec.encode(results);
    long now = now();
    if (db != null && db.available()) {
      db.update("INSERT INTO web_search_cache(q_key, results, cached_at) VALUES(?,?,?) "
          + "ON CONFLICT(q_key) DO UPDATE SET results=excluded.results, cached_at=excluded.cached_at",
          key, payload, now);
    } else {
      memCache.put(key, new CacheEntry(payload, now));
    }
  }

  private static final class CacheEntry {
    final String payload;
    final long cachedAt;
    CacheEntry(String payload, long cachedAt) { this.payload = payload; this.cachedAt = cachedAt; }
  }
}
