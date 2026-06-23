package com.example.imini;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight, in-memory observability for web search — pure aggregation, no I/O, no LLM. Each query records
 * which engines ran vs were skipped (circuit open), whether an instant answer was surfaced, cache hit/miss,
 * result count, and distilled-passage count. Keeps running totals plus a small ring buffer of recent queries
 * for the {@code /admin/web-search} endpoint.
 */
public final class WebSearchMetrics {

  /** Immutable snapshot of one query's behavior. */
  public record Query(String query, List<String> enginesRan, List<String> enginesSkipped,
                      boolean instantSurfaced, boolean cacheHit, int resultCount, int distilledPassages) {
    public Query {
      enginesRan = enginesRan == null ? List.of() : List.copyOf(enginesRan);
      enginesSkipped = enginesSkipped == null ? List.of() : List.copyOf(enginesSkipped);
    }

    /** One-line structured marker for logs/traces. */
    public String marker() {
      return "[web-search] q-len=" + (query == null ? 0 : query.length())
          + " ran=" + enginesRan + " skipped=" + enginesSkipped
          + " instant=" + instantSurfaced + " cache=" + (cacheHit ? "hit" : "miss")
          + " results=" + resultCount + " passages=" + distilledPassages;
    }
  }

  private static final int RING = 25;

  private final AtomicLong totalQueries = new AtomicLong();
  private final AtomicLong cacheHits = new AtomicLong();
  private final AtomicLong instantSurfaced = new AtomicLong();
  private final Map<String, long[]> perEngine = new TreeMap<>(); // name -> [ran, skipped]
  private final Deque<Query> recent = new ArrayDeque<>();
  private volatile List<String> lastAnswered = List.of(); // engines that returned results on the last query

  /** Record which engines actually answered (returned >=1 result) on the most recent query. */
  public void recordAnswered(java.util.Collection<String> engines) {
    lastAnswered = engines == null ? List.of() : List.copyOf(engines);
  }

  public synchronized void record(Query q) {
    totalQueries.incrementAndGet();
    if (q.cacheHit()) {
      cacheHits.incrementAndGet();
    }
    if (q.instantSurfaced()) {
      instantSurfaced.incrementAndGet();
    }
    for (String e : q.enginesRan()) {
      perEngine.computeIfAbsent(e, k -> new long[2])[0]++;
    }
    for (String e : q.enginesSkipped()) {
      perEngine.computeIfAbsent(e, k -> new long[2])[1]++;
    }
    recent.addFirst(q);
    while (recent.size() > RING) {
      recent.removeLast();
    }
  }

  /** A JSON-serializable view for the admin endpoint. */
  public synchronized Map<String, Object> snapshot() {
    Map<String, Object> out = new LinkedHashMap<>();
    long total = totalQueries.get();
    out.put("total_queries", total);
    out.put("cache_hits", cacheHits.get());
    out.put("cache_hit_rate", total == 0 ? 0.0 : round((double) cacheHits.get() / total));
    out.put("instant_surfaced", instantSurfaced.get());
    Map<String, Object> engines = new LinkedHashMap<>();
    for (Map.Entry<String, long[]> e : perEngine.entrySet()) {
      Map<String, Object> v = new LinkedHashMap<>();
      v.put("ran", e.getValue()[0]);
      v.put("skipped", e.getValue()[1]);
      engines.put(e.getKey(), v);
    }
    out.put("engines", engines);
    out.put("last_engines_answered", List.copyOf(lastAnswered));
    List<Map<String, Object>> recentList = new ArrayList<>();
    for (Query q : recent) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("engines_ran", q.enginesRan());
      m.put("engines_skipped", q.enginesSkipped());
      m.put("instant", q.instantSurfaced());
      m.put("cache_hit", q.cacheHit());
      m.put("results", q.resultCount());
      m.put("passages", q.distilledPassages());
      recentList.add(m);
    }
    out.put("recent", recentList);
    return out;
  }

  private static double round(double d) {
    return Math.round(d * 1000.0) / 1000.0;
  }
}
