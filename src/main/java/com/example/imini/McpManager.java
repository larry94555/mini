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
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final List<Tool> tools = new ArrayList<>();

    @Value("${agent.tool-timeout-seconds:60}")
    private int toolTimeoutSeconds;

    public List<Tool> tools() {
        return tools;
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

            // --- handshake ---
            Map<String, Object> initParams = new LinkedHashMap<>();
            initParams.put("protocolVersion", PROTOCOL_VERSION);
            initParams.put("capabilities", Map.of());
            initParams.put("clientInfo", Map.of("name", "imini", "version", "0.3"));
            srv.request("initialize", initParams);
            srv.notify("notifications/initialized", Map.of());

            // --- discover tools ---
            Map<String, Object> listed = srv.request("tools/list", Map.of());
            Map<String, Object> result = (Map<String, Object>) listed.getOrDefault("result", Map.of());
            List<Map<String, Object>> mcpTools =
                    (List<Map<String, Object>>) result.getOrDefault("tools", List.of());

            for (Map<String, Object> t : mcpTools) {
                String toolName = String.valueOf(t.get("name"));
                String desc = String.valueOf(t.getOrDefault("description", ""));
                Map<String, Object> schema = (Map<String, Object>) t.get("inputSchema");
                if (schema == null) schema = Map.of("type", "object", "properties", Map.of());

                String exposedName = sanitize(name + "_" + toolName);
                final String original = toolName;
                // MCP tools are external code -> treat as mutating so the permission gate applies.
                tools.add(new Tool(exposedName, "[MCP:" + name + "] " + desc, schema, true, true,
                        callArgs -> srv.callTool(original, callArgs)));
                log.info("[mcp] " + name + " -> tool " + exposedName);
            }
        } catch (Exception ex) {
            log.warn("[mcp] server '" + name + "' failed: " + ex.getMessage());
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    @PreDestroy
    public void stop() {
        for (Server s : servers) s.close();
    }

    /**
     * One MCP server, speaking newline-delimited JSON-RPC 2.0 over stdio. Calls are serialized
     * (synchronized) and read synchronously: send a request, then read lines until the response
     * with the matching id appears, skipping notifications and any non-JSON log lines.
     *
     * Limitation worth knowing: a misbehaving server that never replies will block this thread.
     * A production client would add per-call timeouts on a separate reader thread.
     */
    private final class Server {
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

        synchronized Map<String, Object> request(String method, Map<String, Object> params) throws IOException {
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

        synchronized void notify(String method, Map<String, Object> params) throws IOException {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("jsonrpc", "2.0");
            msg.put("method", method);
            msg.put("params", params);
            writeLine(msg);
        }

        @SuppressWarnings("unchecked")
        String callTool(String toolName, Map<String, Object> arguments) {
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("name", toolName);
                params.put("arguments", arguments == null ? Map.of() : arguments);
                Map<String, Object> resp = request("tools/call", params);

                Object error = resp.get("error");
                if (error != null) return "ERROR from MCP tool: " + error;

                Map<String, Object> result = (Map<String, Object>) resp.get("result");
                if (result == null) return "(no result)";

                StringBuilder sb = new StringBuilder();
                Object content = result.get("content");
                if (content instanceof List<?> parts) {
                    for (Object p : parts) {
                        Map<String, Object> part = (Map<String, Object>) p;
                        if ("text".equals(part.get("type"))) sb.append(part.get("text")).append("\n");
                    }
                }
                String text = sb.toString().trim();
                return text.isEmpty() ? String.valueOf(result) : text;
            } catch (Exception e) {
                return "ERROR calling MCP tool '" + toolName + "': " + e.getMessage();
            }
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

        void close() {
            try {
                io.shutdownNow();
                proc.destroy();
            } catch (Exception ignore) {
                // best effort
            }
        }
    }
}
