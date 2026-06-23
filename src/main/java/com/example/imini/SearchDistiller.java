package com.example.imini;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Turns the top web-search results into the few best <em>cited passages</em>, so the model receives distilled
 * evidence instead of whole pages — higher answer quality at lower token cost. The splitting, scoring, and
 * near-duplicate removal are <strong>pure</strong> (no network, no LLM), reusing {@link RetrievalService}'s
 * lexical scorer; only {@link #distill} touches the page fetcher, which is injected so it can be faked in
 * tests and gated (self-skips offline).
 */
public final class SearchDistiller {

  /** A passage of page text with its source URL for citation. */
  public record Passage(String text, String sourceUrl) {}

  static final int MIN_PASSAGE_CHARS = 80;
  static final int MAX_PASSAGE_CHARS = 600;
  static final double NEAR_DUP_JACCARD = 0.8;

  private SearchDistiller() {}

  /** Pure: split clean page text into passages (paragraph-ish), merging tiny ones and capping length. */
  public static List<String> splitPassages(String text) {
    List<String> out = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return out;
    }
    StringBuilder cur = new StringBuilder();
    for (String para : text.split("\\n\\s*\\n|(?<=[.!?])\\s{2,}")) {
      String p = para.trim().replaceAll("\\s+", " ");
      if (p.isEmpty()) {
        continue;
      }
      if (cur.length() > 0 && cur.length() + 1 + p.length() <= MAX_PASSAGE_CHARS) {
        cur.append(' ').append(p);
      } else {
        if (cur.length() >= MIN_PASSAGE_CHARS) {
          out.add(clip(cur.toString()));
        }
        cur = new StringBuilder(p);
      }
      if (cur.length() >= MAX_PASSAGE_CHARS) {
        out.add(clip(cur.toString()));
        cur = new StringBuilder();
      }
    }
    if (cur.length() >= MIN_PASSAGE_CHARS) {
      out.add(clip(cur.toString()));
    }
    return out;
  }

  /** Pure: score passages against the query, drop near-duplicates, return the top {@code maxPassages}. */
  public static List<Passage> rankAndDedup(String query, List<Passage> passages, int maxPassages) {
    return rankAndDedup(query, passages, maxPassages, true);
  }

  /**
   * As {@link #rankAndDedup(String, List, int)}, with explicit control over the ranker: BM25 (default) treats
   * the candidate passages as the corpus (so rare query terms and shorter on-topic passages win), or the
   * legacy term-frequency-log lexical scorer when {@code useBm25} is false.
   */
  public static List<Passage> rankAndDedup(String query, List<Passage> passages, int maxPassages,
                                           boolean useBm25) {
    List<String> qt = RetrievalService.tokenize(query == null ? "" : query);
    record Scored(Passage p, double score, Set<String> tokens) {}
    List<Scored> scored = new ArrayList<>();

    Bm25.Corpus corpus = null;
    if (useBm25) {
      List<List<String>> docs = new ArrayList<>(passages.size());
      for (Passage p : passages) {
        docs.add(RetrievalService.tokenize(p.text()));
      }
      corpus = Bm25.Corpus.of(docs);
    }
    for (Passage p : passages) {
      List<String> docTokens = RetrievalService.tokenize(p.text());
      double s = useBm25
          ? Bm25.score(qt, docTokens, corpus)
          : RetrievalService.lexicalScore(qt, p.text());
      if (s > 0) {
        scored.add(new Scored(p, s, new HashSet<>(docTokens)));
      }
    }
    scored.sort((a, b) -> Double.compare(b.score(), a.score()));

    List<Scored> kept = new ArrayList<>();
    for (Scored cand : scored) {
      boolean dup = false;
      for (Scored k : kept) {
        if (jaccard(cand.tokens(), k.tokens()) >= NEAR_DUP_JACCARD) {
          dup = true;
          break;
        }
      }
      if (!dup) {
        kept.add(cand);
      }
      if (kept.size() >= maxPassages) {
        break;
      }
    }
    List<Passage> out = new ArrayList<>();
    for (Scored s : kept) {
      out.add(s.p());
    }
    return out;
  }

  /**
   * Fetch the top-{@code topN} results' pages via {@code fetcher} (url -&gt; clean text), split into passages,
   * and return the best {@code maxPassages} cited passages. {@code fetcher} is the only impure part; pass a
   * no-op/null fetcher offline to self-skip.
   */
  public static List<Passage> distill(String query, List<SearchResult> results,
                                      Function<String, String> fetcher, int topN, int maxPassages) {
    return distill(query, results, fetcher, topN, maxPassages, true);
  }

  /**
   * As {@link #distill(String, List, Function, int, int)}, but with explicit control over prompt-injection
   * scrubbing of the fetched passages (default on). When {@code scrubInjections} is true, each passage is run
   * through {@link SearchSafety#neutralizeInjections} and {@link Redact#scrubPii} before ranking, so untrusted
   * page text cannot smuggle instructions or secrets into the distilled context.
   */
  public static List<Passage> distill(String query, List<SearchResult> results,
                                      Function<String, String> fetcher, int topN, int maxPassages,
                                      boolean scrubInjections) {
    List<Passage> all = new ArrayList<>();
    if (fetcher == null || results == null) {
      return all;
    }
    int fetched = 0;
    for (SearchResult r : results) {
      if (fetched >= topN) {
        break;
      }
      fetched++;
      String text;
      try {
        text = fetcher.apply(r.url());
      } catch (Exception e) {
        continue;
      }
      if (text == null || text.isBlank()) {
        continue;
      }
      for (String p : splitPassages(text)) {
        String passage = scrubInjections ? Redact.scrubPii(SearchSafety.neutralizeInjections(p)) : p;
        all.add(new Passage(passage, r.url()));
      }
    }
    return rankAndDedup(query, all, maxPassages);
  }

  /** Render distilled passages as compact, cited evidence. */
  public static String render(List<Passage> passages) {
    if (passages == null || passages.isEmpty()) {
      return "";
    }
    List<String> lines = new ArrayList<>();
    for (Passage p : passages) {
      lines.add("• " + p.text() + "\n  — " + p.sourceUrl());
    }
    return String.join("\n\n", lines);
  }

  private static double jaccard(Set<String> a, Set<String> b) {
    if (a.isEmpty() && b.isEmpty()) {
      return 1.0;
    }
    int inter = 0;
    for (String t : a) {
      if (b.contains(t)) {
        inter++;
      }
    }
    int union = a.size() + b.size() - inter;
    return union == 0 ? 0.0 : (double) inter / union;
  }

  private static String clip(String s) {
    return s.length() <= MAX_PASSAGE_CHARS ? s : s.substring(0, MAX_PASSAGE_CHARS).trim();
  }
}
