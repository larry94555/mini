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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Wrapper over llama-server's OpenAI-compatible API.
 *
 *   chat / chatStream  -> the main model (8081).
 *   summaryChat        -> a (optionally cheaper) model for summarization/compaction; configured by
 *                         agent.summary-model + agent.summary-base-url. Defaults to the main model,
 *                         so it works out of the box; point it at a smaller model / second server to
 *                         do real cheap-model routing.
 *   countTokens        -> /tokenize for accurate context measurement.
 *
 * chatStream takes a cancel check so a run can be interrupted mid-generation.
 */
@Component
public class LlamaClient {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LlamaClient.class);


    private static final double FREQUENCY_PENALTY = 0.5;
    private static final double PRESENCE_PENALTY = 0.3;
    private static final int LINE_REPEAT_LIMIT = 8;

    @Value("${agent.max-tokens:1024}")
    private int maxTokens;
    @Value("${agent.stream-max-chars:12000}")
    private int streamMaxChars;
    @Value("${agent.stream-max-seconds:90}")
    private int streamMaxSeconds;
    @Value("${agent.summary-model:}")
    private String summaryModel;
    @Value("${agent.summary-base-url:}")
    private String summaryBaseUrl;
    @Value("${llama.port:8081}")
    private int port;
    @Value("${llama.client-host:localhost}")
    private String clientHost;
    @Value("${llama.alias:qwen2.5-3b-instruct}")
    private String model;
    @Value("${llama.cache-prompt:true}")
    private boolean cachePrompt;
    @Value("${llama.constrain-tools:false}")
    private boolean constrainTools;
    @Value("${llama.max-retries:2}")
    private int maxRetries;
    @Value("${llama.retry-backoff-ms:400}")
    private long retryBackoffMs;
    @Value("${llama.circuit-breaker-threshold:5}")
    private int cbThreshold;
    @Value("${llama.circuit-breaker-cooldown-ms:30000}")
    private long cbCooldownMs;
    // Initialised in @PostConstruct (after @Value injection); lateinit pattern.
    private volatile CircuitBreaker breaker;

    private final TokenBudgetService budget;
    private final Metrics metrics;   // optional; null in unit tests
    private volatile int serverCtxCache = 0; // 0 = unknown / not yet fetched
    private volatile int serverVisionCache = 0; // 0 unknown, 1 yes, -1 no

    @org.springframework.beans.factory.annotation.Autowired
    public LlamaClient(TokenBudgetService budget, Metrics metrics) {
        this.budget = budget;
        this.metrics = metrics;
    }

    /** Backward-compatible convenience constructor (no metrics); used by tests and older callers. */
    public LlamaClient(TokenBudgetService budget) {
        this(budget, null);
    }

    @jakarta.annotation.PostConstruct
    public void initBreaker() {
        breaker = new CircuitBreaker("llama", cbThreshold, cbCooldownMs);
        log.info("[llama] circuit breaker: threshold=" + cbThreshold + " cooldown=" + cbCooldownMs + "ms");
    }

    /** Current circuit-breaker state (for /healthz and metrics). */
    public CircuitBreaker.State breakerState() {
        return breaker != null ? breaker.state() : CircuitBreaker.State.CLOSED;
    }

    private String base() { return "http://" + clientHost + ":" + port; }
    private String endpoint() { return base() + "/v1/chat/completions"; }
    private String tokenizeEndpoint() { return base() + "/tokenize"; }
    private String propsEndpoint() { return base() + "/props"; }

    /** Best-effort server context window (n_ctx) via /props; cached. 0 if unknown. */
    @SuppressWarnings("unchecked")
    public int serverContext() {
        if (serverCtxCache != 0) return serverCtxCache;
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(propsEndpoint()))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 == 2) {
                Map<String, Object> json = mapper.readValue(r.body(), Map.class);
                int ctx = readCtx(json);
                serverCtxCache = ctx > 0 ? ctx : -1;
                return serverCtxCache > 0 ? serverCtxCache : 0;
            }
        } catch (Exception ignore) {
            // best-effort; fall back to budget-only enforcement
        }
        serverCtxCache = -1;
        return 0;
    }

    /** Best-effort: does the server expose a vision/multimodal capability? Cached. */
    @SuppressWarnings("unchecked")
    public boolean serverVision() {
        if (serverVisionCache != 0) return serverVisionCache == 1;
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(propsEndpoint()))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 == 2) {
                String body = r.body() == null ? "" : r.body().toLowerCase();
                boolean vision = body.contains("mmproj") || body.contains("\"vision\"")
                        || body.contains("modalities") || body.contains("clip_model") || body.contains("image");
                serverVisionCache = vision ? 1 : -1;
                return vision;
            }
        } catch (Exception ignore) {
            // best-effort
        }
        serverVisionCache = -1;
        return false;
    }

    @SuppressWarnings("unchecked")
    private static int readCtx(Map<String, Object> props) {
        Object n = props.get("n_ctx");
        if (n instanceof Number num) return num.intValue();
        Object dgs = props.get("default_generation_settings");
        if (dgs instanceof Map<?, ?> m && m.get("n_ctx") instanceof Number num) return num.intValue();
        return -1;
    }

    /** The hard prompt cap currently enforced (for diagnostics / the settings endpoint). */
    public int promptCap() {
        int ctx = serverContext();
        return budget.promptCap(ctx);
    }

    /**
     * Guarantee the outgoing prompt fits the budget: count the real tokens; if over the cap, shrink the
     * message list deterministically (truncate oversized messages, drop oldest middle ones). The real
     * /tokenize count is used for the over-budget check; the fast estimate drives the shrink so we don't
     * issue a tokenize call per step.
     */
    private List<Map<String, Object>> enforceBudget(List<Map<String, Object>> messages) {
        int cap = promptCap();
        int real = countMessages(messages);
        int measured = real >= 0 ? real : TokenBudget.estimateMessages(messages);
        if (measured <= cap) return messages;
        TokenBudget.Fitted fitted = TokenBudget.fit(messages, cap, TokenBudget::estimate);
        if (metrics != null && fitted.changed()) metrics.noteTrim("[trim] ~" + measured + " tok > cap " + cap
                + "; trimmed " + fitted.truncated() + ", dropped " + fitted.dropped() + " -> ~" + fitted.afterTokens() + " tok");
        log.warn("[token-budget] prompt ~" + measured + " tok > cap " + cap
                + " (budget " + budget.budget() + ", server n_ctx " + serverContext()
                + "); trimmed " + fitted.truncated() + " message(s), dropped " + fitted.dropped()
                + " -> ~" + fitted.afterTokens() + " tok. Consider plan mode to split a large request.");
        return fitted.messages();
    }

    /** Public: real token count of a message list via /tokenize; -1 if unavailable. */
    public int countMessagesTokens(List<Map<String, Object>> messages) { return countMessages(messages); }

    /** Real token count of a message list via /tokenize (sum of per-message content); -1 if unavailable. */
    private int countMessages(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : messages) {
            Object c = m.get("content");
            if (c != null) sb.append(c).append("\n");
        }
        return countTokens(sb.toString());
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    // ---------------------------------------------------------------------
    // Blocking
    // ---------------------------------------------------------------------

    public Map<String, Object> chat(List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools) throws Exception {
        return chatAt(endpoint(), model, enforceBudget(messages), tools);
    }

    /** Summarization/compaction call, routed to the (optionally cheaper) summary model. */
    public Map<String, Object> summaryChat(List<Map<String, Object>> messages) throws Exception {
        String m = (summaryModel == null || summaryModel.isBlank()) ? model : summaryModel;
        String url = (summaryBaseUrl == null || summaryBaseUrl.isBlank())
                ? endpoint() : summaryBaseUrl + "/v1/chat/completions";
        return chatAt(url, m, messages, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> chatAt(String url, String model,
                                       List<Map<String, Object>> messages,
                                       List<Map<String, Object>> tools) throws Exception {
        HttpRequest req = buildRequest(url, model, messages, tools, false);
        if (breaker != null && !breaker.allowCall()) {
            throw new CircuitBreaker.OpenException("[circuit:llama] open — llama-server unavailable");
        }
        HttpResponse<String> resp;
        try {
            resp = Retry.withBackoff(maxRetries + 1, retryBackoffMs, () -> {
                HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() / 100 == 5) {                 // transient server error -> retry
                    throw new java.io.IOException("llama-server " + r.statusCode() + ": " + r.body());
                }
                return r;
            });
            if (breaker != null) breaker.recordSuccess();
        } catch (java.io.IOException e) {
            if (breaker != null) breaker.recordFailure();
            throw e;
        }
        if (resp.statusCode() / 100 != 2) {                  // 4xx -> caller error, not retried
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
    // Streaming (SSE) with runaway guards + cancellation
    // ---------------------------------------------------------------------

    public Map<String, Object> chatStream(List<Map<String, Object>> messages,
                                          List<Map<String, Object>> tools,
                                          Consumer<String> onToken) throws Exception {
        return chatStream(messages, tools, onToken, () -> false);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> chatStream(List<Map<String, Object>> messages,
                                          List<Map<String, Object>> tools,
                                          Consumer<String> onToken,
                                          BooleanSupplier cancelled) throws Exception {
        messages = enforceBudget(messages);
        HttpRequest req = buildRequest(endpoint(), model, messages, tools, true);
        HttpResponse<Stream<String>> resp = http.send(req, HttpResponse.BodyHandlers.ofLines());
        if (resp.statusCode() / 100 != 2) {
            String body = resp.body().collect(Collectors.joining("\n"));
            throw new RuntimeException("llama-server error " + resp.statusCode() + ": " + body);
        }

        StringBuilder content = new StringBuilder();
        TreeMap<Integer, ToolCallAcc> toolCalls = new TreeMap<>();

        long deadline = System.nanoTime() + streamMaxSeconds * 1_000_000_000L;
        int scanCursor = 0;
        String lastLine = null;
        int lineRepeat = 0;
        String abortReason = null;

        Stream<String> body = resp.body();
        try {
            Iterator<String> it = body.iterator();
            while (it.hasNext()) {
                if (cancelled.getAsBoolean()) {
                    abortReason = "interrupted by user";
                    break;
                }
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
            log.info("\n[guard] stream stopped: " + abortReason);
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
    // Accurate token counting
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public int countTokens(String text) {
        try {
            String body = mapper.writeValueAsString(Map.of("content", text == null ? "" : text));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(tokenizeEndpoint()))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            if (breaker != null && !breaker.allowCall()) return -1; // open: skip silently
            HttpResponse<String> resp;
            try {
                resp = Retry.withBackoff(maxRetries + 1, retryBackoffMs, () -> {
                    HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() / 100 == 5) throw new java.io.IOException("tokenize " + r.statusCode());
                    return r;
                });
                if (breaker != null) breaker.recordSuccess();
            } catch (java.io.IOException e) {
                if (breaker != null) breaker.recordFailure();
                return -1;
            }
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

    private HttpRequest buildRequest(String url, String model,
                                     List<Map<String, Object>> messages,
                                     List<Map<String, Object>> tools,
                                     boolean stream) throws Exception {
        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("model", model);
        bodyMap.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            bodyMap.put("tools", tools);
            bodyMap.put("tool_choice", "auto");
            if (constrainTools) {
                String grammar = GrammarBuilder.fromTools(tools);
                if (grammar != null) bodyMap.put("grammar", grammar);
            }
        }
        bodyMap.put("temperature", 0.2);
        bodyMap.put("max_tokens", maxTokens);
        bodyMap.put("cache_prompt", cachePrompt);   // reuse the prefix KV cache for latency
        bodyMap.put("frequency_penalty", FREQUENCY_PENALTY);
        bodyMap.put("presence_penalty", PRESENCE_PENALTY);
        bodyMap.put("stream", stream);

        return HttpRequest.newBuilder()
                .uri(URI.create(url))
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
