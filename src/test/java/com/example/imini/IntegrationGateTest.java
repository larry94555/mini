package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline coverage for {@link IntegrationGate} — the require-or-skip logic used by the driver-gated
 * persistence tests. No database needed: the pure {@code decide} core is exercised directly.
 */
public class IntegrationGateTest {

  @Test
  void availableAlwaysProceeds() {
    assertTrue(IntegrationGate.decide("t", true, false), "available + not required -> proceed");
    assertTrue(IntegrationGate.decide("t", true, true), "available + required -> proceed");
  }

  @Test
  void unavailableAndNotRequiredSkips() {
    assertFalse(IntegrationGate.decide("t", false, false), "unavailable + not required -> skip");
  }

  @Test
  void unavailableButRequiredHardFails() {
    AssertionError e = assertThrows(AssertionError.class,
        () -> IntegrationGate.decide("t", false, true));
    assertTrue(e.getMessage().contains(IntegrationGate.ENV), "failure message names the env switch");
  }

  @Test
  void defaultEnvIsNotRequired() {
    // In a normal offline/unit run the variable is unset -> not required -> tests self-skip cleanly.
    assertFalse(IntegrationGate.required(), "default (unset) is not required");
  }

  @Test
  void truthyParsing() {
    assertTrue(IntegrationGate.truthy("1"));
    assertTrue(IntegrationGate.truthy("true"));
    assertTrue(IntegrationGate.truthy("YES"));
    assertFalse(IntegrationGate.truthy(null));
    assertFalse(IntegrationGate.truthy("0"));
    assertFalse(IntegrationGate.truthy("off"));
  }
}
