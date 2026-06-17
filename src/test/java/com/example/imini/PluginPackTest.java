package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure plugin-pack model: type/name validation, path safety, and target paths. */
class PluginPackTest {

    @Test
    void sanitizeNameStripsPathsAndTraversal() {
        assertEquals("passwd", PluginPack.sanitizeName("../../etc/passwd")); // no traversal survives
        assertEquals("c", PluginPack.sanitizeName("a/b/c.md"));              // dir + .md stripped
        assertEquals("code-review", PluginPack.sanitizeName("code-review"));
        assertNull(PluginPack.sanitizeName("..."));                          // nothing valid left
        assertNull(PluginPack.sanitizeName(""));
        assertNull(PluginPack.sanitizeName(null));
    }

    @Test
    void validTypeOnlyKnownKinds() {
        assertTrue(PluginPack.validType("skill"));
        assertTrue(PluginPack.validType("AGENT"));
        assertTrue(PluginPack.validType("command"));
        assertFalse(PluginPack.validType("plugin"));
        assertFalse(PluginPack.validType(null));
    }

    @Test
    void targetPathMapsByTypeAndNeutralizesTraversal() {
        assertEquals("skills/code-review/SKILL.md",
                PluginPack.targetPath(new PluginPack.Entry("skill", "code-review", "x")));
        assertEquals("agents/explore.md",
                PluginPack.targetPath(new PluginPack.Entry("agent", "explore", "x")));
        assertEquals("commands/summarize.md",
                PluginPack.targetPath(new PluginPack.Entry("command", "summarize", "x")));
        // a traversal name is reduced to a safe leaf inside the target folder
        assertEquals("commands/pwned.md",
                PluginPack.targetPath(new PluginPack.Entry("command", "../../pwned", "x")));
        assertNull(PluginPack.targetPath(new PluginPack.Entry("bogus", "x", "y")));
        assertNull(PluginPack.targetPath(new PluginPack.Entry("skill", "...", "y")));
    }

    @Test
    void summarizeCountsByTypeAndFlagsInvalid() {
        String sum = PluginPack.summarize(List.of(
                new PluginPack.Entry("skill", "a", "x"),
                new PluginPack.Entry("agent", "b", "y"),
                new PluginPack.Entry("command", "c", "z"),
                new PluginPack.Entry("bogus", "d", "w")));
        assertEquals("1 skill(s), 1 agent(s), 1 command(s), 1 skipped (invalid)", sum);
    }
}
