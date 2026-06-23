package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline coverage for the pure BM25 ranker: corpus statistics, IDF, term-frequency saturation, and
 * document-length normalization. No network, no LLM.
 */
public class Bm25Test {

  private static List<String> t(String... toks) {
    return List.of(toks);
  }

  @Test
  void corpusStatsCountDocsAvgLengthAndDocFreq() {
    Bm25.Corpus c = Bm25.Corpus.of(List.of(
        t("alpha", "beta"),
        t("alpha", "gamma", "gamma"),
        t("delta")));
    assertEquals(3, c.docCount());
    assertEquals(Double.valueOf(2.0), Double.valueOf(c.avgDocLength())); // (2 + 3 + 1) / 3
    assertEquals(2, c.docFreq().get("alpha")); // appears in 2 docs
    assertEquals(1, c.docFreq().get("gamma")); // distinct-per-doc, so 1 not 2
  }

  @Test
  void rareTermOutranksCommonTerm() {
    // "common" appears in every doc (low IDF); "rare" appears in one (high IDF).
    List<List<String>> corpusDocs = List.of(
        t("common", "rare"),
        t("common", "filler"),
        t("common", "filler"),
        t("common", "filler"));
    Bm25.Corpus c = Bm25.Corpus.of(corpusDocs);
    double rare = Bm25.score(t("rare"), t("common", "rare"), c);
    double common = Bm25.score(t("common"), t("common", "rare"), c);
    assertTrue(rare > common, "rare term contributes more than a corpus-wide common term: rare=" + rare + " common=" + common);
  }

  @Test
  void shorterDocumentWithSameTermCountScoresHigher() {
    // Same tf for "turing" (1), different lengths -> shorter doc wins via length normalization.
    List<String> shortDoc = t("turing", "machine");
    List<String> longDoc = t("turing", "machine", "and", "many", "other", "unrelated", "filler", "words", "here", "too");
    Bm25.Corpus c = Bm25.Corpus.of(List.of(shortDoc, longDoc));
    double sShort = Bm25.score(t("turing"), shortDoc, c);
    double sLong = Bm25.score(t("turing"), longDoc, c);
    assertTrue(sShort > sLong, "shorter doc scores higher for same term count: short=" + sShort + " long=" + sLong);
  }

  @Test
  void termFrequencySaturates() {
    // More occurrences help, but with diminishing returns (k1 saturation): 4x tf < 4x score.
    Bm25.Corpus c = Bm25.Corpus.of(List.of(t("x"), t("y")));
    double one = Bm25.score(t("x"), t("x"), c);
    double four = Bm25.score(t("x"), t("x", "x", "x", "x"), c);
    assertTrue(four > one, "more occurrences score higher");
    assertTrue(four < 4 * one, "but with diminishing returns (saturation): four=" + four + " 4*one=" + (4 * one));
  }

  @Test
  void missingQueryTermScoresZero() {
    Bm25.Corpus c = Bm25.Corpus.of(List.of(t("a", "b"), t("c")));
    assertEquals(Double.valueOf(0.0), Double.valueOf(Bm25.score(t("zzz"), t("a", "b"), c)));
    assertEquals(Double.valueOf(0.0), Double.valueOf(Bm25.score(t("a"), List.of(), c)));
  }

  @Test
  void idfIsHigherForRarerTerms() {
    Bm25.Corpus c = Bm25.Corpus.of(List.of(
        t("common", "rare"), t("common"), t("common"), t("common")));
    assertTrue(c.idf("rare") > c.idf("common"), "rarer term has higher idf");
  }
}
