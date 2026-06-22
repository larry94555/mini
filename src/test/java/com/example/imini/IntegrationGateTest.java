package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline coverage for the generalized {@link IntegrationGate} — the per-dependency require-or-skip logic
 * used by the driver/dependency-gated tests. No real dependency needed: the pure {@code decide} core and the
 * env-name mapping are exercised directly.
 */
public class IntegrationGateTest {

  @Test
  void availableAlwaysProceedsRegardlessOfRequire() {
    assertTrue(IntegrationGate.decide("persistence", "t", true, false), "available + not required -> proceed");
    assertTrue(IntegrationGate.decide("node", "t", true, true), "available + required -> proceed");
  }

  @Test
  void unavailableAndNotRequiredSkips() {
    assertFalse(IntegrationGate.decide("node", "t", false, false), "unavailable + not required -> skip");
    assertFalse(IntegrationGate.decide("git", "t", false, false), "unavailable + not required -> skip");
  }

  @Test
  void unavailableButRequiredHardFailsNamingTheRightSwitch() {
    AssertionError e = assertThrows(AssertionError.class,
        () -> IntegrationGate.decide("node", "t", false, true));
    assertTrue(e.getMessage().contains("IMINI_REQUIRE_NODE"), "failure names the node switch: " + e.getMessage());

    AssertionError e2 = assertThrows(AssertionError.class,
        () -> IntegrationGate.decide("persistence", "t", false, true));
    assertTrue(e2.getMessage().contains("IMINI_REQUIRE_PERSISTENCE"), "failure names the persistence switch");
  }

  @Test
  void envVarMappingPerDependency() {
    assertEquals("IMINI_REQUIRE_PERSISTENCE", IntegrationGate.envVar("persistence"));
    assertEquals("IMINI_REQUIRE_NODE", IntegrationGate.envVar("node"));
    assertEquals("IMINI_REQUIRE_GIT", IntegrationGate.envVar("git"));
    assertEquals("IMINI_REQUIRE_MODEL", IntegrationGate.envVar("model"));
  }

  @Test
  void defaultEnvIsNotRequired() {
    // In a normal offline/unit run none of the IMINI_REQUIRE_* vars are set -> nothing is required.
    assertFalse(IntegrationGate.requiredFor("persistence"), "persistence not required by default");
    assertFalse(IntegrationGate.requiredFor("node"), "node not required by default");
    assertFalse(IntegrationGate.requiredFor("git"), "git not required by default");
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
