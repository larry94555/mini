package com.example.imini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A self-hosted <a href="https://searxng.org">SearXNG</a> backend: queries an operator-run instance's JSON
 * API ({@code GET /search?q=…&format=json}) at a configurable base URL and parses its {@code results} array
 * into {@link SearchResult}s (sourceEngine {@code "searxng"}). Free (operator-hosted, no paid API) and
 * token-light. The base URL is read lazily via a {@link Supplier} so configuration injected after construction
 * is honored; when it is blank the engine is gracefully absent (returns no results, never throws).
 *
 * <p>Response parsing ({@link #parse}) is pure given an {@link ObjectMapper}, so it is unit-testable from a
 * recorded JSON fixture (gated on a real mapper).
 */
public final class SearxngEngine implements SearchEngine {

  private static final int MAX_RESULTS = 8;

  private final HttpClient http;
  private final Supplier<String> baseUrl;
  private final ObjectMapper mapper = new ObjectMapper();

  public SearxngEngine(HttpClient http, Supplier<String> baseUrl) {
    this.http = http;
    this.baseUrl = baseUrl;
  }

  @Override
  public String name() {
    return "searxng";
  }

  /** Whether a base URL is configured; used by the caller to skip the engine gracefully when absent. */
  public boolean configured() {
    String b = baseUrl == null ? null : baseUrl.get();
    return b != null && !b.isBlank();
  }

  @Override
  public List<SearchResult> search(String query) throws IOException {
    if (!configured()) {
      return List.of(); // gracefully absent
    }
    String base = baseUrl.get().trim();
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    String url = base + "/search?format=json&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "mini-agent/0.4")
        .header("Accept", "application/json")
        .timeout(Duration.ofSeconds(20))
        .GET()
        .build();
    try {
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        throw new IOException("[searxng] HTTP " + resp.statusCode());
      }
      return parse(mapper, resp.body(), System.currentTimeMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", e);
    }
  }

  /** Pure: parse a SearXNG JSON payload's {@code results} array into ranked {@link SearchResult}s. */
  public static List<SearchResult> parse(ObjectMapper mapper, String json, long nowMs) {
    List<SearchResult> out = new ArrayList<>();
    try {
      JsonNode root = mapper.readTree(json);
      if (root == null) {
        return out;
      }
      JsonNode results = root.get("results");
      if (results == null || !results.isArray()) {
        return out;
      }
      for (JsonNode r : results) {
        String url = textOf(r, "url");
        String title = textOf(r, "title");
        String content = textOf(r, "content");
        if (!title.isBlank() && !url.isBlank()) {
          out.add(new SearchResult(title, url, content, "searxng", nowMs));
        }
        if (out.size() >= MAX_RESULTS) {
          break;
        }
      }
      return out;
    } catch (Exception e) {
      return out;
    }
  }

  private static String textOf(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v == null ? "" : v.asText();
  }
}
