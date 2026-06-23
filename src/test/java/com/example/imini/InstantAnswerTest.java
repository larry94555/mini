package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Instant-answer parsing (gated on a real JSON mapper) + the WebSearchService prepend/dedup integration
 * (pure, always offline). The parse tests gate through {@code IntegrationGate("json", …)} so they self-skip
 * under the no-op offline mapper and run for real in CI; the integration tests use fake engines and need no
 * mapper.
 */
public class InstantAnswerTest {

  private static String fixture(String name) throws IOException {
    try (InputStream in = InstantAnswerTest.class.getResourceAsStream("/websearch/" + name)) {
      return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  // ---- pure JSON parsing (gated on a real mapper) ----

  @Test
  void duckDuckGoInstantParsesConfidentAnswer() throws Exception {
    if (!IntegrationGate.proceed("json", "InstantAnswerTest.ddgInstant", JsonProbe.realMapperAvailable())) return;
    List<SearchResult> r = InstantAnswerEngine.parseDuckDuckGo(new ObjectMapper(), fixture("ddg-instant.json"), 5L);
    assertEquals(1, r.size(), "one confident instant answer: " + r);
    assertEquals("instant", r.get(0).sourceEngine());
    assertEquals("https://en.wikipedia.org/wiki/Java_(programming_language)", r.get(0).url());
    assertTrue(r.get(0).snippet().toLowerCase().contains("object-oriented"), "carries the abstract text");
  }

  @Test
  void duckDuckGoInstantReturnsEmptyWhenNoAbstract() throws Exception {
    if (!IntegrationGate.proceed("json", "InstantAnswerTest.ddgEmpty", JsonProbe.realMapperAvailable())) return;
    assertTrue(InstantAnswerEngine.parseDuckDuckGo(new ObjectMapper(), fixture("ddg-instant-empty.json"), 5L).isEmpty(),
        "no abstract/url -> not confident -> empty");
  }

  @Test
  void wikipediaSummaryParsesConfidentAnswer() throws Exception {
    if (!IntegrationGate.proceed("json", "InstantAnswerTest.wiki", JsonProbe.realMapperAvailable())) return;
    List<SearchResult> r = InstantAnswerEngine.parseWikipedia(new ObjectMapper(), fixture("wiki-summary.json"), 5L);
    assertEquals(1, r.size(), "one confident wiki answer: " + r);
    assertEquals("Alan Turing", r.get(0).title());
    assertEquals("https://en.wikipedia.org/wiki/Alan_Turing", r.get(0).url());
  }

  @Test
  void wikipediaDisambiguationIsNotConfident() throws Exception {
    if (!IntegrationGate.proceed("json", "InstantAnswerTest.wikiDisambig", JsonProbe.realMapperAvailable())) return;
    assertTrue(InstantAnswerEngine.parseWikipedia(new ObjectMapper(), fixture("wiki-disambig.json"), 5L).isEmpty(),
        "disambiguation page -> not confident -> empty");
  }

  // ---- WebSearchService integration (pure: fake engines, no mapper/network) ----

  @Test
  void instantAnswerIsSurfacedAheadOfRankedResults() {
    SearchEngine instant = fixed("instant",
        new SearchResult("Direct", "https://src.example/answer", "the answer", "instant", 1L));
    SearchEngine ranked = fixed("duckduckgo",
        new SearchResult("R1", "https://other.example/1", "r1", "duckduckgo", 1L));
    WebSearchService svc = new WebSearchService(List.of(instant, ranked), null);

    List<SearchResult> out = svc.search("q");
    assertEquals("instant", out.get(0).sourceEngine(), "instant answer ranks first");
    assertEquals(2, out.size(), "instant + one ranked result");
  }

  @Test
  void instantAnswerDedupesAgainstRankedByUrl() {
    SearchEngine instant = fixed("instant",
        new SearchResult("Direct", "https://www.example.com/page/", "answer", "instant", 1L));
    SearchEngine ranked = fixed("duckduckgo",
        new SearchResult("Same", "https://example.com/page", "dup", "duckduckgo", 1L));
    WebSearchService svc = new WebSearchService(List.of(instant, ranked), null);

    List<SearchResult> out = svc.search("q");
    assertEquals(1, out.size(), "same page from instant + ranked collapses to one");
    assertEquals("instant", out.get(0).sourceEngine(), "instant copy kept");
  }

  @Test
  void rankedResultsStillReturnedWhenNoInstantAnswer() {
    SearchEngine instant = fixed("instant"); // returns nothing
    SearchEngine ranked = fixed("duckduckgo",
        new SearchResult("R1", "https://other.example/1", "r1", "duckduckgo", 1L));
    WebSearchService svc = new WebSearchService(List.of(instant, ranked), null);
    List<SearchResult> out = svc.search("q");
    assertEquals(1, out.size());
    assertEquals("duckduckgo", out.get(0).sourceEngine(), "falls back to ranked results");
  }

  private static SearchEngine fixed(String name, SearchResult... results) {
    return new SearchEngine() {
      @Override public String name() { return name; }
      @Override public List<SearchResult> search(String query) { return List.of(results); }
    };
  }
}
