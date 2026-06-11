package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.imini.BuiltinTools.EditSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic checks for the atomic multi-edit core behind apply_patch. */
class ApplyPatchTest {

    private Map<String, String> base() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("A.java", "class A { void x() {} }");
        m.put("B.txt", "hello world");
        return m;
    }

    @Test
    void modifiesCreatesAndChainsInOrder() {
        Map<String, String> r = BuiltinTools.applyEdits(base(), List.of(
                new EditSpec("A.java", "void x() {}", "void x() { y(); }", null),
                new EditSpec("B.txt", "world", "there", null),
                new EditSpec("C.txt", null, null, "line1\n"),
                new EditSpec("C.txt", "line1", "LINE1", null)));   // edit the just-created file
        assertEquals("class A { void x() { y(); } }", r.get("A.java"));
        assertEquals("hello there", r.get("B.txt"));
        assertEquals("LINE1\n", r.get("C.txt"));
    }

    @Test
    void emptyReplaceDeletesSnippet() {
        Map<String, String> r = BuiltinTools.applyEdits(base(),
                List.of(new EditSpec("B.txt", " world", "", null)));
        assertEquals("hello", r.get("B.txt"));
    }

    @Test
    void failsAndWritesNothingWhenFindMissing() {
        Map<String, String> in = base();
        assertThrows(IllegalArgumentException.class,
                () -> BuiltinTools.applyEdits(in, List.of(new EditSpec("A.java", "NOPE", "x", null))));
        // input map is untouched (the core works on a copy -> atomic)
        assertEquals("class A { void x() {} }", in.get("A.java"));
    }

    @Test
    void failsWhenFindNotUnique() {
        assertThrows(IllegalArgumentException.class, () -> BuiltinTools.applyEdits(
                Map.of("D.txt", "a a a"), List.of(new EditSpec("D.txt", "a", "b", null))));
    }

    @Test
    void failsWhenCreatingExistingFile() {
        assertThrows(IllegalArgumentException.class, () -> BuiltinTools.applyEdits(
                base(), List.of(new EditSpec("A.java", null, null, "new"))));
    }

    @Test
    void failsWhenModifyingMissingFile() {
        assertThrows(IllegalArgumentException.class, () -> BuiltinTools.applyEdits(
                base(), List.of(new EditSpec("Z.txt", "a", "b", null))));
    }

    @Test
    void oneBadEditAbortsTheWholeBatch() {
        Map<String, String> in = base();
        // first edit is valid, second is invalid -> the whole thing throws, original untouched
        assertThrows(IllegalArgumentException.class, () -> BuiltinTools.applyEdits(in, List.of(
                new EditSpec("A.java", "void x() {}", "void z() {}", null),
                new EditSpec("B.txt", "MISSING", "x", null))));
        assertEquals("class A { void x() {} }", in.get("A.java"));
        assertFalse(in.get("A.java").contains("void z()"));
        assertTrue(in.get("B.txt").equals("hello world"));
    }
}
