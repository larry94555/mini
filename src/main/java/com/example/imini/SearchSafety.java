package com.example.imini;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pure trust &amp; safety helpers for web search — no network, no LLM:
 *
 * <ul>
 *   <li>{@link #neutralizeInjections} strips/neutralizes prompt-injection patterns from untrusted fetched
 *       page text before it becomes distilled context (complements {@link Redact#scrubPii} for secrets);
 *   <li>{@link #applyTrust} stably re-ranks results using a small configurable host penalty list and an
 *       https tie-breaker — a no-op (order unchanged) when no penalties are configured.
 * </ul>
 */
public final class SearchSafety {

  static final String MARKER = "[redacted-instruction]";

  // Conservative, line/clause-oriented injection patterns. They target imperative directives aimed at an
  // assistant, not ordinary prose, to minimize false positives on legitimate page content.
  private static final List<Pattern> INJECTION = List.of(
      Pattern.compile("(?i)\\bignore\\s+(all\\s+)?(the\\s+)?(previous|prior|above|earlier)\\s+(instructions?|prompts?|messages?|context)\\b.*"),
      Pattern.compile("(?i)\\bdisregard\\s+(all\\s+)?(the\\s+)?(previous|prior|above|earlier|system)\\s+(instructions?|prompts?|messages?)\\b.*"),
      Pattern.compile("(?i)\\bforget\\s+(everything|all)\\b.*\\binstructions?\\b.*"),
      Pattern.compile("(?i)\\byou\\s+are\\s+now\\b.*"),
      Pattern.compile("(?i)\\bact\\s+as\\b\\s+(an?\\s+)?(?:dan|jailbreak|unrestricted).*"),
      Pattern.compile("(?i)^\\s*(system|assistant|developer)\\s*:\\s.*"),
      Pattern.compile("(?i)<\\s*/?\\s*(system|assistant|user|tool)\\b[^>]*>"),
      Pattern.compile("<\\|[^>]*\\|>"),
      Pattern.compile("(?i)\\bnew\\s+(system\\s+)?(instructions?|directives?)\\s*:\\s.*"),
      Pattern.compile("(?i)\\boverride\\s+(your\\s+)?(system\\s+)?(instructions?|prompt|rules)\\b.*"));

  private SearchSafety() {}

  /** Pure: neutralize instruction-injection patterns in untrusted text; returns the cleaned text. */
  public static String neutralizeInjections(String text) {
    if (text == null || text.isBlank()) {
      return text == null ? "" : text;
    }
    String out = text;
    for (Pattern p : INJECTION) {
      out = p.matcher(out).replaceAll(MARKER);
    }
    return out;
  }

  /** Whether {@code text} contains a recognized injection pattern (for tests/metrics). */
  public static boolean looksLikeInjection(String text) {
    if (text == null) {
      return false;
    }
    for (Pattern p : INJECTION) {
      if (p.matcher(text).find()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Parse a penalty config string: comma-separated {@code host=penalty} entries (penalty is a positive number
   * subtracted from a host's trust). Hosts match by registrable-suffix (e.g. {@code spam.example} matches
   * {@code www.spam.example}). Bad entries are ignored.
   */
  public static Map<String, Double> parsePenalties(String cfg) {
    Map<String, Double> out = new LinkedHashMap<>();
    if (cfg == null || cfg.isBlank()) {
      return out;
    }
    for (String entry : cfg.split(",")) {
      String e = entry.trim();
      if (e.isEmpty()) {
        continue;
      }
      int eq = e.lastIndexOf('=');
      if (eq <= 0) {
        continue;
      }
      String host = e.substring(0, eq).trim().toLowerCase(Locale.ROOT);
      try {
        out.put(host, Math.abs(Double.parseDouble(e.substring(eq + 1).trim())));
      } catch (NumberFormatException ignore) {
        // skip malformed penalty
      }
    }
    return out;
  }

  /** Pure: a trust delta for a URL — small https bonus minus any configured host penalty. */
  public static double trustDelta(String url, Map<String, Double> penalties) {
    double delta = 0.0;
    String host = hostOf(url);
    if (url != null && url.toLowerCase(Locale.ROOT).startsWith("https://")) {
      delta += 0.001; // tie-breaker only; never reorders distinct RRF ranks on its own
    }
    if (penalties != null && !penalties.isEmpty() && !host.isEmpty()) {
      for (Map.Entry<String, Double> p : penalties.entrySet()) {
        String h = p.getKey();
        if (host.equals(h) || host.endsWith("." + h)) {
          delta -= p.getValue();
          break;
        }
      }
    }
    return delta;
  }

  /**
   * Stably re-rank results by trust delta. Returns the list unchanged when no penalties are configured
   * (default-neutral): the https bonus is a sub-rank tie-breaker only, so ordering is byte-identical unless a
   * penalty list is supplied.
   */
  public static List<SearchResult> applyTrust(List<SearchResult> results, Map<String, Double> penalties) {
    if (results == null || results.isEmpty() || penalties == null || penalties.isEmpty()) {
      return results;
    }
    List<SearchResult> in = new ArrayList<>(results);
    // Stable sort by descending trust delta; equal deltas keep their incoming (RRF) order.
    List<SearchResult> out = new ArrayList<>(in);
    out.sort((a, b) -> Double.compare(trustDelta(b.url(), penalties), trustDelta(a.url(), penalties)));
    return out;
  }

  private static String hostOf(String url) {
    if (url == null) {
      return "";
    }
    try {
      URI u = URI.create(url);
      String h = u.getHost();
      if (h == null) {
        return "";
      }
      h = h.toLowerCase(Locale.ROOT);
      return h.startsWith("www.") ? h.substring(4) : h;
    } catch (Exception e) {
      return "";
    }
  }
}
