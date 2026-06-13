package com.example.imini;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects the workspace's build system and turns a plan step into a suggested verification command via
 * {@link CheckLibrary}. Used by the plan executor to give weak models a check when they don't emit one.
 */
@Component
public class CheckSuggester {

    private final Sandbox sandbox;

    public CheckSuggester(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    /** maven | gradle | node | python | unknown, by probing the workspace root for marker files. */
    public String detectProject() {
        Path root = sandbox.root();
        if (exists(root, "pom.xml")) return "maven";
        if (exists(root, "build.gradle") || exists(root, "build.gradle.kts")) return "gradle";
        if (exists(root, "package.json")) return "node";
        if (exists(root, "pyproject.toml") || exists(root, "requirements.txt") || exists(root, "setup.py"))
            return "python";
        return "unknown";
    }

    /** Suggested check command for a step, or null. */
    public String suggest(String stepText) {
        return CheckLibrary.suggest(detectProject(), stepText);
    }

    private static boolean exists(Path root, String name) {
        try {
            return root != null && Files.exists(root.resolve(name));
        } catch (Exception e) {
            return false;
        }
    }
}
