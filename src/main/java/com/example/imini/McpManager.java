package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A minimal Model Context Protocol client. On startup it reads mcp.json (if present), launches each
 * configured server as a child process, speaks JSON-RPC 2.0 over the server's stdin/stdout, asks it
 * for its tools, and wraps each one as an imini Tool. Those tools are then registered alongside the
 * built-ins -- so the model can use them with zero other changes.
 *
 * This is the clearest demonstration of the harness point: the model's tools live OUTSIDE the model,
 * in separate processes, and are pluggable. MCP is OFF unless an mcp.json exists.
 *
 * mcp.json format (same shape as common MCP clients):
 *   { "mcpServers": { "name": { "command": "npx", "args": ["-y","@scope/server"], "env": {} } } }
 */
@Component
public class McpManager {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpManager.class);


    // The protocol version we advertise. Servers negotiate; this one is widely supported.
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final Path CONFIG = Path.of("mcp.json");

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Server> servers = new ArrayList<>();
    private final List<Transport> transports = new ArrayList<>();
    private final List<Tool> tools = new ArrayList<>();
    // Discovered MCP resources/prompts (for introspection + tests). resource = {server,uri,name}; prompt = {server,name,description}.
    private final List<Map<String, Object>> resources = new ArrayList<>();
    private final List<Map<String, Object>> prompts = new ArrayList<>();
    // Slash-command name (mcp__server__prompt) -> the prompt's callable tool, for /-dispatch.
    private final Map<String, Tool> promptCommands = new LinkedHashMap<>();

    @Value("${agent.tool-timeout-seconds:60}")
    private int toolTimeoutSeconds;

    public List<Tool> tools() {
        return tools;
    }

    /** Discovered MCP resources across all servers (each: {server, uri, name}). */
    public List<Map<String, Object>> resources() { return resources; }

    /** Discovered MCP prompts across all servers (each: {server, name, description, command}). */
    public List<Map<String, Object>> prompts() { return prompts; }

    /** True if the message is a slash invocation of a discovered MCP prompt (e.g. {@code /mcp__server__name ...}). */
    public boolean isPromptCommand(String msg) {
        String c = commandToken(msg);
        return c != null && promptCommands.containsKey(c);
    }

    /**
     * Render an MCP prompt slash command to its prompt text: parses {@code /mcp__server__name k=v k2=v2},
     * calls {@code prompts/get} with the parsed arguments, and returns the rendered messages. Returns null
     * if the message is not a known prompt command. The result becomes the prompt the model then runs on.
     */
    public String renderPromptCommand(String msg) {
        String c = commandToken(msg);
        if (c == null) return null;
        Tool t = promptCommands.get(c);
        if (t == null) return null;
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("arguments", parsePromptArgs(argString(msg)));
        return t.executor.apply(args);
    }

    /** One-line help listing of the available MCP prompt slash commands (empty string if none). */
    public String promptCommandHelp() {
        if (promptCommands.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> p : prompts) {
            sb.append("  /").append(p.get("command"));
            Object d = p.get("description");
            if (d != null && !String.valueOf(d).isBlank()) sb.append("  -- ").append(d);
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Pure: the first token after a leading '/', or null if the message is not a slash command. */
    static String commandToken(String msg) {
        if (msg == null) return null;
        String m = msg.trim();
        if (!m.startsWith("/")) return null;
        int sp = m.indexOf(' ');
        return (sp < 0 ? m.substring(1) : m.substring(1, sp)).trim();
    }

    /** Pure: the argument string after the command token (everything after the first space), or "". */
    static String argString(String msg) {
        if (msg == null) return "";
        String m = msg.trim();
        int sp = m.indexOf(' ');
        return sp < 0 ? "" : m.substring(sp + 1).trim();
    }

    /** Pure: parse {@code key=value} space-separated tokens into an arguments map (empty if none). */
    static Map<String, Object> parsePromptArgs(String argStr) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (argStr == null || argStr.isBlank()) return out;
        for (String tok : argStr.trim().split("\\s+")) {
            int eq = tok.indexOf('=');
            if (eq > 0) out.put(tok.substring(0, eq), tok.substring(eq + 1));
        }
        return out;
    }

    /** Minimal JSON-RPC transport: stdio (child process) or streamable HTTP (POST to a URL). */
    interface Transport {
        Map<String, Object> request(String method, Map<String, Object> params) throws IOException;
        void notify(String method, Map<String, Object> params) throws IOException;
        void close();
    }

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void start() {
        if (!Files.exists(CONFIG)) {
            log.info("[mcp] no mcp.json found; MCP integration is off.");
            return;
        }
        int serverCount = 0;
        try {
            Map<String, Object> cfg = mapper.readValue(Files.readAllBytes(CONFIG), Map.class);
            Map<String, Object> defs = (Map<String, Object>) cfg.getOrDefault("mcpServers", Map.of());
            for (Map.Entry<String, Object> e : defs.entrySet()) {
                startServer(e.getKey(), (Map<String, Object>) e.getValue());
                serverCount++;
            }
        } catch (Exception ex) {
            log.warn("[mcp] failed to read mcp.json: " + ex.getMessage());
        }
        log.info("[mcp] registered " + tools.size() + " tool(s) from " + serverCount + " server(s).");
    }

    @SuppressWarnings("unchecked")
    private void startServer(String name, Map<String, Object> conf) {
        try {
            String transportKind = String.valueOf(conf.getOrDefault("transport", "stdio")).toLowerCase();
            Transport t;
            if ("http".equals(transportKind) || "sse".equals(transportKind)) {
                String url = String.valueOf(conf.get("url"));
                if (url == null || url.isBlank() || "null".equals(url)) {
                    log.warn("[mcp] server '" + name + "' uses transport=" + transportKind + " but has no 'url'; skipping.");
                    return;
                }
                t = new HttpTransport(name, url);
                log.info("[mcp] " + name + " over HTTP transport -> " + url);
            } else {
                List<String> cmd = new ArrayList<>();
                cmd.add(String.valueOf(conf.get("command")));
                Object args = conf.get("args");
                if (args instanceof List<?> list) {
                    for (Object a : list) cmd.add(String.valueOf(a));
                }
                ProcessBuilder pb = new ProcessBuilder(cmd);
                Object env = conf.get("env");
                if (env instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> pb.environment().put(String.valueOf(k), String.valueOf(v)));
                }
                Files.createDirectories(Path.of(".imini"));
                pb.redirectError(new File(".imini/mcp-" + name + ".log")); // keep stdout clean for JSON-RPC
                Process proc = pb.start();
                Server srv = new Server(name, proc);
                servers.add(srv);
                t = srv;
            }
            transports.add(t);
            discover(name, t);
        } catch (Exception ex) {
            log.warn("[mcp] server '" + name + "' failed: " + ex.getMessage());
        }
    }

    /** Handshake then discover tools, resources, and prompts over the given transport. */
    @SuppressWarnings("unchecked")
    private void discover(String name, Transport t) throws IOException {
        // --- handshake ---
        Map<String, Object> initParams = new LinkedHashMap<>();
        initParams.put("protocolVersion", PROTOCOL_VERSION);
        initParams.put("capabilities", Map.of());
        initParams.put("clientInfo", Map.of("name", "imini", "version", "0.3"));
        t.request("initialize", initParams);
        t.notify("notifications/initialized", Map.of());

        // --- discover tools ---
        Map<String, Object> listed = t.request("tools/list", Map.of());
        Map<String, Object> result = (Map<String, Object>) listed.getOrDefault("result", Map.of());
        List<Map<String, Object>> mcpTools = (List<Map<String, Object>>) result.getOrDefault("tools", List.of());
        for (Map<String, Object> td : mcpTools) {
            String toolName = String.valueOf(td.get("name"));
            String desc = String.valueOf(td.getOrDefault("description", ""));
            Map<String, Object> schema = (Map<String, Object>) td.get("inputSchema");
            if (schema == null) schema = Map.of("type", "object", "properties", Map.of());
            String exposedName = sanitize(name + "_" + toolName);
            final String original = toolName;
            // MCP tools are external code -> treat as mutating so the permission gate applies.
            tools.add(new Tool(exposedName, "[MCP:" + name + "] " + desc, schema, true, true,
                    callArgs -> callTool(t, name, original, callArgs)));
            log.info("[mcp] " + name + " -> tool " + exposedName);
        }

        // --- discover resources (best-effort; servers may not support them) ---
        try {
            Map<String, Object> rl = t.request("resources/list", Map.of());
            Map<String, Object> rr = (Map<String, Object>) rl.getOrDefault("result", Map.of());
            List<Map<String, Object>> rs = (List<Map<String, Object>>) rr.getOrDefault("resources", List.of());
            for (Map<String, Object> r : rs) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("server", name);
                entry.put("uri", String.valueOf(r.get("uri")));
                entry.put("name", String.valueOf(r.getOrDefault("name", r.get("uri"))));
                resources.add(entry);
            }
            if (!rs.isEmpty()) {
                tools.add(new Tool(sanitize(name + "_read_resource"),
                        "[MCP:" + name + "] Read an MCP resource by uri (list available ones with no args).",
                        Map.of("type", "object", "properties",
                                Map.of("uri", Map.of("type", "string", "description", "Resource uri to read; omit to list."))),
                        false, true, callArgs -> readResource(t, name, callArgs)));
                log.info("[mcp] " + name + " -> " + rs.size() + " resource(s)");
            }
        } catch (Exception e) {
            log.info("[mcp] " + name + " has no resources (" + e.getMessage() + ")");
        }

        // --- discover prompts (best-effort) ---
        try {
            Map<String, Object> pl = t.request("prompts/list", Map.of());
            Map<String, Object> pr = (Map<String, Object>) pl.getOrDefault("result", Map.of());
            List<Map<String, Object>> ps = (List<Map<String, Object>>) pr.getOrDefault("prompts", List.of());
            for (Map<String, Object> p : ps) {
                String pname = String.valueOf(p.get("name"));
                String pdesc = String.valueOf(p.getOrDefault("description", ""));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("server", name);
                entry.put("name", pname);
                entry.put("description", pdesc);
                // Claude-Code-style slash command name: /mcp__<server>__<prompt>
                String command = "mcp__" + sanitize(name) + "__" + sanitize(pname);
                entry.put("command", command);
                prompts.add(entry);
                // Expose each prompt as a callable tool (the Claude-Code "/mcp__server__prompt" surface).
                final String original = pname;
                Tool promptTool = new Tool(sanitize(name + "_prompt_" + pname),
                        "[MCP:" + name + " prompt] " + pdesc,
                        Map.of("type", "object", "properties",
                                Map.of("arguments", Map.of("type", "object", "description", "Prompt arguments (optional)."))),
                        false, true, callArgs -> getPrompt(t, name, original, callArgs));
                tools.add(promptTool);
                promptCommands.put(command, promptTool);
                log.info("[mcp] " + name + " -> prompt " + pname + " (/" + command + ")");
            }
        } catch (Exception e) {
            log.info("[mcp] " + name + " has no prompts (" + e.getMessage() + ")");
        }
    }

    @SuppressWarnings("unchecked")
    private String callTool(Transport t, String server, String toolName, Map<String, Object> arguments) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", toolName);
            params.put("arguments", arguments == null ? Map.of() : arguments);
            Map<String, Object> resp = t.request("tools/call", params);
            Object error = resp.get("error");
            if (error != null) return "ERROR from MCP tool: " + error;
            Map<String, Object> result = (Map<String, Object>) resp.get("result");
            if (result == null) return "(no result)";
            String text = extractText(result.get("content"));
            return text.isEmpty() ? String.valueOf(result) : text;
        } catch (Exception e) {
            return "ERROR calling MCP tool '" + toolName + "': " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String readResource(Transport t, String server, Map<String, Object> args) {
        try {
            String uri = args == null ? "" : String.valueOf(args.getOrDefault("uri", ""));
            if (uri.isBlank() || "null".equals(uri)) {
                StringBuilder sb = new StringBuilder("Available resources on '" + server + "':\n");
                for (Map<String, Object> r : resources) {
                    if (server.equals(r.get("server"))) sb.append("  ").append(r.get("uri")).append("  (").append(r.get("name")).append(")\n");
                }
                return sb.toString().trim();
            }
            Map<String, Object> resp = t.request("resources/read", Map.of("uri", uri));
            Object error = resp.get("error");
            if (error != null) return "ERROR reading MCP resource: " + error;
            Map<String, Object> result = (Map<String, Object>) resp.get("result");
            if (result == null) return "(no result)";
            // result.contents: [{uri, mimeType, text|blob}]
            List<Map<String, Object>> contents = (List<Map<String, Object>>) result.getOrDefault("contents", List.of());
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> c : contents) {
                Object text = c.get("text");
                if (text != null) sb.append(text).append("\n");
            }
            String out = sb.toString().trim();
            return out.isEmpty() ? String.valueOf(result) : out;
        } catch (Exception e) {
            return "ERROR reading MCP resource: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String getPrompt(Transport t, String server, String promptName, Map<String, Object> args) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", promptName);
            Object a = args == null ? null : args.get("arguments");
            params.put("arguments", a instanceof Map ? a : Map.of());
            Map<String, Object> resp = t.request("prompts/get", params);
            Object error = resp.get("error");
            if (error != null) return "ERROR getting MCP prompt: " + error;
            Map<String, Object> result = (Map<String, Object>) resp.get("result");
            if (result == null) return "(no result)";
            // result.messages: [{role, content:{type:text,text}}]
            List<Map<String, Object>> msgs = (List<Map<String, Object>>) result.getOrDefault("messages", List.of());
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> m : msgs) {
                Object content = m.get("content");
                String text = extractText(content instanceof List ? content : List.of(content));
                if (!text.isBlank()) sb.append(text).append("\n");
            }
            String out = sb.toString().trim();
            return out.isEmpty() ? String.valueOf(result) : out;
        } catch (Exception e) {
            return "ERROR getting MCP prompt: " + e.getMessage();
        }
    }

    /** Pull text out of an MCP content value (a list of {type:text,text} parts, or a single such map). */
    @SuppressWarnings("unchecked")
    private static String extractText(Object content) {
        StringBuilder sb = new StringBuilder();
        if (content instanceof List<?> parts) {
            for (Object p : parts) {
                if (p instanceof Map<?, ?> part && "text".equals(part.get("type"))) {
                    sb.append(part.get("text")).append("\n");
                }
            }
        } else if (content instanceof Map<?, ?> m && "text".equals(m.get("type"))) {
            sb.append(m.get("text")).append("\n");
        }
        return sb.toString().trim();
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    @PreDestroy
    public void stop() {
        for (Transport t : transports) t.close();
    }

    /**
     * One MCP server, speaking newline-delimited JSON-RPC 2.0 over stdio. Calls are serialized
     * (synchronized) and read synchronously: send a request, then read lines until the response
     * with the matching id appears, skipping notifications and any non-JSON log lines.
     *
     * Limitation worth knowing: a misbehaving server that never replies will block this thread.
     * A production client would add per-call timeouts on a separate reader thread.
     */
    private final class Server implements Transport {
        private final String name;
        private final Process proc;
        private final BufferedWriter out;
        private final BufferedReader in;
        private final ExecutorService io;
        private volatile boolean dead = false;
        private int nextId = 1;

        Server(String name, Process proc) {
            this.name = name;
            this.proc = proc;
            this.out = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()));
            this.in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            this.io = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mcp-" + name + "-io");
                t.setDaemon(true);
                return t;
            });
        }

        @Override public synchronized Map<String, Object> request(String method, Map<String, Object> params) throws IOException {
            if (dead) throw new IOException("MCP server '" + name + "' is not running.");
            int id = nextId++;
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("jsonrpc", "2.0");
            msg.put("id", id);
            msg.put("method", method);
            msg.put("params", params);
            writeLine(msg);

            Future<Map<String, Object>> f = io.submit(() -> {
                String line;
                while ((line = in.readLine()) != null) {
                    Map<String, Object> resp = parse(line);
                    if (resp == null) continue;                   // skip non-JSON log noise
                    Object rid = resp.get("id");
                    if (rid instanceof Number n && n.intValue() == id) return resp;
                    // otherwise a notification or unrelated message: ignore and keep reading
                }
                throw new IOException("MCP server '" + name + "' closed before responding to " + method);
            });
            try {
                return f.get(toolTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                dead = true;
                proc.destroyForcibly();
                io.shutdownNow();
                throw new IOException("MCP server '" + name + "' timed out after " + toolTimeoutSeconds
                        + "s on " + method + "; server terminated.");
            } catch (java.util.concurrent.ExecutionException ee) {
                Throwable cause = ee.getCause();
                throw (cause instanceof IOException io2) ? io2 : new IOException(String.valueOf(cause));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for MCP server '" + name + "'");
            }
        }

        @Override public synchronized void notify(String method, Map<String, Object> params) throws IOException {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("jsonrpc", "2.0");
            msg.put("method", method);
            msg.put("params", params);
            writeLine(msg);
        }

        private void writeLine(Map<String, Object> msg) throws IOException {
            out.write(mapper.writeValueAsString(msg));
            out.write("\n");
            out.flush();
        }

        private Map<String, Object> parse(String line) {
            try {
                return mapper.readValue(line, Map.class);
            } catch (Exception e) {
                return null;
            }
        }

        @Override public void close() {
            try {
                io.shutdownNow();
                proc.destroy();
            } catch (Exception ignore) {
                // best effort
            }
        }
    }

    /**
     * Streamable-HTTP JSON-RPC transport: POSTs each request to a single endpoint URL and reads the
     * response. Accepts either a plain {@code application/json} body or a {@code text/event-stream}
     * (SSE) body, from which it extracts the first {@code data:} JSON line. Notifications are POSTed
     * fire-and-forget. This is the dependency-free, non-streaming subset that covers most simple
     * HTTP MCP servers; long-lived server-initiated SSE streams are out of scope here.
     */
    private final class HttpTransport implements Transport {
        private final String name;
        private final URI uri;
        private final HttpClient client;
        private int nextId = 1;

        HttpTransport(String name, String url) {
            this.name = name;
            this.uri = URI.create(url);
            this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        }

        @Override public synchronized Map<String, Object> request(String method, Map<String, Object> params) throws IOException {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("jsonrpc", "2.0");
            msg.put("id", nextId++);
            msg.put("method", method);
            msg.put("params", params);
            try {
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(toolTimeoutSeconds))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(msg), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() / 100 != 2) {
                    throw new IOException("MCP HTTP server '" + name + "' returned " + resp.statusCode());
                }
                Map<String, Object> parsed = parseHttpBody(resp.body());
                if (parsed == null) throw new IOException("MCP HTTP server '" + name + "' returned no JSON-RPC body");
                return parsed;
            } catch (IOException ioe) {
                throw ioe;
            } catch (Exception e) {
                throw new IOException("MCP HTTP request to '" + name + "' failed: " + e.getMessage());
            }
        }

        @Override public synchronized void notify(String method, Map<String, Object> params) throws IOException {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("jsonrpc", "2.0");
            msg.put("method", method);
            msg.put("params", params);
            try {
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(toolTimeoutSeconds))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(msg), StandardCharsets.UTF_8))
                        .build();
                client.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                // notifications are best-effort
            }
        }

        @Override public void close() { /* stateless HTTP; nothing to tear down */ }
    }

    /** Parse an HTTP MCP body: plain JSON, or SSE — extract the first {@code data:} JSON line. Pure + static for testing. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseHttpBodyStatic(ObjectMapper mapper, String body) {
        String json = jsonFromHttpBody(body);
        if (json == null) return null;
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Pure: given an HTTP MCP body, return the JSON-RPC payload string — the body itself if it is a JSON
     * object, or the first {@code data:} line's JSON for an SSE ({@code text/event-stream}) body; null if
     * neither. Separated from JSON parsing so the transport selection is unit-testable without Jackson.
     */
    static String jsonFromHttpBody(String body) {
        if (body == null) return null;
        String trimmed = body.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("{")) return trimmed;
        for (String line : trimmed.split("\\r?\\n")) {
            String l = line.trim();
            if (l.startsWith("data:")) {
                String json = l.substring("data:".length()).trim();
                if (json.startsWith("{")) return json;
            }
        }
        return null;
    }

    private Map<String, Object> parseHttpBody(String body) {
        return parseHttpBodyStatic(mapper, body);
    }
}
