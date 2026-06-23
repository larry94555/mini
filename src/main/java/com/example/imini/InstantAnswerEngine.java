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
import java.util.List;

/**
 * Direct, cited answers from free structured sources: the DuckDuckGo Instant Answer API and the Wikipedia
 * REST summary endpoint. Returns at most one high-confidence {@link SearchResult} (sourceEngine
 * {@code "instant"}) which {@link WebSearchService} surfaces ahead of the fused ranked results. Free, no key.
 *
 * <p>The response parsing ({@link #parseDuckDuckGo}, {@link #parseWikipedia}) is pure given an
 * {@link ObjectMapper}, so it is unit-testable from recorded JSON fixtures (gated on a real mapper).
 */
public final class InstantAnswerEngine implements SearchEngine {

  static final String DDG_IA = "https://api.duckduckgo.com/?format=json&no_html=1&skip_disambig=1&q=";
  static final String WIKI_SUMMARY = "https://en.wikipedia.org/api/rest_v1/page/summary/";

  private final HttpClient http;
  private final ObjectMapper mapper = new ObjectMapper();

  public InstantAnswerEngine(HttpClient http) {
    this.http = http;
  }

  @Override
  public String name() {
    return "instant";
  }

  @Override
  public List<SearchResult> search(String query) throws IOException {
    long now = System.currentTimeMillis();
    // 1) DuckDuckGo Instant Answer.
    try {
      String json = fetch(DDG_IA + URLEncoder.encode(query, StandardCharsets.UTF_8));
      List<SearchResult> r = parseDuckDuckGo(mapper, json, now);
      if (!r.isEmpty()) {
        return r;
      }
    } catch (IOException e) {
      // fall through to Wikipedia
    }
    // 2) Wikipedia REST summary (title-cased query path).
    String title = query.trim().replace(' ', '_');
    String json = fetch(WIKI_SUMMARY + URLEncoder.encode(title, StandardCharsets.UTF_8));
    return parseWikipedia(mapper, json, now);
  }

  private String fetch(String url) throws IOException {
    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "Mozilla/5.0 (compatible; mini-agent/0.4; +https://example.invalid)")
        .header("Accept", "application/json")
        .timeout(Duration.ofSeconds(20))
        .GET()
        .build();
    try {
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        throw new IOException("[instant] HTTP " + resp.statusCode());
      }
      return resp.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", e);
    }
  }

  /** Pure: parse a DuckDuckGo Instant Answer payload into at most one confident result. */
  public static List<SearchResult> parseDuckDuckGo(ObjectMapper mapper, String json, long nowMs) {
    try {
      JsonNode root = mapper.readTree(json);
      if (root == null) {
        return List.of();
      }
      String text = textOf(root, "AbstractText");
      if (text.isBlank()) {
        text = textOf(root, "Answer");
      }
      if (text.isBlank()) {
        text = textOf(root, "Definition");
      }
      String url = textOf(root, "AbstractURL");
      if (url.isBlank()) {
        url = textOf(root, "DefinitionURL");
      }
      String heading = textOf(root, "Heading");
      // Confident only when we have both a direct answer and a citable source URL.
      if (!text.isBlank() && !url.isBlank()) {
        String title = heading.isBlank() ? "Instant answer" : heading;
        return List.of(new SearchResult(title, url, text, "instant", nowMs));
      }
      return List.of();
    } catch (Exception e) {
      return List.of();
    }
  }

  /** Pure: parse a Wikipedia REST summary payload into at most one confident (non-disambiguation) result. */
  public static List<SearchResult> parseWikipedia(ObjectMapper mapper, String json, long nowMs) {
    try {
      JsonNode root = mapper.readTree(json);
      if (root == null) {
        return List.of();
      }
      String type = textOf(root, "type");
      if (type.equalsIgnoreCase("disambiguation")) {
        return List.of();
      }
      String extract = textOf(root, "extract");
      String title = textOf(root, "title");
      String url = "";
      JsonNode cu = root.get("content_urls");
      if (cu != null) {
        JsonNode desktop = cu.get("desktop");
        if (desktop != null) {
          url = desktop.get("page") == null ? "" : desktop.get("page").asText();
        }
      }
      if (!extract.isBlank() && !url.isBlank()) {
        return List.of(new SearchResult(title.isBlank() ? "Wikipedia" : title, url, extract, "instant", nowMs));
      }
      return List.of();
    } catch (Exception e) {
      return List.of();
    }
  }

  private static String textOf(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v == null ? "" : v.asText();
  }
}
