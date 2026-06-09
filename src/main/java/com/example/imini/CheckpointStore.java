package com.example.imini;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Snapshot-before-edit, now PER-SESSION and persisted in SQLite (table {@code checkpoints}), so
 * rewind survives a restart and one session's undo history doesn't affect another's. The session is
 * read from {@link SessionContext} (set by the engine around each tool call), so the snapshot()
 * signature the file tools call is unchanged.
 *
 * A brand-new file is recorded with existed=false; rewinding it deletes the file (undo a creation).
 * Snapshot bytes are copied under .imini/checkpoints/; metadata rows live in the DB (or an in-memory
 * per-session stack when persistence is unavailable).
 */
@Component
public class CheckpointStore {

    private static final Path DIR = Path.of(".imini", "checkpoints");

    private final Database db;
    private final Map<String, Deque<Entry>> mem = new ConcurrentHashMap<>(); // fallback

    public record Entry(String id, String original, String snapshot, boolean existed, String when) {}

    public CheckpointStore(Database db) {
        this.db = db;
    }

    /** Snapshot a file before it changes, scoped to the current session. */
    public synchronized void snapshot(Path original) {
        String session = SessionContext.sessionId();
        try {
            Files.createDirectories(DIR);
            boolean existed = Files.exists(original);
            String id = UUID.randomUUID().toString();
            String snapPath = null;
            if (existed) {
                String safe = original.getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");
                Path snap = DIR.resolve(id + "__" + safe);
                Files.copy(original, snap, StandardCopyOption.REPLACE_EXISTING);
                snapPath = snap.toString();
            }
            long now = System.currentTimeMillis();
            String abs = original.toAbsolutePath().toString();
            if (db.available()) {
                db.update("INSERT INTO checkpoints(id, session_id, path, snapshot_path, existed, created_at) "
                        + "VALUES(?,?,?,?,?,?)", id, session, abs, snapPath, existed ? 1 : 0, now);
            } else {
                mem.computeIfAbsent(session, k -> new ArrayDeque<>())
                        .push(new Entry(id, abs, snapPath, existed, Instant.ofEpochMilli(now).toString()));
            }
        } catch (IOException e) {
            System.out.println("[checkpoint] could not snapshot " + original + ": " + e.getMessage());
        }
    }

    /** Restore (or delete) the most recent change for a session. */
    public synchronized String rewindLast(String session) {
        Entry e = popLatest(session);
        if (e == null) return "Nothing to rewind for session " + session + ".";
        try {
            Path original = Path.of(e.original());
            if (e.existed() && e.snapshot() != null) {
                Files.copy(Path.of(e.snapshot()), original, StandardCopyOption.REPLACE_EXISTING);
                return "Rewound " + original + " to its earlier state (session " + session + ").";
            } else {
                Files.deleteIfExists(original); // was newly created -> undo = remove
                return "Removed " + original + " (it was created during session " + session + ").";
            }
        } catch (IOException ex) {
            return "ERROR rewinding " + e.original() + ": " + ex.getMessage();
        }
    }

    public synchronized List<String> list(String session) {
        if (db.available()) {
            return db.query("SELECT path, created_at FROM checkpoints WHERE session_id=? ORDER BY created_at DESC",
                    rs -> rs.getString(1) + "  (" + Instant.ofEpochMilli(rs.getLong(2)) + ")", session);
        }
        List<String> out = new ArrayList<>();
        Deque<Entry> d = mem.get(session);
        if (d != null) for (Entry e : d) out.add(e.original() + "  (" + e.when() + ")");
        return out;
    }

    private Entry popLatest(String session) {
        if (db.available()) {
            List<Entry> rows = db.query(
                    "SELECT id, session_id, path, snapshot_path, existed, created_at FROM checkpoints "
                            + "WHERE session_id=? ORDER BY created_at DESC, rowid DESC LIMIT 1",
                    rs -> new Entry(rs.getString(1), rs.getString(3), rs.getString(4),
                            rs.getInt(5) == 1, Instant.ofEpochMilli(rs.getLong(6)).toString()),
                    session);
            if (rows.isEmpty()) return null;
            Entry e = rows.get(0);
            db.update("DELETE FROM checkpoints WHERE id=?", e.id());
            return e;
        }
        Deque<Entry> d = mem.get(session);
        return (d == null || d.isEmpty()) ? null : d.pop();
    }
}
