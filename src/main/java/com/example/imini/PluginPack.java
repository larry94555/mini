package com.example.imini;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * Pure model + path/name rules for plugin packs -- a portable bundle of skills, subagents, and slash
 * commands that can be exported from one workspace and installed into another. The harness's
 * extensibility (skills/, agents/, commands/) is just Markdown files, so a "pack" is a small manifest
 * plus those files' contents. This class is dependency-free (JSON (de)serialization lives in
 * {@link PluginService}); it owns validation so installs can never write outside the intended folders.
 */
public final class PluginPack {

    private PluginPack() {}

    /** Manifest format identifier embedded in exported packs. */
    public static final String FORMAT = "imini-plugin/1";

    public static final List<String> TYPES = List.of("skill", "agent", "command");

    /** One file in a pack. {@code type} in {skill,agent,command}; {@code name} is a bare id. */
    public record Entry(String type, String name, String content) {}

    /** A pack: a name/version/description and its entries. */
    public record Pack(String format, String name, String version, String description, List<Entry> entries) {}

    /** Lowercase hex SHA-256 of the (UTF-8) text. Pure. */
    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Does {@code content} match {@code expectedSha256}? A null/blank expected hash is "unpinned" and
     * accepted (the caller should warn). Comparison is case-insensitive. Mirrors the remote-skill check.
     */
    public static boolean matches(String expectedSha256, String content) {
        if (expectedSha256 == null || expectedSha256.isBlank()) return true;
        return sha256(content).equalsIgnoreCase(expectedSha256.trim());
    }

    public static boolean validType(String type) {
        return type != null && TYPES.contains(type.toLowerCase(Locale.ROOT));
    }

    /**
     * Sanitize an entry name to a safe bare id: letters, digits, dot, dash, underscore only -- no path
     * separators, no traversal. Returns null if nothing valid remains.
     */
    public static String sanitizeName(String raw) {
        if (raw == null) return null;
        String n = raw.trim();
        // strip any directory part defensively, then whitelist characters
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        if (n.endsWith(".md")) n = n.substring(0, n.length() - 3);
        n = n.replaceAll("[^A-Za-z0-9._-]", "");
        while (n.startsWith(".")) n = n.substring(1); // no hidden/relative names
        return n.isEmpty() ? null : n;
    }

    /** The workspace-relative target path for an entry, or null if the entry is invalid. */
    public static String targetPath(Entry e) {
        if (e == null || !validType(e.type())) return null;
        String name = sanitizeName(e.name());
        if (name == null) return null;
        return switch (e.type().toLowerCase(Locale.ROOT)) {
            case "skill" -> "skills/" + name + "/SKILL.md";
            case "agent" -> "agents/" + name + ".md";
            case "command" -> "commands/" + name + ".md";
            default -> null;
        };
    }

    /** Counts of valid entries by type, for a short install/export summary. Pure. */
    public static String summarize(List<Entry> entries) {
        int sk = 0, ag = 0, cm = 0, bad = 0;
        if (entries != null) for (Entry e : entries) {
            if (targetPath(e) == null) { bad++; continue; }
            switch (e.type().toLowerCase(Locale.ROOT)) {
                case "skill" -> sk++;
                case "agent" -> ag++;
                case "command" -> cm++;
                default -> bad++;
            }
        }
        return sk + " skill(s), " + ag + " agent(s), " + cm + " command(s)"
                + (bad > 0 ? ", " + bad + " skipped (invalid)" : "");
    }
}
