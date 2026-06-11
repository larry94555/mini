package com.example.imini;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic checks for symbol extraction (outline) and find_symbol across languages. */
class SymbolToolsTest {

    private boolean has(List<CodebaseTools.Symbol> syms, String kind, String name) {
        return syms.stream().anyMatch(s -> s.kind().equals(kind) && s.name().equals(name));
    }

    @Test
    void javaSymbolsAndCommentSkipping() {
        List<String> lines = List.of(
                "// a class Sneaky in a comment",
                "public class Foo {",
                "  public Foo(int x) {}",
                "  public static void main(String[] a) {}",
                "  private List<String> names() { return null; }",
                "}",
                "interface Greeter { void greet(); }");
        List<CodebaseTools.Symbol> syms = CodebaseTools.extractSymbols("Foo.java", lines);
        assertTrue(has(syms, "class", "Foo"));
        assertTrue(has(syms, "method", "main"));
        assertTrue(has(syms, "method", "names"));
        assertTrue(has(syms, "interface", "Greeter"));
        assertFalse(syms.stream().anyMatch(s -> s.name().equals("Sneaky")), "commented decl ignored");
    }

    @Test
    void pythonSymbols() {
        List<String> lines = List.of(
                "# class Commented",
                "class Widget:",
                "    def __init__(self): pass",
                "    async def fetch(self): pass",
                "def top_level(): pass");
        List<CodebaseTools.Symbol> syms = CodebaseTools.extractSymbols("util.py", lines);
        assertTrue(has(syms, "class", "Widget"));
        assertTrue(has(syms, "def", "fetch"));
        assertTrue(has(syms, "def", "top_level"));
        assertFalse(syms.stream().anyMatch(s -> s.name().equals("Commented")));
    }

    @Test
    void jsTsSymbols() {
        List<String> lines = List.of(
                "export class Service {}",
                "export function handler(req) {}",
                "export const doThing = async (x) => x + 1;",
                "interface Opts { a: number }",
                "type Id = string;");
        List<CodebaseTools.Symbol> syms = CodebaseTools.extractSymbols("app.ts", lines);
        assertTrue(has(syms, "class", "Service"));
        assertTrue(has(syms, "function", "handler"));
        assertTrue(has(syms, "function", "doThing"));
        assertTrue(has(syms, "interface", "Opts"));
        assertTrue(has(syms, "type", "Id"));
    }

    @Test
    void unsupportedExtensionIsEmpty() {
        assertTrue(CodebaseTools.extractSymbols("notes.txt", List.of("class X {}")).isEmpty());
    }

    @Test
    void findSymbolMatchesDeclarationsNotUsages() throws Exception {
        Path root = Files.createTempDirectory("symtest");
        Files.writeString(root.resolve("Foo.java"),
                "public class Foo {\n  void use() { Foo f = new Foo(); }\n}\n");
        Files.writeString(root.resolve("Bar.java"),
                "public class Bar {\n  Foo make() { return null; }\n}\n");
        String out = CodebaseTools.findSymbol(root, root, "Foo", null, 50, 512);
        assertTrue(out.contains("Foo.java:1: class Foo"));
        assertFalse(out.contains("new Foo()"), "usages are not reported, only the declaration");
    }
}
