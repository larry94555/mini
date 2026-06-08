package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper over llama-server's OpenAI-compatible POST /v1/chat/completions.
 *
 * This is the ONLY place the "model" exists from the harness's point of view: a stateless
 * function from (messages + tool specs) to one assistant message. It cannot remember anything
 * between calls and cannot take any action -- it can only return text and/or tool-call requests.
 */
@Component
public class LlamaClient {

    private static final String ENDPOINT = "http://localhost:8081/v1/chat/completions";
    private static final String MODEL = "qwen2.5-3b-instruct"; // matches --alias

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param messages full conversation so far (system, user, assistant, tool ...)
     * @param tools    OpenAI-style tool specs the model is allowed to call
     * @return the raw assistant "message" object from choices[0].message
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> chat(List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        body.put("temperature", 0.2);
        body.put("stream", false);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

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
}
