package com.example.imini;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline coverage for Track C observability + the fixture relevance eval. The metrics aggregation, the
 * service recording (with fake engines), and the pure eval scorers always run; the fixture-parse eval gates
 * on a real HTML parser via {@code IntegrationGate("html", …)}.
 */
public class WebSearchObservabilityTest {

  private static String fixture(String name) throws IOException {
    try (InputStream in = WebSearchObservabilityTest.class.getResourceAsStream("/websearch/" + name)) {
      return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  // ---- pure metrics aggregation ----

  @Test
  void metricsAggregateRunsSkipsCacheAndInstant() {
    WebSearchMetrics m = new WebSearchMetrics();
    m.record(new WebSearchMetrics.Query("a", List.of("duckduckgo", "mojeek"), List.of(), true, false, 5, 2));
    m.record(new WebSearchMetrics.Query("a", List.of(), List.of(), false, true, 5, 0));
    m.record(new WebSearchMetrics.Query("b", List.of("duckduckgo"), List.of("mojeek"), false, false, 3, 0));
    Map<String, Object> snap = m.snapshot();
    assertEquals(3L, snap.get("total_queries"));
    assertEquals(1L, snap.get("cache_hits"));
    assertEquals(1L, snap.get("instant_surfaced"));
    @SuppressWarnings("unchecked")
    Map<String, Object> engines = (Map<String, Object>) snap.get("engines");
    @SuppressWarnings("unchecked")
    Map<String, Object> ddg = (Map<String, Object>) engines.get("duckduckgo");
    assertEquals(2L, ddg.get("ran"));
    @SuppressWarnings("unchecked")
    Map<String, Object> moj = (Map<String, Object>) engines.get("mojeek");
    assertEquals(1L, moj.get("ran"));
    assertEquals(1L, moj.get("skipped"));
  }

  @Test
  void markerSummarizesQuery() {
    String marker = new WebSearchMetrics.Query("q", List.of("duckduckgo"), List.of("mojeek"), true, false, 4, 2).marker();
    assertTrue(marker.contains("ran=[duckduckgo]"));
    assertTrue(marker.contains("skipped=[mojeek]"));
    assertTrue(marker.contains("instant=true"));
    assertTrue(marker.contains("cache=miss"));
    assertTrue(marker.contains("results=4"));
    assertTrue(marker.contains("passages=2"));
  }

  // ---- service records metrics (fake engines, no network) ----

  @Test
  void serviceRecordsPerQueryMetrics() {
    SearchEngine ddg = new SearchEngine() {
      @Override public String name() { return "duckduckgo"; }
      @Override public List<SearchResult> search(String q) {
        return List.of(new SearchResult("R", "https://e/1", "s", "duckduckgo", 1L));
      }
    };
    WebSearchService svc = new WebSearchService(List.of(ddg), null);
    svc.search("hello");
    Map<String, Object> snap = svc.metrics().snapshot();
    assertEquals(1L, snap.get("total_queries"));
    @SuppressWarnings("unchecked")
    Map<String, Object> engines = (Map<String, Object>) snap.get("engines");
    assertTrue(engines.containsKey("duckduckgo"), "engine recorded as ran: " + engines);
  }

  // ---- pure relevance eval scorers ----

  @Test
  void evalScorersCheckUrlDomainAndToken() {
    List<SearchResult> results = List.of(
        new SearchResult("A", "https://www.example.org/alpha/", "s", "duckduckgo", 1L),
        new SearchResult("B", "https://other.example/b", "s", "mojeek", 1L));
    assertTrue(WebSearchEval.topNContainsUrl(results, "http://example.org/alpha", 5), "canonical URL match");
    assertTrue(WebSearchEval.topNContainsDomain(results, "example.org", 5), "domain match");
    assertFalse(WebSearchEval.topNContainsDomain(results, "absent.example", 5), "absent domain");
    List<SearchDistiller.Passage> passages = List.of(new SearchDistiller.Passage("Turing machine", "https://x"));
    assertTrue(WebSearchEval.passagesContainToken(passages, "turing"), "token match");
    assertFalse(WebSearchEval.passagesContainToken(passages, "newton"), "absent token");
  }

  // ---- fixture relevance eval (gated on a real HTML parser) ----

  @Test
  void fusedTopNContainsExpectedUrlFromFixtures() throws Exception {
    if (!IntegrationGate.proceed("html", "WebSearchObservabilityTest.eval", HtmlProbe.realParserAvailable())) return;
    List<SearchResult> ddg = DuckDuckGoEngine.parse(fixture("ddg-html.html"), DuckDuckGoEngine.HTML_URL, 1L);
    List<SearchResult> moj = MojeekEngine.parse(fixture("mojeek.html"), MojeekEngine.SEARCH_URL, 1L);
    List<SearchResult> fused = SearchFusion.fuse(List.of(ddg, moj));
    // The DDG HTML fixture's first result is example.org/alpha — it should be in the fused top-N.
    assertTrue(WebSearchEval.topNContainsUrl(fused, "https://example.org/alpha", 5),
        "expected result present in fused top-N: " + fused);
  }
}
