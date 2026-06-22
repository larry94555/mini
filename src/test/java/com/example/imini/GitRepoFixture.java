package com.example.imini;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Test helper that creates a fully self-contained git repository so git-gated tests pass in a clean
 * environment rather than only where git happens to be pre-configured. Every git invocation here:
 *
 * <ul>
 *   <li>sets a deterministic local identity ({@code user.email}/{@code user.name}) on the repo itself;
 *   <li>isolates from ambient configuration by pointing {@code GIT_CONFIG_GLOBAL} and
 *       {@code GIT_CONFIG_SYSTEM} at {@code /dev/null}, so a developer's or CI runner's global git config
 *       (or its absence) can't change the outcome;
 *   <li>pins {@code init.defaultBranch=main} and disables any terminal prompt.
 * </ul>
 *
 * <p>This only governs the repo <em>setup</em> the test performs; the production {@link GitInspector} still
 * runs git the normal way against {@link Sandbox#root()}.
 */
final class GitRepoFixture {

  private final Path dir;

  private GitRepoFixture(Path dir) {
    this.dir = dir;
  }

  Path path() {
    return dir;
  }

  /** Whether a usable {@code git} is on PATH. */
  static boolean available() {
    try {
      return new ProcessBuilder("git", "--version").redirectErrorStream(true).start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  /** Create an isolated, initialized repo with a deterministic identity (no initial commit). */
  static GitRepoFixture init(String prefix) throws Exception {
    Path dir = Files.createTempDirectory(prefix);
    GitRepoFixture r = new GitRepoFixture(dir);
    r.git("init", "-q", "-b", "main");
    r.git("config", "user.email", "imini-test@example.invalid");
    r.git("config", "user.name", "imini-test");
    r.git("config", "commit.gpgsign", "false");
    return r;
  }

  /** Create an isolated repo and make an initial commit of {@code file} with {@code content}. */
  static GitRepoFixture initWithCommit(String prefix, String file, String content) throws Exception {
    GitRepoFixture r = init(prefix);
    r.write(file, content);
    r.git("add", "-A");
    r.git("commit", "-qm", "chore: seed");
    return r;
  }

  Path write(String relPath, String content) throws Exception {
    Path p = dir.resolve(relPath);
    if (p.getParent() != null) {
      Files.createDirectories(p.getParent());
    }
    Files.writeString(p, content);
    return p;
  }

  /** Stage everything with {@code git add -A}. */
  void stageAll() throws Exception {
    git("add", "-A");
  }

  /** Run a git command in this repo, isolated from ambient config. Returns combined stdout/stderr. */
  String git(String... args) throws Exception {
    List<String> cmd = new ArrayList<>();
    cmd.add("git");
    for (String a : args) {
      cmd.add(a);
    }
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
    pb.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
    pb.environment().put("GIT_CONFIG_SYSTEM", "/dev/null");
    pb.environment().put("GIT_TERMINAL_PROMPT", "0");
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    p.waitFor();
    return out;
  }
}
