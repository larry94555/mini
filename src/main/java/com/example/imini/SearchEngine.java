package com.example.imini;

import java.io.IOException;
import java.util.List;

/**
 * A free web-search backend. Implementations do their own HTTP + parsing and return structured results in
 * rank order. Keep implementations dependency-light and free (no paid API). The HTTP step should throw
 * {@link IOException} on transport/blocking failures so the caller's circuit breaker can react; parsing
 * should be a pure, separately-testable step (see e.g. {@code DuckDuckGoEngine.parse}).
 */
public interface SearchEngine {

  /** Short stable identifier recorded as result provenance, e.g. {@code "duckduckgo"}. */
  String name();

  /** Run a query and return ranked results (possibly empty). Throws on transport/blocking failures. */
  List<SearchResult> search(String query) throws IOException;
}
