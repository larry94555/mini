package com.example.imini;

/**
 * Shared gate for driver-gated integration tests (the persistence-backed ones). Instead of silently
 * {@code if (!db.available()) return;}, a test calls {@link #proceed(String, boolean)}:
 *
 * <ul>
 *   <li>when persistence is available -> prints a "ran against real SQLite" marker and returns {@code true};
 *   <li>when unavailable and {@code IMINI_REQUIRE_PERSISTENCE} is <strong>set</strong> (1/true/yes) -> throws,
 *       so a missing sqlite-jdbc driver in CI is a red build, not a green-but-skipped one;
 *   <li>when unavailable and the variable is unset (the default, e.g. offline/unit builds) -> prints a
 *       "skipped (no driver)" marker and returns {@code false}, exactly as before.
 * </ul>
 *
 * The pure {@link #decide(String, boolean, boolean)} core is unit-tested without a database.
 */
public final class IntegrationGate {

  static final String ENV = "IMINI_REQUIRE_PERSISTENCE";
  static final String RAN_MARKER = "ran against real SQLite";
  static final String SKIP_MARKER = "skipped (no driver)";

  private IntegrationGate() {}

  /** True if the test should proceed; false if it should self-skip. Throws when required-but-unavailable. */
  public static boolean proceed(String label, boolean available) {
    return decide(label, available, required());
  }

  /** Whether persistence is required (CI signal). Set {@code IMINI_REQUIRE_PERSISTENCE=1} to enforce. */
  static boolean required() {
    return truthy(System.getenv(ENV));
  }

  static boolean truthy(String v) {
    if (v == null) {
      return false;
    }
    String s = v.trim();
    return s.equals("1") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes");
  }

  /** Pure decision used by both production calls and the unit test. */
  static boolean decide(String label, boolean available, boolean required) {
    if (available) {
      System.out.println("[integration] " + label + " " + RAN_MARKER);
      return true;
    }
    if (required) {
      throw new AssertionError(
          "[integration] " + label + " requires persistence but it is unavailable "
              + "(no sqlite-jdbc driver?). " + ENV + " is set, so this is a hard failure.");
    }
    System.out.println("[integration] " + label + " " + SKIP_MARKER);
    return false;
  }
}
