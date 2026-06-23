package com.example.imini;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure URL helpers used to dedup search results across engines: unwrap engine redirect wrappers
 * (e.g. DuckDuckGo's {@code uddg=}), strip tracking parameters, and compute a canonical key so the same
 * page from two engines collapses to one result. No I/O, no jsoup — fully unit-testable offline.
 */
public final class SearchUrls {

  /** Tracking params dropped during canonicalization. */
  static final Set<String> TRACKING = Set.of(
      "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
      "gclid", "fbclid", "msclkid", "mc_cid", "mc_eid", "igshid", "yclid", "_ga", "ref", "ref_src");

  private SearchUrls() {}

  /** Unwrap a DuckDuckGo redirect href ({@code /l/?...&uddg=ENCODED}) to the real target, else return as-is. */
  public static String unwrapDuckDuckGo(String href) {
    if (href == null) {
      return "";
    }
    int i = href.indexOf("uddg=");
    if (i < 0) {
      return href.startsWith("//") ? "https:" + href : href;
    }
    String enc = href.substring(i + 5);
    int amp = enc.indexOf('&');
    if (amp >= 0) {
      enc = enc.substring(0, amp);
    }
    try {
      return URLDecoder.decode(enc, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return href;
    }
  }

  /**
   * Unwrap common redirect wrappers to the real target URL: DuckDuckGo ({@code uddg=}) and the generic
   * {@code url=}/{@code q=} redirect params used by Google/Bing/news aggregators. Returns the decoded target
   * when it is an absolute http(s) URL, else the input (with a protocol-relative prefix fixed up). Pure.
   */
  public static String unwrapRedirect(String href) {
    if (href == null || href.isBlank()) {
      return "";
    }
    String ddg = unwrapDuckDuckGo(href);
    if (!ddg.equals(href) && (ddg.startsWith("http://") || ddg.startsWith("https://"))) {
      return ddg;
    }
    String candidate = ddg;
    for (String param : new String[] {"url=", "u=", "q=", "target="}) {
      int i = indexOfParam(candidate, param);
      if (i >= 0) {
        String enc = candidate.substring(i + param.length());
        int amp = enc.indexOf('&');
        if (amp >= 0) {
          enc = enc.substring(0, amp);
        }
        try {
          String dec = URLDecoder.decode(enc, StandardCharsets.UTF_8);
          if (dec.startsWith("http://") || dec.startsWith("https://")) {
            return dec;
          }
        } catch (Exception ignore) {
          // fall through to next param
        }
      }
    }
    return candidate.startsWith("//") ? "https:" + candidate : candidate;
  }

  private static int indexOfParam(String url, String param) {
    int q = url.indexOf('?');
    if (q < 0) {
      return -1;
    }
    // match "?param" or "&param"
    int i = url.indexOf("?" + param, q - 0);
    if (i >= 0) {
      return i + 1;
    }
    i = url.indexOf("&" + param, q);
    return i >= 0 ? i + 1 : -1;
  }

  /** A normalized display URL: redirect-unwrapped, tracking-stripped, with sorted remaining query params. */
  public static String clean(String rawUrl) {
    String unwrapped = unwrapRedirect(rawUrl);
    try {
      URI u = URI.create(unwrapped);
      if (u.getScheme() == null || u.getHost() == null) {
        return unwrapped;
      }
      String scheme = u.getScheme().toLowerCase(Locale.ROOT);
      String host = u.getHost().toLowerCase(Locale.ROOT);
      if (host.startsWith("www.")) {
        host = host.substring(4);
      }
      String query = stripTracking(u.getRawQuery());
      StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
      if (u.getPort() != -1 && !(scheme.equals("http") && u.getPort() == 80) && !(scheme.equals("https") && u.getPort() == 443)) {
        sb.append(':').append(u.getPort());
      }
      String path = u.getRawPath() == null ? "" : u.getRawPath();
      if (path.length() > 1 && path.endsWith("/")) {
        path = path.substring(0, path.length() - 1);
      }
      sb.append(path);
      if (!query.isEmpty()) {
        sb.append('?').append(query);
      }
      return sb.toString();
    } catch (Exception e) {
      return unwrapped;
    }
  }

  /** A canonical dedup key (clean URL, lowercased, scheme- and fragment-insensitive). */
  public static String canonicalKey(String rawUrl) {
    String c = clean(rawUrl).toLowerCase(Locale.ROOT);
    int hash = c.indexOf('#');
    if (hash >= 0) {
      c = c.substring(0, hash);
    }
    if (c.startsWith("https://")) {
      c = c.substring(8);
    } else if (c.startsWith("http://")) {
      c = c.substring(7);
    }
    return c;
  }

  private static String stripTracking(String rawQuery) {
    if (rawQuery == null || rawQuery.isEmpty()) {
      return "";
    }
    Map<String, String> kept = new TreeMap<>();
    for (String pair : rawQuery.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      String key = eq >= 0 ? pair.substring(0, eq) : pair;
      if (TRACKING.contains(key.toLowerCase(Locale.ROOT))) {
        continue;
      }
      kept.put(key, eq >= 0 ? pair.substring(eq + 1) : "");
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : kept.entrySet()) {
      if (sb.length() > 0) {
        sb.append('&');
      }
      sb.append(e.getKey());
      if (!e.getValue().isEmpty()) {
        sb.append('=').append(e.getValue());
      }
    }
    return sb.toString();
  }

  /** Normalize a query string for cache keys: trimmed, collapsed whitespace, lowercased. */
  public static String normalizeQuery(String q) {
    if (q == null) {
      return "";
    }
    return String.join(" ", Arrays.stream(q.trim().toLowerCase(Locale.ROOT).split("\\s+"))
        .filter(s -> !s.isEmpty()).toList());
  }

  /** Helper retained for symmetry with callers that expect a list of kept params. */
  static List<String> keptParams(String rawQuery) {
    String s = stripTracking(rawQuery);
    return s.isEmpty() ? List.of() : Arrays.asList(s.split("&"));
  }

  static Map<String, String> emptyMap() {
    return new LinkedHashMap<>();
  }
}
