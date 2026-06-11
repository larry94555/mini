package com.example.imini;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic checks for the navigation tools' walking/searching logic against a temp tree. */
class CodebaseToolsTest {

    private Path makeTree() throws Exception {
        Path root = Files.createTempDirectory("navtest");
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve("node_modules/pkg"));
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("src/main/java/Foo.java"), "package a;\nclass Foo { void bar() {} }\n");
        Files.writeString(root.resolve("src/main/java/Baz.java"), "package a;\nclass Baz { void qux() {} }\n");
        Files.writeString(root.resolve("docs/readme.md"), "# Title\nhello WORLD\n");
        Files.writeString(root.resolve("node_modules/pkg/index.js"), "class Sneaky {}\n");
        return root;
    }

    @Test
    void globFindsJavaAndPrunesIgnoredDirs() throws Exception {
        Path root = makeTree();
        List<String> hits = CodebaseTools.globFiles(root, root, "**/*.java", 200);
        assertEquals(2, hits.size());
        assertTrue(hits.contains("src/main/java/Baz.java"));
        assertTrue(hits.contains("src/main/java/Foo.java"));
        assertFalse(hits.toString().contains("node_modules"));
    }

    @Test
    void grepReturnsFileLineAndHonorsFilterAndPrune() throws Exception {
        Path root = makeTree();
        String out = CodebaseTools.grepText(root, root, Pattern.compile("class "), "**/*.java", 100, 512);
        assertTrue(out.contains("src/main/java/Foo.java:2:"));
        assertTrue(out.contains("class Foo"));
        assertFalse(out.contains("Sneaky"), "node_modules should be pruned");
    }

    @Test
    void grepIgnoreCase() throws Exception {
        Path root = makeTree();
        String out = CodebaseTools.grepText(root, root,
                Pattern.compile("world", Pattern.CASE_INSENSITIVE), null, 100, 512);
        assertTrue(out.contains("docs/readme.md:2:"));
        assertTrue(out.contains("hello WORLD"));
    }

    @Test
    void treeRespectsDepthAndPrune() throws Exception {
        Path root = makeTree();
        String t1 = CodebaseTools.tree(root, root, 1, 300);
        assertTrue(t1.contains("docs/"));
        assertTrue(t1.contains("src/"));
        assertFalse(t1.contains("node_modules"), "ignored dirs are not listed");
        assertFalse(t1.contains("Foo.java"), "depth 1 should not reach nested files");

        // src/main/java/Foo.java sits FOUR levels below the root (src -> main -> java -> Foo.java),
        // so depth 3 reaches the java/ directory but not the files inside it; depth 4 reaches them.
        String d3 = CodebaseTools.tree(root, root, 3, 300);
        assertTrue(d3.contains("java/"), "depth 3 reaches the java/ directory");
        assertFalse(d3.contains("Foo.java"), "depth 3 stops above the files inside java/");

        String d4 = CodebaseTools.tree(root, root, 4, 300);
        assertTrue(d4.contains("Foo.java"), "depth 4 reaches the java files");
    }
}
