package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure /init logic: build-system detection, language ranking, command suggestions, and draft rendering. */
class RepoScanTest {

    @Test
    void extPullsLowercaseExtensionOrEmpty() {
        assertEquals("java", RepoScan.ext("src/Foo.java"));
        assertEquals("md", RepoScan.ext("README.MD"));
        assertEquals("", RepoScan.ext("Makefile"));
        assertEquals("", RepoScan.ext(".gitignore"));
    }

    @Test
    void detectsBuildSystemFromRootFiles() {
        assertEquals("Maven", RepoScan.detectBuildSystem(List.of("pom.xml", "README.md")));
        assertEquals("Gradle", RepoScan.detectBuildSystem(List.of("build.gradle.kts")));
        assertEquals("npm", RepoScan.detectBuildSystem(List.of("package.json")));
        assertEquals("Python", RepoScan.detectBuildSystem(List.of("pyproject.toml")));
        assertEquals("unknown", RepoScan.detectBuildSystem(List.of("notes.txt")));
    }

    @Test
    void languagesRankByCountThenName() {
        Map<String, Integer> ec = new HashMap<>();
        ec.put("java", 10);
        ec.put("py", 3);
        ec.put("md", 99);  // not a code ext -> ignored
        assertEquals(List.of("Java", "Python"), RepoScan.languages(ec));
    }

    @Test
    void commandsMatchBuildSystem() {
        assertEquals("mvn test", RepoScan.testCmd("Maven"));
        assertEquals("./gradlew build -x test", RepoScan.buildCmd("Gradle"));
        assertTrue(RepoScan.buildCmd("unknown").startsWith("(describe"));
    }

    @Test
    void draftHasAllScaffoldSectionsAndMissingDetects() {
        RepoScan.Facts f = new RepoScan.Facts("Maven", List.of("Java"), List.of("pom.xml"),
                List.of("src/"), "mvn -q -DskipTests package", "mvn test", 42);
        String draft = InitDraft.render("demo", f);
        assertEquals(List.of("Project overview", "Build and test", "Layout", "Conventions", "Notes for the agent"),
                InitDraft.headings(draft));
        assertTrue(draft.contains("# demo"));
        assertTrue(draft.contains("mvn test"));
        // an existing file with only one heading is "missing" the other four
        List<String> missing = InitDraft.missingSections("# x\n## Project overview\nstuff\n", draft);
        assertEquals(List.of("Build and test", "Layout", "Conventions", "Notes for the agent"), missing);
    }
}
