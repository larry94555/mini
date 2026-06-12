package com.example.imini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Tiny deterministic end-to-end harness tests that do not need a live llama-server.
 *
 * <p>These tests intentionally use a small scripted fake model instead of the full Spring wiring. The
 * educational point is the same: a model emits a tool call, the harness validates it, the tool runs,
 * the result goes back into the transcript, and the model eventually answers.
 */
class FakeModelHarnessTest {

  @Test
  void fakeModelCanReadThenEditThenAnswer() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("notes.txt", "status: draft\n");

    Map<String, Tool> tools = new LinkedHashMap<>();
    tools.put("read_file", readFileTool(files));
    tools.put("edit_file", editFileTool(files));

    FakeModel model = new FakeModel(
        Step.call("read_file", Map.of("path", "notes.txt")),
        Step.call(
            "edit_file",
            Map.of("path", "notes.txt", "old_str", "status: draft", "new_str", "status: final")),
        Step.answer("Updated notes.txt and verified the target text."));

    TinyHarness harness = new TinyHarness(model, tools);
    String answer = harness.run("Change notes.txt from draft to final.");

    assertEquals("status: final\n", files.get("notes.txt"));
    assertTrue(answer.contains("Updated notes.txt"));
    assertTrue(harness.transcript().stream().anyMatch(s -> s.contains("TOOL read_file -> status: draft")));
    assertTrue(harness.transcript().stream().anyMatch(s -> s.contains("TOOL edit_file -> Edited notes.txt")));
  }

  @Test
  void invalidArgsBecomeFeedbackInsteadOfToolExecution() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("notes.txt", "status: draft\n");

    Map<String, Tool> tools = Map.of("read_file", readFileTool(files));

    FakeModel model = new FakeModel(
        Step.call("read_file", Map.of()),
        Step.call("read_file", Map.of("path", "notes.txt")),
        Step.answer("Recovered after the harness reported the missing path."));

    TinyHarness harness = new TinyHarness(model, tools);
    String answer = harness.run("Read notes.txt, but first make a bad call.");

    assertTrue(answer.contains("Recovered"));
    assertTrue(harness.transcript().stream().anyMatch(s -> s.contains("INVALID_ARGS")));
    assertTrue(harness.transcript().stream().anyMatch(s -> s.contains("TOOL read_file -> status: draft")));
  }

  private static Tool readFileTool(Map<String, String> files) {
    return new Tool(
        "read_file",
        "Read a fake file.",
        schema(Map.of("path", prop("string")), "path"),
        false,
        args -> files.getOrDefault(String.valueOf(args.get("path")), "ERROR: not found"));
  }

  private static Tool editFileTool(Map<String, String> files) {
    return new Tool(
        "edit_file",
        "Replace text in a fake file.",
        schema(
            Map.of(
                "path", prop("string"),
                "old_str", prop("string"),
                "new_str", prop("string")),
            "path",
            "old_str",
            "new_str"),
        true,
        args -> {
          String path = String.valueOf(args.get("path"));
          String content = files.get(path);
          if (content == null) {
            return "ERROR: file not found";
          }
          String oldStr = String.valueOf(args.get("old_str"));
          String newStr = String.valueOf(args.get("new_str"));
          if (!content.contains(oldStr)) {
            return "ERROR: old_str not found";
          }
          files.put(path, content.replace(oldStr, newStr));
          return "Edited " + path;
        });
  }

  private static Map<String, Object> prop(String type) {
    return Map.of("type", type);
  }

  private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    schema.put("required", List.of(required));
    return schema;
  }

  private record Step(String toolName, Map<String, Object> args, String answer) {
    static Step call(String toolName, Map<String, Object> args) {
      return new Step(toolName, args, null);
    }

    static Step answer(String answer) {
      return new Step(null, Map.of(), answer);
    }

    boolean isAnswer() {
      return answer != null;
    }
  }

  private static final class FakeModel {
    private final List<Step> steps;
    private int next;

    FakeModel(Step... steps) {
      this.steps = List.of(steps);
    }

    Step next() {
      if (next >= steps.size()) {
        return Step.answer("[no more scripted steps]");
      }
      return steps.get(next++);
    }
  }

  private static final class TinyHarness {
    private final FakeModel model;
    private final Map<String, Tool> tools;
    private final List<String> transcript = new ArrayList<>();

    TinyHarness(FakeModel model, Map<String, Tool> tools) {
      this.model = model;
      this.tools = tools;
    }

    String run(String userMessage) {
      transcript.add("USER " + userMessage);

      for (int i = 0; i < 8; i++) {
        Step step = model.next();
        if (step.isAnswer()) {
          transcript.add("ASSISTANT " + step.answer());
          return step.answer();
        }

        Tool tool = tools.get(step.toolName());
        if (tool == null) {
          transcript.add("TOOL_ERROR unknown tool " + step.toolName());
          continue;
        }

        String validationError = SchemaValidator.validate(tool.name, tool.parameters, step.args());
        if (validationError != null) {
          transcript.add(validationError);
          continue;
        }

        Function<Map, String> executor = tool.executor;
        String result = executor.apply(new LinkedHashMap<>(step.args()));
        transcript.add("TOOL " + step.toolName() + " -> " + result.strip());
      }

      return "[stopped: fake model did not answer]";
    }

    List<String> transcript() {
      return transcript;
    }
  }
}
