package com.example.imini;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline coverage for the content-distillation pipeline: passage splitting, relevance scoring, near-dup
 * removal, and the distill orchestration with a fake fetcher. No network, no LLM, no jsoup.
 */
public class SearchDistillerTest {

  private static String fixture(String name) throws IOException {
    try (InputStream in = SearchDistillerTest.class.getResourceAsStream("/websearch/" + name)) {
      return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void splitsIntoBoundedPassages() throws Exception {
    List<String> passages = SearchDistiller.splitPassages(fixture("page-turing.txt"));
    assertFalse(passages.isEmpty(), "splits into passages");
    for (String p : passages) {
      assertTrue(p.length() <= SearchDistiller.MAX_PASSAGE_CHARS, "passage within cap: " + p.length());
      assertFalse(p.contains("\n"), "whitespace collapsed");
    }
  }

  @Test
  void scoringSurfacesRelevantPassageAndDropsIrrelevant() {
    List<SearchDistiller.Passage> passages = List.of(
        new SearchDistiller.Passage(
            "Alan Turing led Hut 8 at Bletchley Park and devised codebreaking techniques against German ciphers.",
            "https://src/turing"),
        new SearchDistiller.Passage(
            "The cafe down the street serves excellent pastries and fresh coffee every sunny morning.",
            "https://src/cafe"));
    List<SearchDistiller.Passage> best =
        SearchDistiller.rankAndDedup("Turing codebreaking Bletchley", passages, 5);
    assertEquals(1, best.size(), "only the passage sharing query terms is kept: " + best);
    assertTrue(best.get(0).text().contains("Turing"), "on-topic passage kept");
  }

  @Test
  void nearDuplicatePassagesAcrossSourcesAreDeduped() throws Exception {
    // The same Turing sentence appears in two 'sources'; only one should survive.
    String shared = SearchDistiller.splitPassages(fixture("page-turing-dup.txt")).get(0);
    List<SearchDistiller.Passage> passages = List.of(
        new SearchDistiller.Passage(shared, "https://a/"),
        new SearchDistiller.Passage(shared, "https://b/"));
    List<SearchDistiller.Passage> best = SearchDistiller.rankAndDedup("Turing computer science", passages, 5);
    assertEquals(1, best.size(), "near-identical passages across sources collapse to one");
  }

  @Test
  void distillFetchesTopNAndReturnsCitedPassages() throws Exception {
    Map<String, String> pages = new HashMap<>();
    pages.put("https://src/turing", fixture("page-turing.txt"));
    pages.put("https://src/other", "Totally unrelated content about gardening and tomatoes.");
    List<SearchResult> results = List.of(
        new SearchResult("Turing", "https://src/turing", "snip", "duckduckgo", 1L),
        new SearchResult("Other", "https://src/other", "snip", "mojeek", 1L));

    List<SearchDistiller.Passage> out =
        SearchDistiller.distill("Turing Bletchley codebreaking", results, pages::get, 2, 3);
    assertFalse(out.isEmpty(), "distillation returns passages");
    assertEquals("https://src/turing", out.get(0).sourceUrl(), "cited with the source URL");
    assertTrue(SearchDistiller.render(out).contains("— https://src/turing"), "render cites the source");
  }

  @Test
  void distillSelfSkipsWithNullFetcher() {
    List<SearchResult> results = List.of(new SearchResult("T", "https://x/", "s", "duckduckgo", 1L));
    assertTrue(SearchDistiller.distill("q", results, null, 3, 3).isEmpty(), "null fetcher -> no distillation");
  }

  @Test
  void webSearchServiceDistillsWhenEnabled() throws Exception {
    SearchEngine ranked = new SearchEngine() {
      @Override public String name() { return "duckduckgo"; }
      @Override public List<SearchResult> search(String q) {
        return List.of(new SearchResult("Turing", "https://src/turing", "snip", "duckduckgo", 1L));
      }
    };
    WebSearchService svc = new WebSearchService(List.of(ranked), null);
    svc.distill = true;
    String turing = fixture("page-turing.txt");
    svc.pageFetcher = url -> "https://src/turing".equals(url) ? turing : "";

    String text = svc.searchText("Turing Bletchley codebreaking");
    assertTrue(text.contains("•"), "distilled passages rendered: " + text);
    assertTrue(text.contains("— sources —"), "sources section appended");
    assertTrue(text.contains("https://src/turing"), "cited");
  }
}
