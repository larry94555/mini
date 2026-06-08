package com.example.imini;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Before any tool overwrites or edits a file, it asks this store to take a snapshot. rewindLast()
 * restores the most recent snapshot. This is the seed of Claude Code's checkpoint/rewind: a cheap
 * safety net so an agent edit can always be undone.
 *
 * Snapshots are copied to .imini/checkpoints/ on disk; the undo order is tracked in memory, so
 * "rewind" works within a running session. (Surviving a restart would just mean persisting this
 * stack too -- a small exercise.)
 */
@Component
public class CheckpointStore {

    private static final Path DIR = Path.of(".imini", "checkpoints");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Deque<Entry> history = new ArrayDeque<>();

    public record Entry(Path original, Path snapshot, String when) {}

    /** Snapshot a file before it changes. No-op for a not-yet-existing (brand new) file. */
    public synchronized void snapshot(Path original) {
        try {
            if (!Files.exists(original)) return;
            Files.createDirectories(DIR);
            String safe = original.getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");
            Path snap = DIR.resolve(LocalDateTime.now().format(TS) + "__" + safe);
            Files.copy(original, snap, StandardCopyOption.REPLACE_EXISTING);
            history.push(new Entry(original.toAbsolutePath(), snap, LocalDateTime.now().toString()));
        } catch (IOException e) {
            System.out.println("[checkpoint] could not snapshot " + original + ": " + e.getMessage());
        }
    }

    /** Restore the most recent snapshot. */
    public synchronized String rewindLast() {
        if (history.isEmpty()) return "Nothing to rewind.";
        Entry e = history.pop();
        try {
            Files.copy(e.snapshot(), e.original(), StandardCopyOption.REPLACE_EXISTING);
            return "Rewound " + e.original() + " to its state from " + e.when();
        } catch (IOException ex) {
            return "ERROR rewinding " + e.original() + ": " + ex.getMessage();
        }
    }

    public synchronized List<String> list() {
        List<String> out = new ArrayList<>();
        int i = history.size();
        for (Entry e : history) out.add((i--) + ". " + e.original() + "  (" + e.when() + ")");
        return out;
    }
}
