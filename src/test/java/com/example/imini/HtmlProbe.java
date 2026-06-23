package com.example.imini;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * Detects at runtime whether a <em>real</em> HTML parser (jsoup) is present, as opposed to the no-op jsoup
 * stub used in the offline compile scaffold (whose {@code parse}/{@code select} return empty). Web-search
 * parsing tests gate on this so they self-skip when only the stub is present and run for real in CI.
 *
 * <p>The interpretation step ({@link #interpret}) is pure, so the gate logic is unit-testable without jsoup.
 */
final class HtmlProbe {

  static final String PROBE_HTML = "<html><body><a class=\"x\" href=\"https://e/p\">hi</a></body></html>";

  private HtmlProbe() {}

  /** True only when jsoup actually parses the probe HTML and a selector finds the expected element. */
  static boolean realParserAvailable() {
    try {
      Document doc = Jsoup.parse(PROBE_HTML);
      var el = doc.selectFirst("a.x");
      return interpret(el == null ? null : el.text(), el == null ? null : el.attr("href"));
    } catch (Throwable t) {
      return false;
    }
  }

  /** Pure: did the parse recover the expected text + href? (Unit-tested without jsoup.) */
  static boolean interpret(String text, String href) {
    return "hi".equals(text) && "https://e/p".equals(href);
  }
}
