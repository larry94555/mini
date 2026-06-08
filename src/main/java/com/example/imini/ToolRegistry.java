package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Holds the tools and produces the OpenAI-compatible "tools" array we send to the model.
 * Add a tool here and the model can immediately use it -- no model retraining, just a new function
 * plus a description. This registry is a big part of what a "harness" is.
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    public ToolRegistry() {
        register(readFileTool());
        register(listDirTool());
        register(writeFileTool());
        register(runCommandTool());
        register(webFetchTool());
    }

    private void register(Tool t) {
        tools.put(t.name, t);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    /** Builds the [{type:function, function:{name, description, parameters}}] array for the model. */
    public List<Map<String, Object>> specs() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Tool t : tools.values()) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", t.name);
            fn.put("description", t.description);
            fn.put("parameters", t.parameters);

            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("type", "function");
            spec.put("function", fn);
            list.add(spec);
        }
        return list;
    }

    // ---------------------------------------------------------------------
    // Tool implementations
    // ---------------------------------------------------------------------

    private Tool readFileTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", strProp("Path to the text file to read."));
        return new Tool("read_file", "Read a UTF-8 text file from disk.",
                schema(props, "path"), false, args -> {
            try {
                String content = Files.readString(Path.of(str(args, "path")));
                return truncate(content, 6000);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    private Tool listDirTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", strProp("Directory to list. Defaults to current directory."));
        return new Tool("list_dir", "List the entries in a directory.",
                schema(props), false, args -> {
            try {
                String p = args.containsKey("path") ? str(args, "path") : ".";
                try (var stream = Files.list(Path.of(p))) {
                    String out = stream.map(x -> (Files.isDirectory(x) ? "[dir]  " : "[file] ") + x.getFileName())
                            .sorted()
                            .collect(Collectors.joining("\n"));
                    return out.isBlank() ? "(empty)" : out;
                }
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    private Tool writeFileTool() {
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

    private Tool runCommandTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("command", strProp("Shell command to run."));
        return new Tool("run_command", "Run a shell command and return combined stdout/stderr. "
                + "Mutating: requires approval.",
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

    private Tool webFetchTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("url", strProp("Absolute http(s) URL to fetch."));
        return new Tool("web_fetch", "Fetch a web page and return it as plain text (HTML stripped).",
                schema(props, "url"), false, args -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(str(args, "url")))
                        .header("User-Agent", "mini-agent/0.1")
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                // NOTE: this regex strip is the ENTIRE "interpret the HTML structure" job done in code.
                // It is mechanical (remove tags). Deciding *which* text is the top story is the model's job.
                return truncate(htmlToText(resp.body()), 6000);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

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

    private static String htmlToText(String html) {
        if (html == null) return "";
        String s = html
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#39;", "'")
                .replace("&quot;", "\"");
        return s.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("(?m)^\\s+", "")
                .replaceAll("\\n{2,}", "\n")
                .trim();
    }
}
