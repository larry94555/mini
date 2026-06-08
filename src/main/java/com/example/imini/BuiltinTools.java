package com.example.imini;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the individual Tool instances. Separated from ToolRegistry so the SubAgent can grab just
 * the web tools without pulling in the registry (which depends on the SubAgent -- separating these
 * avoids a dependency cycle).
 *
 *   all()        -> the tools the MAIN agent uses (file, shell, web_fetch). NOT web_search:
 *                   open-ended searching is delegated to the sub-agent on purpose.
 *   webFetch()   -> jsoup-based page fetch + main-article extraction
 *   webSearch()  -> DuckDuckGo HTML search, parsed with jsoup (given only to the sub-agent)
 */
@Component
public class BuiltinTools {

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)   // CNN etc. redirect; without this the body is empty
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Tools available to the main agent. */
    public List<Tool> all() {
        return List.of(readFile(), listDir(), writeFile(), runCommand(), webFetch());
    }

    // ---------------------------------------------------------------------
    // File + shell tools
    // ---------------------------------------------------------------------

    public Tool readFile() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", strProp("Path to the text file to read."));
        return new Tool("read_file", "Read a UTF-8 text file from disk.",
                schema(props, "path"), false, args -> {
            try {
                return truncate(Files.readString(Path.of(str(args, "path"))), 6000);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    public Tool listDir() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", strProp("Directory to list. Defaults to current directory."));
        return new Tool("list_dir", "List the entries in a directory.",
                schema(props), false, args -> {
            try {
                String p = args.containsKey("path") ? str(args, "path") : ".";
                try (var stream = Files.list(Path.of(p))) {
                    String out = stream
                            .map(x -> (Files.isDirectory(x) ? "[dir]  " : "[file] ") + x.getFileName())
                            .sorted()
                            .collect(Collectors.joining("\n"));
                    return out.isBlank() ? "(empty)" : out;
                }
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    public Tool writeFile() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", strProp("Path to create or overwrite."));
        props.put("content", strProp("Full UTF-8 content to write."));
        return new Tool("write_file", "Create or overwrite a text file. Mutating: requires approval.",
                schema(props, "path", "content"), true, args -> {
            try {
                Path p = Path.of(str(args, "path"));
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                Files.writeString(p, str(args, "content"));
                return "Wrote " + p.toAbsolutePath();
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    public Tool runCommand() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("command", strProp("Shell command to run."));
        return new Tool("run_command",
                "Run a shell command and return combined stdout/stderr. Mutating: requires approval.",
                schema(props, "command"), true, args -> {
            try {
                String cmd = str(args, "command");
                boolean win = System.getProperty("os.name").toLowerCase().contains("win");
                ProcessBuilder pb = win
                        ? new ProcessBuilder("cmd.exe", "/c", cmd)
                        : new ProcessBuilder("sh", "-c", cmd);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String out = new String(proc.getInputStream().readAllBytes());
                proc.waitFor();
                return truncate(out.isBlank() ? "(no output)" : out, 6000);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    // ---------------------------------------------------------------------
    // Web tools (jsoup)
    // ---------------------------------------------------------------------

    public Tool webFetch() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("url", strProp("Absolute http(s) URL to fetch."));
        return new Tool("web_fetch",
                "Fetch a web page and return the main article region as clean text (jsoup).",
                schema(props, "url"), false, args -> {
            try {
                String url = str(args, "url");
                HttpResponse<String> resp = http.send(get(url), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    return "ERROR: HTTP " + resp.statusCode() + " when fetching " + url
                            + " (the site may be blocking automated requests).";
                }
                String text = HtmlExtractor.mainText(resp.body(), url);
                if (text.isBlank()) {
                    return "The page was fetched but contained no readable text. It may require "
                            + "JavaScript to render its content, so its headlines are not available this way.";
                }
                return truncate(text, 6000);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    public Tool webSearch() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", strProp("Search query."));
        return new Tool("web_search",
                "Search the web (DuckDuckGo) and return the top results with titles, URLs, and snippets.",
                schema(props, "query"), false, args -> {
            try {
                String q = str(args, "query");
                String url = "https://html.duckduckgo.com/html/?q="
                        + URLEncoder.encode(q, StandardCharsets.UTF_8);
                HttpResponse<String> resp = http.send(get(url), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    return "ERROR: HTTP " + resp.statusCode() + " from the search endpoint.";
                }

                Document doc = Jsoup.parse(resp.body(), url);
                List<String> out = new ArrayList<>();
                int rank = 1;
                for (Element result : doc.select("div.result")) {
                    Element link = result.selectFirst("a.result__a");
                    if (link == null) continue;
                    String title = link.text().trim();
                    String href = decodeDdg(link.attr("href"));
                    Element snip = result.selectFirst(".result__snippet");
                    String snippet = snip == null ? "" : snip.text().trim();
                    out.add(rank + ". " + title + "\n   " + href
                            + (snippet.isBlank() ? "" : "\n   " + snippet));
                    if (rank++ >= 6) break;
                }
                return out.isEmpty() ? "(no results)" : String.join("\n\n", out);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) mini-agent/0.2")
                .header("Accept", "text/html")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
    }

    /** DuckDuckGo wraps result links as //duckduckgo.com/l/?uddg=<encoded-real-url>. Unwrap it. */
    private static String decodeDdg(String href) {
        if (href == null) return "";
        int i = href.indexOf("uddg=");
        if (i < 0) return href;
        String enc = href.substring(i + 5);
        int amp = enc.indexOf('&');
        if (amp >= 0) enc = enc.substring(0, amp);
        try {
            return URLDecoder.decode(enc, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return href;
        }
    }

    private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        s.put("required", List.of(required));
        return s;
    }

    private static Map<String, Object> strProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        return p;
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n...[truncated " + (s.length() - max) + " chars]";
    }
}
