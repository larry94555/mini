package com.example.imini;

import java.util.List;
import java.util.Locale;

/**
 * Pure relevance checks for the offline web-search eval — no network, no LLM. Given fused results (or
 * distilled passages) and an expectation, report whether the expectation is met, so a small fixture suite can
 * score the pipeline and flag regressions.
 */
public final class WebSearchEval {

  private WebSearchEval() {}

  /** True if {@code expectedUrl} (by canonical key) appears in the top-{@code n} fused results. */
  public static boolean topNContainsUrl(List<SearchResult> results, String expectedUrl, int n) {
    if (results == null || expectedUrl == null) {
      return false;
    }
    String want = SearchUrls.canonicalKey(expectedUrl);
    int i = 0;
    for (SearchResult r : results) {
      if (i++ >= n) {
        break;
      }
      if (SearchUrls.canonicalKey(r.url()).equals(want)) {
        return true;
      }
    }
    return false;
  }

  /** True if any result in the top-{@code n} is on the given registrable domain (or a sub-domain). */
  public static boolean topNContainsDomain(List<SearchResult> results, String domain, int n) {
    if (results == null || domain == null) {
      return false;
    }
    String d = domain.toLowerCase(Locale.ROOT);
    int i = 0;
    for (SearchResult r : results) {
      if (i++ >= n) {
        break;
      }
      String host = SearchUrls.canonicalKey(r.url());
      int slash = host.indexOf('/');
      if (slash >= 0) {
        host = host.substring(0, slash);
      }
      if (host.equals(d) || host.endsWith("." + d)) {
        return true;
      }
    }
    return false;
  }

  /** True if any distilled passage contains {@code token} (case-insensitive). */
  public static boolean passagesContainToken(List<SearchDistiller.Passage> passages, String token) {
    if (passages == null || token == null) {
      return false;
    }
    String t = token.toLowerCase(Locale.ROOT);
    for (SearchDistiller.Passage p : passages) {
      if (p.text().toLowerCase(Locale.ROOT).contains(t)) {
        return true;
      }
    }
    return false;
  }

  /** The distinct source engines present in a result list, sorted — i.e. which engines actually answered. */
  public static java.util.List<String> distinctSourceEngines(List<SearchResult> results) {
    java.util.TreeSet<String> set = new java.util.TreeSet<>();
    if (results != null) {
      for (SearchResult r : results) {
        if (r != null && r.sourceEngine() != null && !r.sourceEngine().isBlank()) {
          set.add(r.sourceEngine());
        }
      }
    }
    return new java.util.ArrayList<>(set);
  }
}
