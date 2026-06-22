package com.example.imini;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Test helper that reliably locates the bundled MCP stub server and produces a connectable command, so
 * Node/MCP-gated tests pass wherever they run rather than only when launched from the module root.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>the test <strong>classpath</strong> ({@code /mcp/stub-server.js}, copied to a temp file) — this is
 *       how Maven exposes {@code src/test/resources}, so it works regardless of the working directory;
 *   <li>a few well-known filesystem locations as a fallback (module root, {@code target/test-classes}).
 * </ol>
 *
 * <p>Mirrors {@code GitRepoFixture}: a small, deterministic preflight shared by the gated tests.
 */
final class McpStubFixture {

  static final String RESOURCE = "/mcp/stub-server.js";

  private McpStubFixture() {}

  /** Whether a usable {@code node} is on PATH. */
  static boolean nodeAvailable() {
    try {
      return new ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  /** True only when node is present AND the stub server can be located — the gate's availability probe. */
  static boolean available() {
    if (!nodeAvailable()) {
      return false;
    }
    try {
      return stubScript() != null;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Absolute path to a runnable copy of the stub server. Prefers the classpath resource (copied to a temp
   * file); falls back to known filesystem locations. Returns {@code null} if it cannot be found.
   */
  static Path stubScript() throws Exception {
    try (InputStream in = McpStubFixture.class.getResourceAsStream(RESOURCE)) {
      if (in != null) {
        Path tmp = Files.createTempFile("imini-mcp-stub-", ".js");
        Files.write(tmp, in.readAllBytes());
        tmp.toFile().deleteOnExit();
        return tmp.toAbsolutePath();
      }
    }
    for (String c : new String[] {
        "src/test/resources/mcp/stub-server.js",
        "target/test-classes/mcp/stub-server.js"
    }) {
      Path p = Path.of(c);
      if (Files.exists(p)) {
        return p.toAbsolutePath();
      }
    }
    return null;
  }

  /** A {@code mcp.connect(...)} config that launches the stub over stdio. */
  static Map<String, Object> command() throws Exception {
    Path stub = stubScript();
    if (stub == null) {
      throw new IllegalStateException("stub-server.js not found on the classpath or known paths");
    }
    return Map.of("command", "node", "args", List.of(stub.toString()));
  }
}
