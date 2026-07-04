package com.example.imini;

import java.nio.file.Path;
import java.util.function.BiFunction;

/**
 * The small, read-only handle passed to every {@link Extension} contribution method. Deliberately
 * minimal — an extension is a Spring bean and can inject any harness service it needs through its own
 * constructor, so this only carries the couple of things that are awkward to obtain otherwise:
 *
 * <ul>
 *   <li>{@link #workspaceRoot()} — the absolute workspace root the harness is operating in.
 *   <li>{@link #property(String, String)} — read a config value (e.g. an extension's own settings from
 *       {@code application.properties}) with a default.
 *   <li>{@link #log()} — an SLF4J logger named for the extension.
 * </ul>
 *
 * <p>Dependency-free (no Spring types on the surface) so extensions and tests are trivial to construct.
 */
public final class ExtensionContext {

    private final Path workspaceRoot;
    private final BiFunction<String, String, String> properties;
    private final org.slf4j.Logger log;

    public ExtensionContext(Path workspaceRoot, BiFunction<String, String, String> properties, String name) {
        this.workspaceRoot = workspaceRoot;
        this.properties = properties == null ? (k, d) -> d : properties;
        this.log = org.slf4j.LoggerFactory.getLogger("extension." + (name == null ? "?" : name));
    }

    /** The absolute, normalized workspace root the harness is confined to. */
    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /** A configuration value (from {@code application.properties}/env/system props), or {@code def}. */
    public String property(String key, String def) {
        return properties.apply(key, def);
    }

    /** An SLF4J logger named {@code extension.<name>} for this extension. */
    public org.slf4j.Logger log() {
        return log;
    }
}
