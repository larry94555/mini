package com.example.imini;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, dependency-free serialization for cached {@link SearchResult} lists. One record per line, fields
 * tab-separated, with tabs/newlines/backslashes escaped — so the web-search cache does not depend on a JSON
 * mapper and round-trips identically offline. Unit-testable without any I/O.
 */
public final class SearchCodec {

  private SearchCodec() {}

  public static String encode(List<SearchResult> results) {
    StringBuilder sb = new StringBuilder();
    for (SearchResult r : results) {
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(esc(r.title())).append('\t')
        .append(esc(r.url())).append('\t')
        .append(esc(r.snippet())).append('\t')
        .append(esc(r.sourceEngine())).append('\t')
        .append(r.fetchedAtMs());
    }
    return sb.toString();
  }

  public static List<SearchResult> decode(String payload) {
    List<SearchResult> out = new ArrayList<>();
    if (payload == null || payload.isEmpty()) {
      return out;
    }
    for (String line : payload.split("\n", -1)) {
      if (line.isEmpty()) {
        continue;
      }
      String[] f = line.split("\t", -1);
      if (f.length < 5) {
        continue;
      }
      long ts;
      try {
        ts = Long.parseLong(f[4]);
      } catch (NumberFormatException e) {
        ts = 0L;
      }
      out.add(new SearchResult(unesc(f[0]), unesc(f[1]), unesc(f[2]), unesc(f[3]), ts));
    }
    return out;
  }

  private static String esc(String s) {
    if (s == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\': sb.append("\\\\"); break;
        case '\t': sb.append("\\t"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        default: sb.append(c);
      }
    }
    return sb.toString();
  }

  private static String unesc(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char n = s.charAt(++i);
        switch (n) {
          case '\\': sb.append('\\'); break;
          case 't': sb.append('\t'); break;
          case 'n': sb.append('\n'); break;
          case 'r': sb.append('\r'); break;
          default: sb.append(n);
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
