package com.example.imini;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live MCP integration: drive {@link McpManager#connect} against a real stub server over BOTH transports
 * (a node child process over stdio, and a JDK HttpServer over HTTP) and assert that tools, resources and
 * prompts are discovered and that read_resource and the /mcp__server__prompt slash command return the
 * server-rendered content. The stdio half self-skips when node is unavailable; the HTTP half always runs.
 */
class McpLiveIntegrationTest {

    // ---- stdio transport: a node child process speaking JSON-RPC over stdin/stdout ----

    @Test
    void discoversAndInvokesOverStdio() throws Exception {
        if (!nodeAvailable()) {
            System.out.println("[skip] node not on PATH; skipping stdio MCP integration test");
            return; // self-skip, mirroring the eval harness
        }
        Path stub = locateStub();
        assertNotNull(stub, "stub-server.js must be present in test resources");

        McpManager mcp = new McpManager();
        setTimeout(mcp, 30);
        mcp.connect("nodestub", Map.of("command", "node", "args", List.of(stub.toString())));

        assertDiscoveryAndInvocation(mcp, "nodestub");
    }

    // ---- HTTP transport: a JDK HttpServer answering JSON-RPC POSTs ----

    @Test
    void discoversAndInvokesOverHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rpc", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String resp = handle(body);                 // null for notifications
            byte[] out = (resp == null ? "" : resp).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length == 0 ? -1 : out.length);
            if (out.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            McpManager mcp = new McpManager();
            setTimeout(mcp, 30);
            mcp.connect("httpstub", Map.of("transport", "http", "url", "http://127.0.0.1:" + port + "/rpc"));
            assertDiscoveryAndInvocation(mcp, "httpstub");
        } finally {
            server.stop(0);
        }
    }

    // ---- HTTP transport with a STREAMING (multi-event) SSE response ----

    @Test
    void discoversAndInvokesOverStreamingSse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rpc", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String resp = handle(body);                 // null for notifications
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            if (resp == null) { ex.sendResponseHeaders(200, -1); ex.close(); return; }
            // Emit a couple of interim progress events BEFORE the real response event, as a streaming
            // server would; the client must skip them and pick the response (has result).
            StringBuilder sse = new StringBuilder();
            sse.append("event: message\n").append("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"p\":0.5}}\n\n");
            sse.append("event: message\n").append("data: ").append(resp).append("\n\n");
            byte[] out = sse.toString().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            McpManager mcp = new McpManager();
            setTimeout(mcp, 30);
            mcp.connect("ssestub", Map.of("transport", "http", "url", "http://127.0.0.1:" + port + "/rpc"));
            assertDiscoveryAndInvocation(mcp, "ssestub");
        } finally {
            server.stop(0);
        }
    }

    // ---- pure: the streaming SSE selector picks the response, not interim events ----

    @Test
    void sseSelectorPicksResponseAmongMultipleEvents() {
        String sse = "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n"
                + "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"ok\":true}}\n\n";
        String picked = McpManager.jsonFromHttpBody(sse);
        assertTrue(picked != null && picked.contains("\"result\""),
                "should pick the response event, not the progress notification: " + picked);
    }

    // ---- shared assertions over a connected manager ----

    private void assertDiscoveryAndInvocation(McpManager mcp, String server) {
        // tools discovered (echo) + the auto-registered read_resource tool
        Map<String, Tool> tools = toolMap(mcp);
        assertTrue(tools.containsKey(server + "_echo"), "echo tool discovered; have " + tools.keySet());
        assertTrue(tools.containsKey(server + "_read_resource"), "read_resource tool registered");

        // resources discovered
        boolean hasGreeting = mcp.resources().stream().anyMatch(r -> "mem://greeting".equals(r.get("uri")));
        assertTrue(hasGreeting, "resource discovered: " + mcp.resources());

        // prompts discovered + exposed as a slash command
        String cmd = "/mcp__" + server + "__review";
        assertTrue(mcp.isPromptCommand(cmd), "prompt slash command recognized: " + mcp.prompts());

        // read_resource returns the server-rendered content
        String res = tools.get(server + "_read_resource").executor.apply(Map.of("uri", "mem://greeting"));
        assertTrue(res.contains("hello from resource"), "read_resource content: " + res);

        // tools/call round-trips
        String echo = tools.get(server + "_echo").executor.apply(Map.of("text", "ping"));
        assertTrue(echo.contains("echo:ping"), "tool call content: " + echo);

        // the slash command renders the prompt (prompts/get) with arguments substituted
        String rendered = mcp.renderPromptCommand(cmd + " file=A.java");
        assertTrue(rendered != null && rendered.contains("review A.java"),
                "prompt rendered with args: " + rendered);
    }

    // ---- the stub's JSON-RPC responses, shared by the HTTP handler (mirrors stub-server.js) ----

    private static String handle(String body) {
        // crude method extraction is fine for the stub; the bodies are fixed JSON.
        if (body.contains("\"method\":\"notifications/initialized\"")) return null;
        String id = extractId(body);
        if (body.contains("\"initialize\""))
            return rpc(id, "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"stub\"}}");
        if (body.contains("\"tools/list\""))
            return rpc(id, "{\"tools\":[{\"name\":\"echo\",\"description\":\"Echo\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}}]}");
        if (body.contains("\"tools/call\"")) {
            String text = extractField(body, "text");
            return rpc(id, "{\"content\":[{\"type\":\"text\",\"text\":\"echo:" + text + "\"}]}");
        }
        if (body.contains("\"resources/list\""))
            return rpc(id, "{\"resources\":[{\"uri\":\"mem://greeting\",\"name\":\"greeting\"}]}");
        if (body.contains("\"resources/read\""))
            return rpc(id, "{\"contents\":[{\"uri\":\"mem://greeting\",\"mimeType\":\"text/plain\",\"text\":\"hello from resource mem://greeting\"}]}");
        if (body.contains("\"prompts/list\""))
            return rpc(id, "{\"prompts\":[{\"name\":\"review\",\"description\":\"Review a file\"}]}");
        if (body.contains("\"prompts/get\"")) {
            String file = extractField(body, "file");
            if (file.isEmpty()) file = "the code";
            return rpc(id, "{\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"Please review " + file + " carefully.\"}}]}");
        }
        return rpc(id, "{}");
    }

    private static String rpc(String id, String result) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + (id == null ? "null" : id) + ",\"result\":" + result + "}";
    }

    private static String extractId(String body) {
        int i = body.indexOf("\"id\":");
        if (i < 0) return null;
        int j = i + 5;
        int k = j;
        while (k < body.length() && "0123456789".indexOf(body.charAt(k)) >= 0) k++;
        return k > j ? body.substring(j, k) : null;
    }

    private static String extractField(String body, String field) {
        String key = "\"" + field + "\":\"";
        int i = body.indexOf(key);
        if (i < 0) return "";
        int j = i + key.length();
        int k = body.indexOf('"', j);
        return k > j ? body.substring(j, k) : "";
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private static Map<String, Tool> toolMap(McpManager mcp) {
        java.util.Map<String, Tool> m = new java.util.LinkedHashMap<>();
        for (Tool t : mcp.tools()) m.put(t.name, t);
        return m;
    }

    private static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Path locateStub() {
        for (String c : new String[]{
                "src/test/resources/mcp/stub-server.js",
                "target/test-classes/mcp/stub-server.js"}) {
            Path p = Path.of(c);
            if (Files.exists(p)) return p.toAbsolutePath();
        }
        return null;
    }

    private static void setTimeout(McpManager mcp, int seconds) throws Exception {
        java.lang.reflect.Field f = McpManager.class.getDeclaredField("toolTimeoutSeconds");
        f.setAccessible(true);
        f.setInt(mcp, seconds);
    }
}
