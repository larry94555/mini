package com.example.imini;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Replaces the old regex tag-stripper with jsoup. It removes boilerplate (scripts, nav, footer,
 * ads), finds the main content region (article / main / [role=main], falling back to body), and
 * returns just the headings, paragraphs, and list items as clean text.
 *
 * For an article page this yields the article body. For a homepage like foxnews.com it yields the
 * headline blocks with most of the chrome removed -- which is exactly what the model needs to pick
 * the top story, without burning context on menus and ad markup.
 */
public final class HtmlExtractor {

    private HtmlExtractor() {}

    public static String mainText(String html, String baseUri) {
        if (html == null || html.isBlank()) return "";
        Document doc = Jsoup.parse(html, baseUri == null ? "" : baseUri);

        // strip obvious boilerplate
        doc.select("script, style, noscript, nav, footer, aside, form, iframe, svg, header").remove();

        Element main = firstNonNull(
                doc.selectFirst("article"),
                doc.selectFirst("main"),
                doc.selectFirst("[role=main]"),
                doc.body());
        if (main == null) return doc.text();

        StringBuilder sb = new StringBuilder();
        String title = doc.title();
        if (title != null && !title.isBlank()) {
            sb.append("TITLE: ").append(title.trim()).append("\n\n");
        }

        Set<String> seen = new LinkedHashSet<>();
        for (Element e : main.select("h1, h2, h3, h4, p, li")) {
            String t = e.text().trim();
            if (t.length() < 3) continue;     // drop empty / nav crumbs
            if (seen.add(t)) sb.append(t).append("\n");
        }

        String out = sb.toString().trim();
        return out.isBlank() ? main.text() : out;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }
}
