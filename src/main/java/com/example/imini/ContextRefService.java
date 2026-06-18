package com.example.imini;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Resolves {@code @file} / {@code @directory} references in a user message and inlines them into the
 * text the model sees. Resolution is confined to the workspace root (no path traversal) and capped by
 * size/count; unresolved tokens are left untouched. Parsing + rendering are pure ({@link ContextRefs});
 * only the filesystem access lives here.
 */
@Component
public class ContextRefService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContextRefService.class);

    @Value("${context.refs.enabled:true}") private boolean enabled;
    @Value("${context.refs.max-files:10}") private int maxFiles;
    @Value("${context.refs.max-file-kb:64}") private int maxFileKb;
    @Value("${context.refs.max-total-kb:256}") private int maxTotalKb;
    @Value("${context.refs.max-dir-entries:100}") private int maxDirEntries;
    // A file over max-file-kb is normally skipped. With folding on, files up to max-fold-file-kb are
    // instead read and folded (chunk -> summarize -> reduce via ContextManager) so their gist still enters
    // context; files larger than max-fold-file-kb are still skipped (a hard read cap).
    @Value("${context.refs.fold-large-files:true}") private boolean foldLargeFiles;
    @Value("${context.refs.max-fold-file-kb:512}") private int maxFoldFileKb;

    private final Path root = Path.of("").toAbsolutePath().normalize();
    private final ContextManager context;

    public ContextRefService(ContextManager context) {
        this.context = context;
    }

    /** What to do with a referenced file given its size and the configured caps. */
    enum LargeFileAction { INLINE, FOLD, SKIP }

    static LargeFileAction largeFileAction(long sizeBytes, int maxInlineKb, int maxFoldKb, boolean foldEnabled) {
        if (sizeBytes <= (long) maxInlineKb * 1024) return LargeFileAction.INLINE;
        if (foldEnabled && sizeBytes <= (long) maxFoldKb * 1024) return LargeFileAction.FOLD;
        return LargeFileAction.SKIP;
    }

    /** The augmented message (with a referenced-context block appended) plus trace notes. */
    public record Expansion(String text, List<String> attached, List<String> skipped) {}

    public Expansion expand(String message) {
        if (!enabled || message == null || message.indexOf('@') < 0) {
            return new Expansion(message, List.of(), List.of());
        }
        List<ContextRefs.Resolved> resolved = new ArrayList<>();
        List<String> attached = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int totalBytes = 0;

        for (String ref : ContextRefs.parse(message)) {
            if (resolved.size() >= maxFiles) {
                skipped.add(ref + " (max " + maxFiles + " refs)");
                continue;
            }
            Path abs;
            try {
                abs = root.resolve(ref).normalize();
            } catch (Exception e) {
                skipped.add(ref + " (bad path)");
                continue;
            }
            if (!abs.startsWith(root) || !Files.exists(abs)) {
                continue; // not a workspace path -> leave the token as-is (e.g. an @mention)
            }
            try {
                if (Files.isDirectory(abs)) {
                    String listing = listDir(abs);
                    int entries = listing.isEmpty() ? 0 : (int) listing.lines().count();
                    resolved.add(new ContextRefs.Resolved(ref, "dir", listing, 0, entries));
                    attached.add(ref + " (directory, " + entries + " entries)");
                } else {
                    long size = Files.size(abs);
                    LargeFileAction action = largeFileAction(size, maxFileKb, maxFoldFileKb, foldLargeFiles);
                    if (action == LargeFileAction.SKIP) {
                        skipped.add(ref + " (exceeds " + maxFileKb + "KB"
                                + (foldLargeFiles ? "; over fold cap " + maxFoldFileKb + "KB" : "") + ")");
                        continue;
                    }
                    String raw = Files.readString(abs);
                    String content = action == LargeFileAction.FOLD ? context.condenseToolResult(raw) : raw;
                    if (totalBytes + content.length() > (long) maxTotalKb * 1024) {
                        skipped.add(ref + " (total budget " + maxTotalKb + "KB exceeded)");
                        continue;
                    }
                    totalBytes += content.length();
                    resolved.add(new ContextRefs.Resolved(ref, "file", content, content.length(), 0));
                    attached.add(action == LargeFileAction.FOLD
                            ? ref + " (file, folded from " + size + " bytes -> " + content.length() + ")"
                            : ref + " (file, " + content.length() + " bytes)");
                }
            } catch (Exception e) {
                skipped.add(ref + " (unreadable)");
            }
        }
        String text = message + ContextRefs.block(resolved);
        return new Expansion(text, attached, skipped);
    }

    private String listDir(Path dir) {
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.sorted().limit(maxDirEntries).forEach(p -> {
                String n = p.getFileName().toString();
                names.add(Files.isDirectory(p) ? "- " + n + "/" : "- " + n);
            });
        } catch (Exception e) {
            log.warn("[context] could not list " + dir + ": " + e.getMessage());
        }
        return String.join("\n", names);
    }
}
