package com.example.imini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Deterministic scenarios for model behavior the harness must survive.
 *
 * <p>The tests are intentionally small. They encode the recovery contracts that matter most when a
 * weak local model emits imperfect tool calls: reject bad args, reject unknown tools, prevent path
 * escape, suppress duplicate mutations, and keep patch application atomic.
 */
class BadModelBehaviorTest {

  @Test
  void missingRequiredToolArgumentsAreRecoverableFeedback() {
    Map<String, Object> schema = Map.of(
        "type", "object",
        "properties", Map.of("path", Map.of("type", "string")),
        "required", List.of("path"));

    String error = SchemaValidator.validate("read_file", schema, Map.of());

    assertTrue(error.contains("INVALID_ARGS"), error);
    assertTrue(error.contains("missing required"), error);
  }

  @Test
  void unknownToolNameIsAnErrorButNotAProcessCrash() {
    Map<String, Tool> tools = Map.of("read_file", new Tool(
        "read_file",
        "Read a fake file.",
        Map.of("type", "object", "properties", Map.of(), "required", List.of()),
        false,
        args -> "ok"));

    String result = unknownToolResult("delete_everything", tools);

    assertTrue(result.contains("unknown tool"), result);
    assertTrue(result.contains("delete_everything"), result);
  }

  @Test
  void pathEscapeIsRejectedByWorkspaceConfinementContract() {
    Path root = Path.of("/work/project");

    assertTrue(PermissionService.isWithin(root, "src/Main.java"));
    assertFalse(PermissionService.isWithin(root, "../escape.txt"));
    assertFalse(PermissionService.isWithin(root, "/etc/passwd"));
  }

  @Test
  void repeatedMutatingCallsAreSuppressedAfterThreshold() {
    DuplicateCallGuard guard = new DuplicateCallGuard(2);

    assertFalse(guard.shouldSuppress("edit_file", Map.of("path", "a.txt", "old_str", "x", "new_str", "y")));
    assertFalse(guard.shouldSuppress("edit_file", Map.of("path", "a.txt", "old_str", "x", "new_str", "y")));
    assertTrue(guard.shouldSuppress("edit_file", Map.of("path", "a.txt", "old_str", "x", "new_str", "y")));
  }

  @Test
  void patchWithNonUniqueFindFailsBeforeWritingAnything() {
    Map<String, String> contents = Map.of("notes.txt", "same\nsame\n");
    List<BuiltinTools.EditSpec> edits = List.of(
        new BuiltinTools.EditSpec("notes.txt", "same", "changed", null));

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> BuiltinTools.applyEdits(contents, edits));

    assertTrue(ex.getMessage().contains("not unique"), ex.getMessage());
  }

  @Test
  void patchCreateThenReplaceSameFileWorksInMemoryBeforeWriting() {
    Map<String, String> contents = Map.of();
    List<BuiltinTools.EditSpec> edits = List.of(
        new BuiltinTools.EditSpec("new.txt", null, null, "hello draft\n"),
        new BuiltinTools.EditSpec("new.txt", "draft", "final", null));

    Map<String, String> result = BuiltinTools.applyEdits(contents, edits);

    assertEquals("hello final\n", result.get("new.txt"));
  }

  @Test
  void commandDenyListCatchesObviousDangerousCommand() {
    String denial = Sandbox.screen(
        "rm -rf / --no-preserve-root",
        "deny-only",
        List.of(),
        Sandbox.DEFAULT_DENY,
        2000);

    assertTrue(denial.contains("denied pattern"), denial);
  }

  private static String unknownToolResult(String name, Map<String, Tool> tools) {
    if (!tools.containsKey(name)) {
      return "ERROR: unknown tool '" + name + "'. Use only the provided tools.";
    }
    return "ok";
  }

  private static final class DuplicateCallGuard {
    private final int allowedCalls;
    private final Map<String, Integer> calls = new HashMap<>();

    DuplicateCallGuard(int allowedCalls) {
      this.allowedCalls = allowedCalls;
    }

    boolean shouldSuppress(String tool, Map<String, ?> args) {
      String signature = tool + "|" + args;
      int count = calls.merge(signature, 1, Integer::sum);
      return count > allowedCalls;
    }
  }
}
