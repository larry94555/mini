package com.example.imini;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live, end-to-end web search against the real engines. Gated on the {@code network} family: it self-skips
 * unless {@code IMINI_REQUIRE_NETWORK} is set (so offline/unit builds never hit the network), and runs in CI
 * where the workflow provisions network access. When it runs, it asserts a real query returns non-empty fused
 * results with provenance.
 */
public class WebSearchLiveTest {

  @Test
  void liveQueryReturnsFusedResults() {
    // available iff required: offline (unset) -> skip; CI (IMINI_REQUIRE_NETWORK=1) -> run (and must succeed).
    if (!IntegrationGate.proceed("network", "WebSearchLiveTest.liveQuery", IntegrationGate.requiredFor("network"))) {
      return;
    }
    HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build();
    WebSearchService svc = new WebSearchService(
        List.of(new InstantAnswerEngine(http), new DuckDuckGoEngine(http), new MojeekEngine(http)), null);

    List<SearchResult> results = svc.search("wikipedia");
    assertFalse(results.isEmpty(), "a real query should yield results across the engine set");
    assertTrue(results.get(0).url().startsWith("http"), "results carry real URLs");
    assertFalse(results.get(0).sourceEngine().isBlank(), "results carry provenance (source engine)");
  }
}
