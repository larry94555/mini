# Retrieval ranking

`imini`'s workspace/memory retrieval and the web-search content distiller share one ranking core.

## BM25 (default)

In lexical mode (the default; no embeddings, no LLM tokens), ranking uses a pure
[BM25](https://en.wikipedia.org/wiki/Okapi_BM25) scorer (`Bm25`):

- **Corpus stats** (`Bm25.Corpus.of`) compute document frequencies, document count, and average document
  length from the indexed chunks (for memory search) or the candidate set (for `rankTexts` / distilled
  passages).
- **Scoring** weights each query term by IDF, saturates term frequency via `k1`, and normalizes by document
  length via `b`, so **rare query terms outrank common ones** and a **shorter on-topic document outranks a
  longer one** with the same term count.

Configuration:

| Setting | Default | Meaning |
| --- | --- | --- |
| `retrieval.bm25` | `true` | Use BM25; set `false` to fall back to the legacy tf-log lexical scorer. |
| `retrieval.bm25-k1` | `1.2` | Term-frequency saturation. |
| `retrieval.bm25-b` | `0.75` | Document-length normalization (0 = none, 1 = full). |

The active ranker and its parameters are reported by `RetrievalService.rankerInfo()` and logged at startup
(e.g. `ranker=bm25(k1=1.2,b=0.75)`). Embeddings mode (`retrieval.embeddings=true`) is unchanged and takes
precedence when enabled.

## Reuse in web search

`SearchDistiller.rankAndDedup` ranks distilled web passages with the same BM25 scorer (treating the candidate
passages as the corpus), so improving the ranker lifts both local retrieval and web distillation at once. See
[`docs/WEB_SEARCH.md`](WEB_SEARCH.md) and TESTING cases 641-642.
