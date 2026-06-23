package com.example.imini;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure <a href="https://en.wikipedia.org/wiki/Okapi_BM25">BM25</a> ranking — no I/O, no LLM. Corpus statistics
 * (document frequencies, document count, average document length) are computed once from the indexed
 * documents; the scoring function then ranks a document for a query using term frequency saturation
 * ({@code k1}) and document-length normalization ({@code b}) with IDF weighting, so rare query terms and
 * shorter on-topic documents are rewarded. Reused by both local memory retrieval and the web-search distiller.
 */
public final class Bm25 {

  public static final double DEFAULT_K1 = 1.2;
  public static final double DEFAULT_B = 0.75;

  private Bm25() {}

  /** Immutable corpus statistics over a set of tokenized documents. */
  public record Corpus(int docCount, double avgDocLength, Map<String, Integer> docFreq) {

    /** Build corpus stats from already-tokenized documents. */
    public static Corpus of(List<List<String>> docs) {
      Map<String, Integer> df = new HashMap<>();
      long totalLen = 0;
      int n = 0;
      if (docs != null) {
        for (List<String> doc : docs) {
          if (doc == null) {
            continue;
          }
          n++;
          totalLen += doc.size();
          for (String term : new LinkedHashSet<>(doc)) { // distinct terms per doc for df
            df.merge(term, 1, Integer::sum);
          }
        }
      }
      double avg = n == 0 ? 0.0 : (double) totalLen / n;
      return new Corpus(n, avg, df);
    }

    /** BM25 IDF for a term (non-negative variant: {@code log(1 + (N - df + 0.5)/(df + 0.5))}). */
    public double idf(String term) {
      int df = docFreq.getOrDefault(term, 0);
      return Math.log(1.0 + (docCount - df + 0.5) / (df + 0.5));
    }
  }

  /** Score {@code docTokens} for {@code queryTokens} against {@code corpus} with default {@code k1}/{@code b}. */
  public static double score(List<String> queryTokens, List<String> docTokens, Corpus corpus) {
    return score(queryTokens, docTokens, corpus, DEFAULT_K1, DEFAULT_B);
  }

  /** Pure BM25 score for one document. */
  public static double score(List<String> queryTokens, List<String> docTokens, Corpus corpus,
                             double k1, double b) {
    if (queryTokens == null || docTokens == null || corpus == null || docTokens.isEmpty()) {
      return 0.0;
    }
    Map<String, Integer> tf = new HashMap<>();
    for (String t : docTokens) {
      tf.merge(t, 1, Integer::sum);
    }
    double len = docTokens.size();
    double avg = corpus.avgDocLength() <= 0 ? len : corpus.avgDocLength();
    double score = 0.0;
    for (String q : new LinkedHashSet<>(queryTokens)) {
      int f = tf.getOrDefault(q, 0);
      if (f == 0) {
        continue;
      }
      double idf = corpus.idf(q);
      double denom = f + k1 * (1.0 - b + b * (len / avg));
      score += idf * (f * (k1 + 1.0)) / denom;
    }
    return score;
  }

  /** Convenience: build a one-shot corpus from candidate token-lists and score one of them. */
  public static double scoreAgainst(List<String> queryTokens, List<String> docTokens,
                                    List<List<String>> corpusDocs) {
    return score(queryTokens, docTokens, Corpus.of(corpusDocs));
  }

  static Set<String> distinct(List<String> tokens) {
    return new LinkedHashSet<>(tokens);
  }
}
