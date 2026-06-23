package com.example.imini;

/**
 * One web-search hit with provenance. {@code sourceEngine} records which engine produced it and
 * {@code fetchedAtMs} when it was retrieved, so results can be cited and cached. Immutable.
 */
public record SearchResult(String title, String url, String snippet, String sourceEngine, long fetchedAtMs) {

  public SearchResult {
    title = title == null ? "" : title.trim();
    url = url == null ? "" : url.trim();
    snippet = snippet == null ? "" : snippet.trim();
    sourceEngine = sourceEngine == null ? "" : sourceEngine;
  }

  /** A compact, token-light rendering with provenance for the model. */
  public String render(int rank) {
    StringBuilder sb = new StringBuilder();
    sb.append(rank).append(". ").append(title).append('\n');
    sb.append("   ").append(url);
    if (!sourceEngine.isBlank()) {
      sb.append("  [").append(sourceEngine).append(']');
    }
    if (!snippet.isBlank()) {
      sb.append('\n').append("   ").append(snippet);
    }
    return sb.toString();
  }
}
