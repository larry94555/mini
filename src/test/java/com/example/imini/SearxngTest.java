package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SearXNG parsing (gated on a real JSON mapper) + graceful absence and config-driven engine selection
 * (pure, always offline). The parse tests gate through {@code IntegrationGate("json", …)} so they self-skip
 * under the no-op offline mapper and run for real in CI.
 */
public class SearxngTest {

  private static String fixture(String name) throws IOException {
    try (InputStream in = SearxngTest.class.getResourceAsStream("/websearch/" + name)) {
      return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  // ---- pure JSON parsing (gated on a real mapper) ----

  @Test
  void parsesResultsArray() throws Exception {
    if (!IntegrationGate.proceed("json", "SearxngTest.parse", JsonProbe.realMapperAvailable())) return;
    List<SearchResult> r = SearxngEngine.parse(new ObjectMapper(), fixture("searxng.json"), 7L);
    assertEquals(2, r.size(), "two results: " + r);
    assertEquals("Alan Turing - Wikipedia", r.get(0).title());
    assertEquals("https://en.wikipedia.org/wiki/Alan_Turing", r.get(0).url());
    assertEquals("searxng", r.get(0).sourceEngine());
    assertTrue(r.get(0).snippet().toLowerCase().contains("mathematician"), "content -> snippet");
  }

  @Test
  void parsesEmptyResultsToNothing() throws Exception {
    if (!IntegrationGate.proceed("json", "SearxngTest.empty", JsonProbe.realMapperAvailable())) return;
    assertTrue(SearxngEngine.parse(new ObjectMapper(), fixture("searxng-empty.json"), 7L).isEmpty(),
        "empty results array -> no SearchResults");
  }

  // ---- graceful absence (pure) ----

  @Test
  void engineIsAbsentWithoutBaseUrl() throws IOException {
    SearxngEngine blank = new SearxngEngine(HttpClient.newHttpClient(), () -> "");
    assertFalse(blank.configured(), "no base URL -> not configured");
    assertTrue(blank.search("anything").isEmpty(), "unconfigured search returns empty, never throws");

    SearxngEngine set = new SearxngEngine(HttpClient.newHttpClient(), () -> "https://searx.example");
    assertTrue(set.configured(), "base URL -> configured");
  }

  // ---- config-driven engine set (pure, fake engines) ----

  @Test
  void engineSetIsConfigDrivenAndOrdered() {
    SearchEngine ddg = fixed("duckduckgo", "https://e/d");
    SearchEngine moj = fixed("mojeek", "https://e/m");
    WebSearchService svc = new WebSearchService(List.of(ddg, moj), null);
    // Choose a subset + custom order via config.
    svc.setEnginesCsvForTest("mojeek,duckduckgo");
    List<SearchResult> out = svc.search("q");
    assertEquals("mojeek", out.get(0).sourceEngine(), "configured order honored (mojeek first): " + out);
  }

  @Test
  void unknownOrUnconfiguredEngineNamesAreSkippedGracefully() {
    SearchEngine ddg = fixed("duckduckgo", "https://e/d");
    // 'searxng' named but provided as an unconfigured SearxngEngine; 'bogus' is unknown.
    SearxngEngine sx = new SearxngEngine(HttpClient.newHttpClient(), () -> "");
    WebSearchService svc = new WebSearchService(List.of(ddg, sx), null);
    svc.setEnginesCsvForTest("bogus,searxng,duckduckgo");
    List<SearchResult> out = svc.search("q");
    assertEquals(1, out.size(), "only the real, configured engine contributes: " + out);
    assertEquals("duckduckgo", out.get(0).sourceEngine());
  }

  private static SearchEngine fixed(String name, String url) {
    return new SearchEngine() {
      @Override public String name() { return name; }
      @Override public List<SearchResult> search(String query) {
        return List.of(new SearchResult(name + " result", url, "s", name, 1L));
      }
    };
  }
}
