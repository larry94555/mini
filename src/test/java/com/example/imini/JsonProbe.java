package com.example.imini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Detects at runtime whether a <em>real</em> JSON mapper is present, as opposed to the no-op
 * {@code ObjectMapper} used in the offline test scaffold (whose {@code readTree}/{@code readValue} return
 * empty/null). Tests that depend on real JSON-RPC parsing (MCP discovery over any transport) gate on this so
 * they self-skip cleanly offline and run for real in CI.
 *
 * <p>The probe round-trips a known JSON string through {@code ObjectMapper.readTree} and checks the parsed
 * value. The interpretation step ({@link #interpret}) is pure, so the gate logic is unit-testable without a
 * real mapper.
 */
final class JsonProbe {

  static final String PROBE_JSON = "{\"imini_probe\":\"ok\"}";
  static final String EXPECTED = "ok";

  private JsonProbe() {}

  /** True only when a real mapper actually parses the probe JSON back to its expected value. */
  static boolean realMapperAvailable() {
    try {
      ObjectMapper mapper = new ObjectMapper();
      JsonNode root = mapper.readTree(PROBE_JSON);
      JsonNode value = root == null ? null : root.get("imini_probe");
      return interpret(value == null ? null : value.asText());
    } catch (Throwable t) {
      return false;
    }
  }

  /** Pure: did the round-trip recover the expected value? (Unit-tested without a real mapper.) */
  static boolean interpret(String parsedValue) {
    return EXPECTED.equals(parsedValue);
  }
}
