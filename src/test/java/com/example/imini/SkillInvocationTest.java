package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure /skills + /<skill-name> logic: parsing, reserved names, $ARGUMENTS, and listing render. */
class SkillInvocationTest {

    @Test
    void parseSplitsNameAndArgs() {
        SkillInvocation.Parsed p = SkillInvocation.parse("/code-review  src/A.java foo");
        assertEquals("code-review", p.name());
        assertEquals("src/A.java foo", p.args());
        assertEquals("", SkillInvocation.parse("/debug").args());
        assertNull(SkillInvocation.parse("not a command"));
        assertNull(SkillInvocation.parse("/"));
    }

    @Test
    void reservedNamesAreNotSkills() {
        assertTrue(SkillInvocation.isReserved("skills"));
        assertTrue(SkillInvocation.isReserved("Memory"));
        assertFalse(SkillInvocation.isReserved("code-review"));
    }

    @Test
    void substituteReplacesPlaceholderOrAppendsArgs() {
        assertEquals("Review src/A.java now", SkillInvocation.substitute("Review $ARGUMENTS now", "src/A.java"));
        assertEquals("Review src/A.java", SkillInvocation.substitute("Review $ARGS", "src/A.java"));
        assertTrue(SkillInvocation.substitute("Do a review.", "x.java").endsWith("Arguments: x.java"));
        assertEquals("Do a review.", SkillInvocation.substitute("Do a review.", ""));
    }

    @Test
    void renderListMarksEnabledAndDescribes() {
        List<Map<String, Object>> sk = List.of(
                row("code-review", "Review a diff", true),
                row("debug", "Find a bug", false));
        String out = SkillInvocation.renderList(sk);
        assertTrue(out.contains("/code-review - Review a diff"));
        assertTrue(out.contains("(disabled) /debug"));
        assertTrue(SkillInvocation.renderList(List.of()).startsWith("No skills available"));
    }

    private static Map<String, Object> row(String name, String desc, boolean enabled) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", desc);
        m.put("enabled", enabled);
        return m;
    }
}
