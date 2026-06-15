package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure layered-memory helpers: import parsing and depth/cycle-guarded @path expansion. */
class MemoryLoaderTest {

    @Test
    void importsParsesAtPathLinesAndIgnoresEscapes() {
        List<String> imp = MemoryLoader.imports("intro\n@./a.md\n  @b/c.md  \ntext\n@@literal\nemail me@host");
        assertEquals(List.of("./a.md", "b/c.md"), imp); // @@ escaped, mid-line @ ignored
    }

    @Test
    void expandInlinesNestedImportsAndGuardsCyclesDepthAndMissing() {
        Map<String, String> fs = new HashMap<>();
        fs.put("a.md", "A-body\n@b.md");
        fs.put("b.md", "B-body\n@a.md"); // cycles back to a
        MemoryLoader.Resolver res = (from, imp) -> {
            String key = imp.replace("./", "");
            return fs.containsKey(key) ? new MemoryLoader.Resolved(key, fs.get(key)) : null;
        };
        List<MemoryLoader.Source> diag = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add("ROOT.md");
        String out = MemoryLoader.expand("ROOT.md", "root\n@a.md\n@missing.md", res, 3, diag, visited, 0);

        assertTrue(out.contains("A-body") && out.contains("B-body"));
        // a (d1), b (d2), cycle->a (d3 skipped), missing (d1 skipped)
        assertEquals("imported via @", diag.get(0).reason());
        assertTrue(diag.stream().anyMatch(s -> s.reason().contains("cycle")));
        assertTrue(diag.stream().anyMatch(s -> s.reason().contains("not found")));
    }

    @Test
    void expandHonorsMaxDepth() {
        Map<String, String> fs = new HashMap<>();
        fs.put("deep.md", "deep");
        MemoryLoader.Resolver res = (from, imp) -> new MemoryLoader.Resolved("deep.md", fs.get("deep.md"));
        List<MemoryLoader.Source> diag = new ArrayList<>();
        // maxDepth 0 => the very first import is past the cap
        MemoryLoader.expand("ROOT.md", "@deep.md", res, 0, diag, new HashSet<>(), 0);
        assertTrue(diag.get(0).reason().contains("max import depth"));
    }
}
