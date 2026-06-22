package com.example.imini;

import java.util.Locale;

/**
 * Shared require-or-skip gate for dependency-gated tests. Instead of silently
 * {@code if (!available) return;}, a test calls {@link #proceed(String, String, boolean)} with a short
 * dependency token ({@code "persistence"}, {@code "node"}, {@code "git"}, {@code "model"}, ...):
 *
 * <ul>
 *   <li>when the dependency is available -> prints {@code [integration] <label> (<dep>) ran} and returns
 *       {@code true};
 *   <li>when unavailable and {@code IMINI_REQUIRE_<DEP>} is <strong>set</strong> (1/true/yes) -> throws, so a
 *       missing dependency in CI is a red build, not a green-but-skipped one;
 *   <li>when unavailable and the variable is unset (the default, e.g. offline/unit builds) -> prints
 *       {@code [integration] <label> (<dep>) skipped} and returns {@code false}, exactly as before.
 * </ul>
 *
 * Each dependency family has its own switch ({@code IMINI_REQUIRE_PERSISTENCE},
 * {@code IMINI_REQUIRE_NODE}, {@code IMINI_REQUIRE_GIT}, {@code IMINI_REQUIRE_MODEL}, ...), so a CI job can
 * require exactly the dependencies it provisions. The pure {@link #decide} core is unit-tested without any
 * real dependency.
 */
public final class IntegrationGate {

  static final String ENV_PREFIX = "IMINI_REQUIRE_";
  static final String MARK = "[integration] ";

  private IntegrationGate() {}

  /** True if the test should proceed; false if it should self-skip. Throws when required-but-unavailable. */
  public static boolean proceed(String dep, String label, boolean available) {
    return decide(dep, label, available, requiredFor(dep));
  }

  /** The environment variable that forces {@code dep} to be present, e.g. {@code IMINI_REQUIRE_NODE}. */
  static String envVar(String dep) {
    return ENV_PREFIX + dep.toUpperCase(Locale.ROOT);
  }

  /** Whether {@code dep} is required (CI signal). Set {@code IMINI_REQUIRE_<DEP>=1} to enforce. */
  static boolean requiredFor(String dep) {
    return truthy(System.getenv(envVar(dep)));
  }

  static boolean truthy(String v) {
    if (v == null) {
      return false;
    }
    String s = v.trim();
    return s.equals("1") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes");
  }

  /** Pure decision used by both production calls and the unit test. */
  static boolean decide(String dep, String label, boolean available, boolean required) {
    if (available) {
      System.out.println(MARK + label + " (" + dep + ") ran");
      return true;
    }
    if (required) {
      throw new AssertionError(
          MARK + label + " requires '" + dep + "' but it is unavailable. "
              + envVar(dep) + " is set, so this is a hard failure (not a silent skip).");
    }
    System.out.println(MARK + label + " (" + dep + ") skipped");
    return false;
  }
}
