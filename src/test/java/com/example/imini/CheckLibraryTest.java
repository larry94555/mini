package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Check suggestions: per-project templates + filename extraction, and the no-model-check fallback. */
class CheckLibraryTest {

    @Test
    void suggestsCompileOrTestPerProjectType() {
        assertEquals("mvn -q -DskipTests compile", CheckLibrary.suggest("maven", "Add a /version endpoint"));
        assertEquals("mvn -q test", CheckLibrary.suggest("maven", "Write a unit test for the parser"));
        assertEquals("gradle -q compileJava", CheckLibrary.suggest("gradle", "Implement the service"));
        assertEquals("npm run build --silent", CheckLibrary.suggest("node", "Build the bundle"));
        assertEquals("npm test --silent", CheckLibrary.suggest("node", "Add a test for utils"));
        assertEquals("pytest -q", CheckLibrary.suggest("python", "Verify with a test"));
    }

    @Test
    void pythonAndUnknownUseFileExistenceWhenAFileIsNamed() {
        assertEquals("python -m py_compile utils/clock.py",
                CheckLibrary.suggest("python", "Create utils/clock.py with now()"));
        assertNull(CheckLibrary.suggest("python", "Refactor the main loop"));
        assertEquals("test -f config/app.yaml", CheckLibrary.suggest("unknown", "Create config/app.yaml"));
        assertNull(CheckLibrary.suggest("unknown", "Think about the design"));
        assertNull(CheckLibrary.suggest("maven", null));
    }

    @Test
    void firstFileRespectsExtensionFilter() {
        assertEquals("src/main.py", CheckLibrary.firstFile("edit src/main.py and README.md", ".py"));
        assertEquals("README.md", CheckLibrary.firstFile("just touch README.md", null));
        assertNull(CheckLibrary.firstFile("no files mentioned here", null));
    }

    @Test
    void suggestedCheckIsRunWhenTheModelEmitsNone() {
        List<String> verified = new ArrayList<>();
        List<List<TodoStore.Item>> snaps = new ArrayList<>();

        Planner.executeFrom("goal", Planner.toItems(List.of("Add endpoint")),
                p -> "did it\nSTEP_STATUS: done",               // model gives NO CHECK line
                p -> List.of(), snaps::add, 0, 0,
                cmd -> { verified.add(cmd); return new Planner.CheckResult(true, "ok"); },
                stepText -> "mvn -q -DskipTests compile");       // suggester

        assertEquals(List.of("mvn -q -DskipTests compile"), verified, "suggested check was verified");
        assertEquals("completed", snaps.get(snaps.size() - 1).get(0).status());
    }

    @Test
    void theModelsOwnCheckTakesPriorityOverASuggestion() {
        List<String> verified = new ArrayList<>();
        Planner.executeFrom("goal", Planner.toItems(List.of("do it")),
                p -> "done\nCHECK: test -f out.txt\nSTEP_STATUS: done",
                p -> List.of(), items -> {}, 0, 0,
                cmd -> { verified.add(cmd); return new Planner.CheckResult(true, "ok"); },
                stepText -> "mvn -q -DskipTests compile");

        assertTrue(verified.contains("test -f out.txt"));
        assertTrue(!verified.contains("mvn -q -DskipTests compile"), "suggestion not used when model declares one");
    }
}
