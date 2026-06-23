package com.example.imini;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure result fusion: merge ranked result lists from several engines into one ranked, deduped list using
 * Reciprocal Rank Fusion (RRF). RRF score for a document is the sum over engines of {@code 1/(k + rank)},
 * which rewards results that appear high across multiple engines without needing score calibration. Dedup is
 * by {@link SearchUrls#canonicalKey}. No I/O, no LLM — fully unit-testable offline.
 */
public final class SearchFusion {

  /** RRF damping constant; 60 is the value from the original Cormack et al. RRF paper. */
  static final int K = 60;

  private SearchFusion() {}

  /** Fuse the per-engine ranked lists (in priority order) into one deduped, RRF-ranked list. */
  public static List<SearchResult> fuse(List<List<SearchResult>> perEngine) {
    Map<String, SearchResult> repr = new LinkedHashMap<>();
    Map<String, Double> score = new LinkedHashMap<>();
    Map<String, Integer> firstSeen = new LinkedHashMap<>();
    int order = 0;

    for (List<SearchResult> list : perEngine) {
      if (list == null) {
        continue;
      }
      int rank = 0;
      for (SearchResult r : list) {
        if (r == null || r.url().isBlank()) {
          continue;
        }
        rank++;
        String key = SearchUrls.canonicalKey(r.url());
        if (key.isBlank()) {
          continue;
        }
        score.merge(key, 1.0 / (K + rank), Double::sum);
        if (!repr.containsKey(key)) {
          repr.put(key, withCleanUrl(r));
          firstSeen.put(key, order++);
        } else {
          // Keep the richer snippet if the incumbent has none.
          SearchResult cur = repr.get(key);
          if (cur.snippet().isBlank() && !r.snippet().isBlank()) {
            repr.put(key, new SearchResult(cur.title(), cur.url(), r.snippet(), cur.sourceEngine(), cur.fetchedAtMs()));
          }
        }
      }
    }

    List<String> keys = new ArrayList<>(repr.keySet());
    keys.sort((a, b) -> {
      int byScore = Double.compare(score.getOrDefault(b, 0.0), score.getOrDefault(a, 0.0));
      return byScore != 0 ? byScore : Integer.compare(firstSeen.get(a), firstSeen.get(b));
    });

    List<SearchResult> out = new ArrayList<>(keys.size());
    for (String k : keys) {
      out.add(repr.get(k));
    }
    return out;
  }

  /** Render a fused list as compact, token-light text with provenance. */
  public static String render(List<SearchResult> results, int limit) {
    if (results == null || results.isEmpty()) {
      return "(no results)";
    }
    List<String> lines = new ArrayList<>();
    int rank = 1;
    for (SearchResult r : results) {
      lines.add(r.render(rank++));
      if (rank > limit) {
        break;
      }
    }
    return String.join("\n\n", lines);
  }

  private static SearchResult withCleanUrl(SearchResult r) {
    String clean = SearchUrls.clean(r.url());
    if (clean.equals(r.url())) {
      return r;
    }
    return new SearchResult(r.title(), clean, r.snippet(), r.sourceEngine(), r.fetchedAtMs());
  }
}
