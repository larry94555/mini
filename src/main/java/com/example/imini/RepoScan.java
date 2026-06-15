package com.example.imini;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure classification helpers for {@code /init}'s repository scan. The filesystem walk lives in
 * {@link InitService}; everything that turns raw facts (root filenames, file-extension counts) into a
 * build system, language list, and suggested commands lives here so it is deterministic and testable.
 */
public final class RepoScan {

    private RepoScan() {}

    /** Facts gathered about a repository, used to render a {@code CLAUDE.md} draft. */
    public record Facts(String buildSystem, List<String> languages, List<String> buildFiles,
                        List<String> keyDirs, String buildCmd, String testCmd, int fileCount) {}

    /** Lowercased file extension without the dot (e.g. {@code "Foo.java" -> "java"}); "" if none. */
    public static String ext(String name) {
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = base.lastIndexOf('.');
        return (dot <= 0 || dot == base.length() - 1) ? "" : base.substring(dot + 1).toLowerCase();
    }

    // code extension -> language label (curated so "languages" stays meaningful)
    private static final Map<String, String> LANG = Map.ofEntries(
            Map.entry("java", "Java"), Map.entry("kt", "Kotlin"), Map.entry("py", "Python"),
            Map.entry("js", "JavaScript"), Map.entry("ts", "TypeScript"), Map.entry("jsx", "JavaScript"),
            Map.entry("tsx", "TypeScript"), Map.entry("go", "Go"), Map.entry("rs", "Rust"),
            Map.entry("rb", "Ruby"), Map.entry("c", "C"), Map.entry("h", "C"), Map.entry("cpp", "C++"),
            Map.entry("cc", "C++"), Map.entry("hpp", "C++"), Map.entry("cs", "C#"),
            Map.entry("php", "PHP"), Map.entry("swift", "Swift"), Map.entry("scala", "Scala"),
            Map.entry("sh", "Shell"));

    /** Is this a code extension we count toward "languages"? */
    public static boolean isCodeExt(String ext) {
        return LANG.containsKey(ext);
    }

    /** Languages ordered by file count (descending), then alphabetically for ties. Pure. */
    public static List<String> languages(Map<String, Integer> extCounts) {
        Map<String, Integer> byLang = new LinkedHashMap<>();
        if (extCounts != null) {
            for (Map.Entry<String, Integer> e : extCounts.entrySet()) {
                String lang = LANG.get(e.getKey());
                if (lang != null) byLang.merge(lang, e.getValue(), Integer::sum);
            }
        }
        List<String> langs = new ArrayList<>(byLang.keySet());
        langs.sort((a, b) -> {
            int c = Integer.compare(byLang.get(b), byLang.get(a));
            return c != 0 ? c : a.compareTo(b);
        });
        return langs;
    }

    /** Detect the build system from the repository's root filenames. Pure. */
    public static String detectBuildSystem(Collection<String> rootFiles) {
        List<String> f = rootFiles == null ? List.of() : new ArrayList<>(rootFiles);
        if (f.contains("pom.xml")) return "Maven";
        if (f.contains("build.gradle") || f.contains("build.gradle.kts") || f.contains("settings.gradle")) return "Gradle";
        if (f.contains("package.json")) return "npm";
        if (f.contains("pyproject.toml") || f.contains("requirements.txt") || f.contains("setup.py")) return "Python";
        if (f.contains("Cargo.toml")) return "Cargo";
        if (f.contains("go.mod")) return "Go";
        if (f.contains("Makefile")) return "Make";
        return "unknown";
    }

    /** Build files of interest present at the root (for the "Build and test" section). Pure. */
    public static List<String> buildFiles(Collection<String> rootFiles) {
        List<String> known = List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
                "package.json", "pyproject.toml", "requirements.txt", "setup.py", "Cargo.toml", "go.mod",
                "Makefile");
        List<String> out = new ArrayList<>();
        if (rootFiles != null) for (String k : known) if (rootFiles.contains(k)) out.add(k);
        return out;
    }

    public static String buildCmd(String buildSystem) {
        return switch (buildSystem) {
            case "Maven" -> "mvn -q -DskipTests package";
            case "Gradle" -> "./gradlew build -x test";
            case "npm" -> "npm install && npm run build";
            case "Python" -> "pip install -e .";
            case "Cargo" -> "cargo build";
            case "Go" -> "go build ./...";
            case "Make" -> "make";
            default -> "(describe how to build this project)";
        };
    }

    public static String testCmd(String buildSystem) {
        return switch (buildSystem) {
            case "Maven" -> "mvn test";
            case "Gradle" -> "./gradlew test";
            case "npm" -> "npm test";
            case "Python" -> "pytest";
            case "Cargo" -> "cargo test";
            case "Go" -> "go test ./...";
            case "Make" -> "make test";
            default -> "(describe how to run the tests)";
        };
    }
}
