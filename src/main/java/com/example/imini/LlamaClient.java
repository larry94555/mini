package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Thin wrapper over llama-server's OpenAI-compatible POST /v1/chat/completions.
 *
 * Safety relevant to the "endless loop" bug:
 *   - every request now sets max_tokens, so one generation can never run unbounded;
 *   - frequency/presence penalties discourage the small model from repeating itself;
 *   - chatStream has three independent guards (length, wall-clock, line-repetition) that stop a
 *     runaway stream even if the server ignores the token cap.
 */
@Component
public class LlamaClient {

    private static final String ENDPOINT = "http://localhost:8081/v1/chat/completions";
    private static final String MODEL = "qwen2.5-3b-instruct"; // matches --alias

    // sampling: curb repetition in the small model
    private static final double FREQUENCY_PENALTY = 0.5;
    private static final double PRESENCE_PENALTY = 0.3;
    // stream guard: how many times the same non-empty line may repeat before we abort
    private static final int LINE_REPEAT_LIMIT = 8;

    @Value("${agent.max-tokens:1024}")
    private int maxTokens;
    @Value("${agent.stream-max-chars:12000}")
    private int streamMaxChars;
    @Value("${agent.stream-max-seconds:90}")
    private int streamMaxSeconds;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    // ---------------------------------------------------------------------
    // Blocking
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Map<String, Object> chat(List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools) throws Exception {
        HttpRequest req = buildRequest(messages, tools, false);
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("llama-server error " + resp.statusCode() + ": " + resp.body());
        }
        Map<String, Object> json = mapper.readValue(resp.body(), Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) json.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("no choices in response: " + resp.body());
        }
        return (Map<String, Object>) choices.get(0).get("message");
    }

    // ---------------------------------------------------------------------
    // Streaming (SSE) with runaway guards
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Map<String, Object> chatStream(List<Map<String, Object>> messages,
                                          List<Map<String, Object>> tools,
                                          Consumer<String> onToken) throws Exception {
        HttpRequest req = buildRequest(messages, tools, true);
        HttpResponse<Stream<String>> resp = http.send(req, HttpResponse.BodyHandlers.ofLines());
        if (resp.statusCode() / 100 != 2) {
            String body = resp.body().collect(Collectors.joining("\n"));
            throw new RuntimeException("llama-server error " + resp.statusCode() + ": " + body);
        }

        StringBuilder content = new StringBuilder();
        TreeMap<Integer, ToolCallAcc> toolCalls = new TreeMap<>();

        long deadline = System.nanoTime() + streamMaxSeconds * 1_000_000_000L;
        int scanCursor = 0;       // index in content up to which lines have been checked
        String lastLine = null;
        int lineRepeat = 0;
        String abortReason = null;

        Stream<String> body = resp.body();
        try {
            Iterator<String> it = body.iterator();
            while (it.hasNext()) {
                String line = it.next();
                if (line == null || !line.startsWith("data:")) continue;
                String data = line.substring("data:".length()).trim();
                if (data.isEmpty()) continue;
                if (data.equals("[DONE]")) break;

                Map<String, Object> chunk = mapper.readValue(data, Map.class);
                List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                if (choices == null || choices.isEmpty()) continue;
                Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                if (delta == null) continue;

                Object c = delta.get("content");
                if (c != null) {
                    String piece = String.valueOf(c);
                    content.append(piece);
                    if (onToken != null) onToken.accept(piece);
                }

                Object tcs = delta.get("tool_calls");
                if (tcs instanceof List<?> list) {
                    for (Object o : list) {
                        Map<String, Object> tc = (Map<String, Object>) o;
                        int idx = tc.get("index") instanceof Number nm ? nm.intValue() : 0;
                        ToolCallAcc acc = toolCalls.computeIfAbsent(idx, k -> new ToolCallAcc());
                        if (tc.get("id") != null) acc.id = String.valueOf(tc.get("id"));
                        Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                        if (fn != null) {
                            if (fn.get("name") != null) acc.name = String.valueOf(fn.get("name"));
                            if (fn.get("arguments") != null) acc.arguments.append(String.valueOf(fn.get("arguments")));
                        }
                    }
                }

                // ---- runaway guards ----
                if (content.length() > streamMaxChars) {
                    abortReason = "length cap (" + streamMaxChars + " chars)";
                    break;
                }
                int nl;
                while ((nl = content.indexOf("\n", scanCursor)) >= 0) {
                    String l = content.substring(scanCursor, nl).trim();
                    scanCursor = nl + 1;
                    if (!l.isEmpty() && l.equals(lastLine)) {
                        if (++lineRepeat >= LINE_REPEAT_LIMIT) {
                            abortReason = "repetition (\"" + shorten(l) + "\" repeated)";
                            break;
                        }
                    } else {
                        lastLine = l;
                        lineRepeat = 0;
                    }
                }
                if (abortReason != null) break;
                if (System.nanoTime() > deadline) {
                    abortReason = "time cap (" + streamMaxSeconds + "s)";
                    break;
                }
            }
        } finally {
            body.close();
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");

        if (abortReason != null) {
            // The turn was cut off. Treat whatever we have as final text (drop any half-parsed
            // tool call) so the loop ends gracefully instead of acting on garbage.
            System.out.println("\n[guard] stream stopped: " + abortReason);
            content.append("\n[harness stopped this response: ").append(abortReason).append("]");
            message.put("content", content.toString());
            return message;
        }

        message.put("content", content.length() == 0 ? null : content.toString());
        if (!toolCalls.isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            int i = 0;
            for (ToolCallAcc acc : toolCalls.values()) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", acc.name);
                fn.put("arguments", acc.arguments.toString());
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", acc.id != null ? acc.id : "call_" + (i++));
                call.put("type", "function");
                call.put("function", fn);
                calls.add(call);
            }
            message.put("tool_calls", calls);
        }
        return message;
    }

    // ---------------------------------------------------------------------
    // Accurate token counting (Tier 2)
    // ---------------------------------------------------------------------

    private static final String TOKENIZE_ENDPOINT = "http://localhost:8081/tokenize";

    /**
     * Ask llama-server how many tokens a piece of text really is. Returns -1 on any failure so the
     * caller can fall back to an estimate.
     */
    @SuppressWarnings("unchecked")
    public int countTokens(String text) {
        try {
            String body = mapper.writeValueAsString(Map.of("content", text == null ? "" : text));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(TOKENIZE_ENDPOINT))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return -1;
            Map<String, Object> json = mapper.readValue(resp.body(), Map.class);
            Object tokens = json.get("tokens");
            return (tokens instanceof List<?> l) ? l.size() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private HttpRequest buildRequest(List<Map<String, Object>> messages,
                                     List<Map<String, Object>> tools,
                                     boolean stream) throws Exception {
        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("model", MODEL);
        bodyMap.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            bodyMap.put("tools", tools);
            bodyMap.put("tool_choice", "auto");
        }
        bodyMap.put("temperature", 0.2);
        bodyMap.put("max_tokens", maxTokens);             // bound every single generation
        bodyMap.put("frequency_penalty", FREQUENCY_PENALTY);
        bodyMap.put("presence_penalty", PRESENCE_PENALTY);
        bodyMap.put("stream", stream);

        return HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(bodyMap)))
                .build();
    }

    private static String shorten(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }

    private static final class ToolCallAcc {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
