package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline coverage for the JSON/real-mapper gate: the pure probe interpretation and the {@code "json"}
 * dependency wiring in {@link IntegrationGate}. No real Jackson required.
 */
public class JsonProbeTest {

  @Test
  void interpretRecognizesOnlyTheExpectedValue() {
    assertTrue(JsonProbe.interpret(JsonProbe.EXPECTED), "round-trip recovered 'ok' -> real mapper");
    assertFalse(JsonProbe.interpret(null), "null (no-op mapper) -> not available");
    assertFalse(JsonProbe.interpret(""), "empty (stub mapper) -> not available");
    assertFalse(JsonProbe.interpret("nope"), "wrong value -> not available");
  }

  @Test
  void jsonGateEnvMappingAndDecision() {
    assertEquals("IMINI_REQUIRE_JSON", IntegrationGate.envVar("json"), "json -> IMINI_REQUIRE_JSON");
    // available -> proceed regardless of require
    assertTrue(IntegrationGate.decide("json", "t", true, false));
    assertTrue(IntegrationGate.decide("json", "t", true, true));
    // unavailable + not required -> skip
    assertFalse(IntegrationGate.decide("json", "t", false, false));
    // unavailable + required -> hard fail naming the switch
    AssertionError e = assertThrows(AssertionError.class, () -> IntegrationGate.decide("json", "t", false, true));
    assertTrue(e.getMessage().contains("IMINI_REQUIRE_JSON"), "failure names the json switch: " + e.getMessage());
  }

  @Test
  void jsonNotRequiredByDefault() {
    assertFalse(IntegrationGate.requiredFor("json"), "json not required unless IMINI_REQUIRE_JSON is set");
  }
}
