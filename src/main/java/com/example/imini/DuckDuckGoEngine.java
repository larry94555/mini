package com.example.imini;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

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

/**
 * DuckDuckGo backend, hardened: it tries the HTML endpoint, and on a block/empty/transport failure falls
 * back to the simpler, stabler DDG-Lite endpoint, with a couple of retries and browser-like headers. The
 * parsing step ({@link #parse}) is pure and handles BOTH layouts, so it is unit-testable from recorded HTML
 * fixtures without any network. Free; no API key.
 */
public final class DuckDuckGoEngine implements SearchEngine {

  static final String HTML_URL = "https://html.duckduckgo.com/html/?q=";
  static final String LITE_URL = "https://lite.duckduckgo.com/lite/?q=";
  private static final int MAX_RESULTS = 8;

  private final HttpClient http;
  private final int retries;

  public DuckDuckGoEngine(HttpClient http) {
    this(http, 2);
  }

  public DuckDuckGoEngine(HttpClient http, int retries) {
    this.http = http;
    this.retries = Math.max(1, retries);
  }

  @Override
  public String name() {
    return "duckduckgo";
  }

  @Override
  public List<SearchResult> search(String query) throws IOException {
    String enc = URLEncoder.encode(query, StandardCharsets.UTF_8);
    IOException last = null;
    for (String base : new String[] {HTML_URL, LITE_URL}) {
      for (int attempt = 1; attempt <= retries; attempt++) {
        try {
          String body = fetch(base + enc);
          if (looksBlocked(body)) {
            last = new IOException("[duckduckgo] blocked/anomaly page");
            break; // try the next endpoint
          }
          List<SearchResult> results = parse(body, base, System.currentTimeMillis());
          if (!results.isEmpty()) {
            return results;
          }
        } catch (IOException e) {
          last = e;
          sleep(150L * attempt); // simple backoff
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted", e);
        }
      }
    }
    if (last != null) {
      throw last;
    }
    return List.of();
  }

  private String fetch(String url) throws IOException, InterruptedException {
    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
        .header("Accept", "text/html,application/xhtml+xml")
        .header("Accept-Language", "en-US,en;q=0.9")
        .timeout(Duration.ofSeconds(20))
        .GET()
        .build();
    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2) {
      throw new IOException("[duckduckgo] HTTP " + resp.statusCode());
    }
    return resp.body();
  }

  /** Pure: detect a block / anomaly / CAPTCHA page so the caller can fall back instead of returning empty. */
  public static boolean looksBlocked(String html) {
    if (html == null || html.isBlank()) {
      return true;
    }
    String h = html.toLowerCase();
    return h.contains("if this error persists")
        || h.contains("challenge-platform")
        || h.contains("anomaly.js")
        || h.contains("detected unusual")
        || h.contains("/sorry/");
  }

  /** Pure: parse either the html.duckduckgo.com result layout or the lite.duckduckgo.com table layout. */
  public static List<SearchResult> parse(String html, String baseUrl, long nowMs) {
    List<SearchResult> out = new ArrayList<>();
    Document doc = Jsoup.parse(html == null ? "" : html, baseUrl == null ? HTML_URL : baseUrl);

    // Layout A: full HTML endpoint.
    for (Element result : doc.select("div.result")) {
      Element link = result.selectFirst("a.result__a");
      if (link == null) {
        continue;
      }
      String title = link.text().trim();
      String href = SearchUrls.unwrapDuckDuckGo(link.attr("href"));
      Element snip = result.selectFirst(".result__snippet");
      String snippet = snip == null ? "" : snip.text().trim();
      if (!title.isBlank() && !href.isBlank()) {
        out.add(new SearchResult(title, href, snippet, "duckduckgo", nowMs));
      }
      if (out.size() >= MAX_RESULTS) {
        return out;
      }
    }
    if (!out.isEmpty()) {
      return out;
    }

    // Layout B: DDG-Lite (a table of rows; result links carry class "result-link").
    for (Element link : doc.select("a.result-link")) {
      String title = link.text().trim();
      String href = SearchUrls.unwrapDuckDuckGo(link.attr("href"));
      // The snippet on lite is in a sibling row cell with class "result-snippet".
      String snippet = "";
      Element row = link.parent();
      Element table = row == null ? null : row.parent();
      if (table != null) {
        Element snip = table.selectFirst("td.result-snippet");
        if (snip != null) {
          snippet = snip.text().trim();
        }
      }
      if (!title.isBlank() && !href.isBlank()) {
        out.add(new SearchResult(title, href, snippet, "duckduckgo", nowMs));
      }
      if (out.size() >= MAX_RESULTS) {
        return out;
      }
    }
    return out;
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
