package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure whole-identifier reference matching + rendering for find_references. */
class SymbolRefsTest {

    @Test
    void wholeIdentifierMatchingOnly() {
        assertTrue(SymbolRefs.references("User u = new User(\"a\");", "User"));
        assertFalse(SymbolRefs.references("String username;", "user"));   // substring, not whole word
        assertFalse(SymbolRefs.references("int user_id;", "user"));       // _ is an identifier char
        assertTrue(SymbolRefs.references("return user;", "user"));
        assertFalse(SymbolRefs.references("", "user"));
        assertFalse(SymbolRefs.references("anything", ""));
    }

    @Test
    void countCountsEachOccurrence() {
        assertEquals(2, SymbolRefs.count("User a = new User();", "User"));
        assertEquals(0, SymbolRefs.count("username userId", "user"));
        assertEquals(1, SymbolRefs.count("x.user = user2;", "user"));
    }

    @Test
    void renderMarksDefsAndCountsDeclarations() {
        String out = SymbolRefs.render(List.of(
                new SymbolRefs.Ref("A.java", 1, true, "class User {"),
                new SymbolRefs.Ref("B.java", 7, false, "new User()")), "User", 50, false);
        assertTrue(out.contains("2 reference(s) to 'User'"));
        assertTrue(out.contains("(1 declaration)"));
        assertTrue(out.contains("A.java:1: [def] class User {"));
        assertTrue(out.contains("B.java:7: new User()"));
        assertFalse(out.contains("stopped at"));
    }

    @Test
    void renderHandlesEmptyAndTruncation() {
        assertEquals("(no references to 'X' found)", SymbolRefs.render(List.of(), "X", 50, false));
        String out = SymbolRefs.render(List.of(new SymbolRefs.Ref("A", 1, false, "x")), "x", 1, true);
        assertTrue(out.contains("stopped at 1 matches"));
    }
}
