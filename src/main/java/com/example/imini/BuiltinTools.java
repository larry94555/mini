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
 * Builds the individual Tool instances.
 *
 *   all()        -> tools the MAIN agent uses: read_file, view, list_dir, write_file, edit_file,
 *                   run_command, web_fetch. (web_search is delegated to the sub-agent.)
 *   webFetch()   -> jsoup page fetch + main-article extraction
 *   webSearch()  -> DuckDuckGo HTML search (given only to the sub-agent)
 *
 * write_file and edit_file snapshot the target through CheckpointStore first, so edits can be rewound.
 */
@Component
public class BuiltinTools {

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)   // CNN etc. redirect; without this the body is empty
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final CheckpointStore checkpoints;
    private final TodoStore todos;

    public BuiltinTools(CheckpointStore checkpoints, TodoStore todos) {
        this.checkpoints = checkpoints;
        this.todos = todos;
    }

    /** Tools available to the main agent. */
    public List<Tool> all() {
        return List.of(readFile(), view(), listDir(), writeFile(), editFile(),
                runCommand(), webFetch(), todoWrite());
    }

    // ---------------------------------------------------------------------
    // File tools
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

    public Tool view() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", strProp("File to view."));
        props.put("start_line", intProp("Optional 1-based first line to show."));
        props.put("end_line", intProp("Optional 1-based last line to show (inclusive)."));
        return new Tool("view",
                "Read a text file with line numbers (optionally a line range). Use this before edit_file "
                        + "so you can copy an exact, unique snippet to replace.",
                schema(props, "path"), false, args -> {
            try {
                List<String> lines = Files.readAllLines(Path.of(str(args, "path")));
                int start = Math.max(1, intArg(args, "start_line", 1));
                int end = Math.min(lines.size(), intArg(args, "end_line", lines.size()));
                StringBuilder sb = new StringBuilder();
                for (int i = start; i <= end; i++) {
                    sb.append(String.format("%6d\t%s%n", i, lines.get(i - 1)));
                }
                return sb.length() == 0 ? "(empty or range out of bounds)" : truncate(sb.toString(), 8000);
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
        return new Tool("write_file",
                "Create or overwrite a whole file. For changing part of an existing file, prefer edit_file. "
                        + "Mutating: requires approval.",
                schema(props, "path", "content"), true, args -> {
            try {
                Path p = Path.of(str(args, "path"));
                checkpoints.snapshot(p);                       // save before overwriting
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                Files.writeString(p, str(args, "content"));
                return "Wrote " + p.toAbsolutePath() + " (snapshot saved for rewind).";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    public Tool editFile() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", strProp("File to edit."));
        props.put("old_str", strProp("Exact text to find. Must appear EXACTLY ONCE in the file."));
        props.put("new_str", strProp("Text to replace it with."));
        return new Tool("edit_file",
                "Replace a unique, exact snippet in a file with new text. Fails if the snippet is missing "
                        + "or appears more than once. Snapshots the file first so it can be rewound. "
                        + "Mutating: requires approval.",
                schema(props, "path", "old_str", "new_str"), true, args -> {
            try {
                Path p = Path.of(str(args, "path"));
                String content = Files.readString(p);
                String oldStr = str(args, "old_str");
                String newStr = str(args, "new_str");
                if (oldStr.isEmpty()) return "ERROR: old_str must not be empty.";
                int count = countOccurrences(content, oldStr);
                if (count == 0) return "ERROR: old_str was not found in " + p + ".";
                if (count > 1) {
                    return "ERROR: old_str appears " + count + " times in " + p
                            + "; include more surrounding text so it is unique.";
                }
                checkpoints.snapshot(p);
                Files.writeString(p, content.replace(oldStr, newStr));
                return "Edited " + p.toAbsolutePath() + " (1 replacement; snapshot saved for rewind).";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    // ---------------------------------------------------------------------
    // Planning tool
    // ---------------------------------------------------------------------

    public Tool todoWrite() {
        // schema: { todos: [ { content: string, status: pending|in_progress|completed } ] }
        Map<String, Object> item = new LinkedHashMap<>();
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("content", strProp("What needs to be done."));
        Map<String, Object> statusProp = new LinkedHashMap<>();
        statusProp.put("type", "string");
        statusProp.put("enum", List.of("pending", "in_progress", "completed"));
        statusProp.put("description", "pending | in_progress | completed");
        itemProps.put("status", statusProp);
        item.put("type", "object");
        item.put("properties", itemProps);
        item.put("required", List.of("content", "status"));

        Map<String, Object> todosProp = new LinkedHashMap<>();
        todosProp.put("type", "array");
        todosProp.put("items", item);
        todosProp.put("description", "The COMPLETE task list (always pass the whole list).");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("todos", todosProp);

        return new Tool("todo_write",
                "Record or update your task checklist for a multi-step task. Always pass the FULL list. "
                        + "Use it to plan before you start and to mark steps in_progress/completed as you go.",
                schema(props, "todos"), false, args -> {
            try {
                Object raw = args.get("todos");
                List<TodoStore.Item> items = new ArrayList<>();
                if (raw instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> m) {
                            String content = String.valueOf(m.get("content"));
                            Object st = m.get("status");
                            items.add(new TodoStore.Item(content, st == null ? "pending" : String.valueOf(st)));
                        }
                    }
                }
                todos.set(items);
                String rendered = todos.render();
                System.out.println("[todo] updated:\n" + rendered);
                return "Updated todo list:\n" + rendered;
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    // ---------------------------------------------------------------------
    // Shell tool
    // ---------------------------------------------------------------------

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
                schema(props, "url"), false, true /* untrusted output */, args -> {
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
                schema(props, "query"), false, true /* untrusted output */, args -> {
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
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) mini-agent/0.3")
                .header("Accept", "text/html")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
    }

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

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, from = 0, idx;
        while ((idx = haystack.indexOf(needle, from)) >= 0) {
            n++;
            from = idx + needle.length();
        }
        return n;
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

    private static Map<String, Object> intProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "integer");
        p.put("description", description);
        return p;
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static int intArg(Map<String, Object> args, String key, int dflt) {
        Object v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? dflt : Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n...[truncated " + (s.length() - max) + " chars]";
    }
}
