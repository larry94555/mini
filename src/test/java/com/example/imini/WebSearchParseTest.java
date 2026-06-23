package com.example.imini;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden tests for the web-search engine parsers over recorded HTML fixtures, plus the cache and the
 * HtmlProbe gate logic. The jsoup-dependent parse assertions gate through {@code IntegrationGate("html", …)}
 * so they self-skip under the no-op jsoup stub and run for real in CI; the cache and probe-interpret tests
 * are pure and always run.
 */
public class WebSearchParseTest {

  private static String fixture(String name) throws IOException {
    try (InputStream in = WebSearchParseTest.class.getResourceAsStream("/websearch/" + name)) {
      if (in == null) {
        return null;
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void duckDuckGoHtmlLayoutParses() throws Exception {
    if (!IntegrationGate.proceed("html", "WebSearchParseTest.ddgHtml", HtmlProbe.realParserAvailable())) return;
    List<SearchResult> r = DuckDuckGoEngine.parse(fixture("ddg-html.html"), DuckDuckGoEngine.HTML_URL, 1L);
    assertEquals(2, r.size(), "two results: " + r);
    assertEquals("Alpha Title", r.get(0).title());
    assertEquals("https://example.org/alpha", r.get(0).url(), "uddg redirect unwrapped");
    assertEquals("duckduckgo", r.get(0).sourceEngine());
  }

  @Test
  void duckDuckGoLiteLayoutParses() throws Exception {
    if (!IntegrationGate.proceed("html", "WebSearchParseTest.ddgLite", HtmlProbe.realParserAvailable())) return;
    List<SearchResult> r = DuckDuckGoEngine.parse(fixture("ddg-lite.html"), DuckDuckGoEngine.LITE_URL, 1L);
    assertEquals(2, r.size(), "two lite results: " + r);
    assertEquals("https://lite.example/one", r.get(0).url());
  }

  @Test
  void mojeekLayoutParses() throws Exception {
    if (!IntegrationGate.proceed("html", "WebSearchParseTest.mojeek", HtmlProbe.realParserAvailable())) return;
    List<SearchResult> r = MojeekEngine.parse(fixture("mojeek.html"), MojeekEngine.SEARCH_URL, 1L);
    assertEquals(2, r.size(), "two mojeek results: " + r);
    assertEquals("Mojeek X", r.get(0).title());
    assertEquals("https://mojeek.example/x", r.get(0).url());
    assertEquals("https://mojeek.example/y", r.get(1).url(), "protocol-relative href fixed up");
  }

  @Test
  void blockDetectionIsPureAndCatchesAnomalyPage() throws Exception {
    // Pure string check — runs even under the stub.
    assertTrue(DuckDuckGoEngine.looksBlocked(fixtureOrInline("ddg-blocked.html")));
    assertFalse(DuckDuckGoEngine.looksBlocked("<html><body><div class=\"result\">ok</div></body></html>"));
    assertTrue(DuckDuckGoEngine.looksBlocked(""), "empty body counts as blocked/empty");
  }

  @Test
  void htmlProbeInterpretIsPure() {
    assertTrue(HtmlProbe.interpret("hi", "https://e/p"));
    assertFalse(HtmlProbe.interpret(null, null));
    assertFalse(HtmlProbe.interpret("hi", "wrong"));
    assertEquals("IMINI_REQUIRE_HTML", IntegrationGate.envVar("html"));
  }

  @Test
  void cacheHitsWithinTtlAndMissesAfter() {
    CountingEngine engine = new CountingEngine();
    WebSearchService svc = new WebSearchService(List.of(engine), null);
    // enable cache via reflection-free package access
    svc.cacheTtlSeconds = 60;
    svc.nowOverrideMs = 1_000_000L;

    String first = svc.searchText("hello world");
    assertTrue(first.contains("Counted"), "first call hits the engine");
    assertEquals(1, engine.calls, "engine called once");

    // within TTL -> served from cache, engine not called again
    svc.nowOverrideMs = 1_000_000L + 30_000L;
    svc.searchText("hello world");
    assertEquals(1, engine.calls, "cache hit: engine not called again");

    // past TTL -> miss, engine called again
    svc.nowOverrideMs = 1_000_000L + 61_000L;
    svc.searchText("hello world");
    assertEquals(2, engine.calls, "cache expired: engine called again");
  }

  @Test
  void disabledCacheNeverStores() {
    CountingEngine engine = new CountingEngine();
    WebSearchService svc = new WebSearchService(List.of(engine), null); // ttl defaults to 0 = disabled
    svc.searchText("q");
    svc.searchText("q");
    assertEquals(2, engine.calls, "with caching disabled every call hits the engine");
  }

  private static String fixtureOrInline(String name) {
    try {
      String f = fixture(name);
      return f != null ? f : "anomaly.js";
    } catch (Exception e) {
      return "anomaly.js";
    }
  }

  /** A fake engine that counts calls and needs no network/jsoup. */
  static final class CountingEngine implements SearchEngine {
    int calls = 0;
    @Override public String name() { return "counting"; }
    @Override public List<SearchResult> search(String query) {
      calls++;
      return List.of(new SearchResult("Counted " + query, "https://example.com/" + calls, "s", "counting", 1L));
    }
  }
}
