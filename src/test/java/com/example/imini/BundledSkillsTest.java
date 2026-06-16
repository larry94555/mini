package com.example.imini;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the bundled educational skills under {@code skills/}: each SKILL.md must parse with the
 * expected front-matter name, a non-empty description, a substantial body, and an {@code $ARGUMENTS}
 * placeholder (so direct {@code /<skill-name> args} invocation passes the user's text through).
 */
class BundledSkillsTest {

    private static final List<String> BUNDLED = List.of("code-review", "debug", "batch", "loop");

    @Test
    void everyBundledSkillParsesWithItsName() throws Exception {
        for (String name : BUNDLED) {
            Path p = Path.of("skills", name, "SKILL.md");
            assertTrue(Files.isRegularFile(p), "missing bundled skill: " + p);
            SkillLibrary.Skill s = SkillLibrary.parse(Files.readString(p), name);
            assertEquals(name, s.name(), "front-matter name mismatch for " + p);
            assertTrue(s.description().length() > 10, "description too short for " + name);
            assertTrue(s.body().length() > 200, "body too short for " + name);
            assertTrue(s.body().contains("$ARGUMENTS"), "missing $ARGUMENTS placeholder in " + name);
        }
    }

    @Test
    void bundledSkillNamesAreNotReservedCommands() {
        for (String name : BUNDLED) {
            assertTrue(!SkillInvocation.isReserved(name), name + " collides with a reserved command");
        }
    }
}
