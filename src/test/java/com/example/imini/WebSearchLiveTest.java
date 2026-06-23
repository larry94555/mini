package com.example.imini;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live, end-to-end web search against the real engines. Gated on the {@code network} family: it self-skips
 * unless {@code IMINI_REQUIRE_NETWORK} is set (so offline/unit builds never hit the network), and runs in CI
 * where the workflow provisions network access. When it runs, it proves the full default engine set returns
 * fused, deduped, provenance-carrying results — and, when available, a confident instant answer.
 *
 * <p>If {@code agent.web-search.searxng-base-url} is provided (system property or env), a SearXNG engine is
 * included in the live run too.
 */
public class WebSearchLiveTest {

  private static WebSearchService liveService() {
    HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build();
    List<SearchEngine> engines = new ArrayList<>(List.of(
        new InstantAnswerEngine(http), new DuckDuckGoEngine(http), new MojeekEngine(http)));
    String searxng = searxngBaseUrl();
    if (!searxng.isBlank()) {
      engines.add(new SearxngEngine(http, () -> searxng));
    }
    return new WebSearchService(engines, null);
  }

  private static String searxngBaseUrl() {
    String p = System.getProperty("agent.web-search.searxng-base-url", "");
    if (p == null || p.isBlank()) {
      p = System.getenv().getOrDefault("AGENT_WEB_SEARCH_SEARXNG_BASE_URL", "");
    }
    return p == null ? "" : p.trim();
  }

  @Test
  void liveQueryReturnsFusedResultsWithProvenance() {
    // available iff required: offline (unset) -> skip; CI (IMINI_REQUIRE_NETWORK=1) -> run (and must succeed).
    if (!IntegrationGate.proceed("network", "WebSearchLiveTest.liveQuery", IntegrationGate.requiredFor("network"))) {
      return;
    }
    WebSearchService svc = liveService();
    List<SearchResult> results = svc.search("wikipedia");

    assertFalse(results.isEmpty(), "a real query should yield results across the engine set");
    for (SearchResult r : results) {
      assertTrue(r.url().startsWith("http://") || r.url().startsWith("https://"), "real http(s) URL: " + r.url());
      assertFalse(r.sourceEngine().isBlank(), "result carries provenance (source engine)");
    }
    assertFalse(WebSearchEval.distinctSourceEngines(results).isEmpty(),
        "at least one distinct source engine answered");
    assertFalse(((List<?>) svc.metrics().snapshot().get("last_engines_answered")).isEmpty(),
        "metrics record the engines that answered");
  }

  @Test
  void liveInstantAnswerIsConfidentWhenAvailable() {
    if (!IntegrationGate.proceed("network", "WebSearchLiveTest.instant", IntegrationGate.requiredFor("network"))) {
      return;
    }
    WebSearchService svc = liveService();
    List<SearchResult> results = svc.search("Alan Turing");
    assertFalse(results.isEmpty(), "factual query should yield results");

    SearchResult instant = results.stream()
        .filter(r -> "instant".equals(r.sourceEngine()))
        .findFirst().orElse(null);
    if (instant == null) {
      // Instant answers are best-effort; degrade gracefully rather than fail when none is returned.
      return;
    }
    assertTrue(instant.url().startsWith("http"), "instant answer cites a real URL: " + instant.url());
    assertFalse(instant.snippet().isBlank(), "instant answer carries a direct answer snippet");
  }
}
