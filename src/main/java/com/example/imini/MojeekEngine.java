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
 * Mojeek backend — a second, independent free index (its own crawler, not a DuckDuckGo/Bing reseller), which
 * makes multi-engine fusion meaningfully more robust and diverse. Free; no API key. Parsing ({@link #parse})
 * is pure and unit-testable from recorded HTML fixtures.
 */
public final class MojeekEngine implements SearchEngine {

  static final String SEARCH_URL = "https://www.mojeek.com/search?q=";
  private static final int MAX_RESULTS = 8;

  private final HttpClient http;

  public MojeekEngine(HttpClient http) {
    this.http = http;
  }

  @Override
  public String name() {
    return "mojeek";
  }

  @Override
  public List<SearchResult> search(String query) throws IOException {
    String url = SEARCH_URL + URLEncoder.encode(query, StandardCharsets.UTF_8);
    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
        .header("Accept", "text/html,application/xhtml+xml")
        .header("Accept-Language", "en-US,en;q=0.9")
        .timeout(Duration.ofSeconds(20))
        .GET()
        .build();
    try {
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        throw new IOException("[mojeek] HTTP " + resp.statusCode());
      }
      return parse(resp.body(), url, System.currentTimeMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", e);
    }
  }

  /** Pure: parse Mojeek's results list (each hit is an {@code <li>} with a title anchor and a snippet). */
  public static List<SearchResult> parse(String html, String baseUrl, long nowMs) {
    List<SearchResult> out = new ArrayList<>();
    Document doc = Jsoup.parse(html == null ? "" : html, baseUrl == null ? SEARCH_URL : baseUrl);
    for (Element li : doc.select("ul.results-standard li, li.result")) {
      Element link = li.selectFirst("a.title");
      if (link == null) {
        link = li.selectFirst("h2 a");
      }
      if (link == null) {
        continue;
      }
      String title = link.text().trim();
      String href = link.attr("href").trim();
      if (href.startsWith("//")) {
        href = "https:" + href;
      }
      Element snip = li.selectFirst("p.s");
      if (snip == null) {
        snip = li.selectFirst("p.result-snippet");
      }
      String snippet = snip == null ? "" : snip.text().trim();
      if (!title.isBlank() && !href.isBlank()) {
        out.add(new SearchResult(title, href, snippet, "mojeek", nowMs));
      }
      if (out.size() >= MAX_RESULTS) {
        break;
      }
    }
    return out;
  }
}
