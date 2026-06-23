package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline coverage for the pure web-search logic: URL canonicalization / tracker + redirect stripping,
 * reciprocal-rank fusion + dedup, and the cache codec. No network, no jsoup, no JSON mapper.
 */
public class SearchFusionTest {

  private static SearchResult r(String title, String url, String snippet, String engine) {
    return new SearchResult(title, url, snippet, engine, 1000L);
  }

  @Test
  void unwrapsDuckDuckGoRedirect() {
    String href = "//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage&rut=abc";
    assertEquals("https://example.com/page", SearchUrls.unwrapDuckDuckGo(href));
  }

  @Test
  void cleanStripsTrackingAndWwwAndTrailingSlash() {
    String dirty = "https://www.Example.com/Path/?utm_source=ddg&id=7&fbclid=xyz";
    String clean = SearchUrls.clean(dirty);
    assertTrue(clean.startsWith("https://example.com/Path"), "host lowercased, www dropped: " + clean);
    assertTrue(clean.contains("id=7"), "kept real param: " + clean);
    assertFalse(clean.contains("utm_source"), "dropped utm_source: " + clean);
    assertFalse(clean.contains("fbclid"), "dropped fbclid: " + clean);
  }

  @Test
  void canonicalKeyCollapsesSchemeWwwAndTrailingSlash() {
    String a = SearchUrls.canonicalKey("https://www.example.com/a/");
    String b = SearchUrls.canonicalKey("http://example.com/a");
    assertEquals(a, b, "same page via different scheme/www/slash -> same key");
  }

  @Test
  void fusionDedupsAcrossEnginesByCanonicalUrl() {
    List<SearchResult> ddg = List.of(
        r("A", "https://example.com/a", "snip a", "duckduckgo"),
        r("B", "https://example.com/b", "", "duckduckgo"));
    List<SearchResult> moj = List.of(
        r("A dup", "https://www.example.com/a/", "richer snippet", "mojeek"),
        r("C", "https://example.com/c", "snip c", "mojeek"));

    List<SearchResult> fused = SearchFusion.fuse(List.of(ddg, moj));
    // 3 unique URLs (a collapsed)
    assertEquals(3, fused.size(), "deduped to 3: " + fused);
    // 'a' appears in both engines at rank 1 -> highest RRF score -> ranked first
    assertTrue(fused.get(0).url().contains("example.com/a"), "shared top result ranks first: " + fused.get(0).url());
  }

  @Test
  void fusionKeepsRicherSnippetWhenIncumbentBlank() {
    List<SearchResult> e1 = List.of(r("A", "https://example.com/a", "", "duckduckgo"));
    List<SearchResult> e2 = List.of(r("A", "https://example.com/a", "has snippet", "mojeek"));
    List<SearchResult> fused = SearchFusion.fuse(List.of(e1, e2));
    assertEquals(1, fused.size());
    assertEquals("has snippet", fused.get(0).snippet(), "blank snippet backfilled from other engine");
  }

  @Test
  void fusionSurvivesAnEmptyEngineList() {
    List<SearchResult> only = List.of(r("A", "https://example.com/a", "s", "mojeek"));
    List<SearchResult> fused = SearchFusion.fuse(java.util.Arrays.asList(List.of(), only, null));
    assertEquals(1, fused.size(), "one engine down/empty still yields results");
  }

  @Test
  void normalizeQueryIsStableForCacheKeys() {
    assertEquals("hello world", SearchUrls.normalizeQuery("  Hello   WORLD  "));
  }

  @Test
  void codecRoundTripsResultsWithTabsAndNewlines() {
    List<SearchResult> in = List.of(
        r("Title\twith tab", "https://example.com/x", "line1\nline2", "duckduckgo"),
        r("Plain", "https://example.com/y", "ok", "mojeek"));
    List<SearchResult> out = SearchCodec.decode(SearchCodec.encode(in));
    assertEquals(in, out, "codec round-trips exactly, escaping tabs/newlines");
  }

  @Test
  void renderIsCompactAndCarriesProvenance() {
    String text = SearchFusion.render(List.of(r("T", "https://example.com/a", "snip", "duckduckgo")), 6);
    assertTrue(text.contains("1. T"), "ranked");
    assertTrue(text.contains("[duckduckgo]"), "shows source engine");
    assertTrue(text.contains("https://example.com/a"), "shows url");
  }
}
