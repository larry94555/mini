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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Snapshot-before-edit, PER-SESSION and persisted in SQLite (table {@code checkpoints}), so rewind
 * survives a restart and one session's undo history doesn't affect another's. The session is read
 * from {@link SessionContext} (set by the engine around each tool call), so the snapshot() signature
 * the file tools call is unchanged.
 *
 * Snapshots are grouped into CHANGE SETS. A tool that touches several files in one logical step
 * (e.g. apply_patch) wraps its snapshots in {@link #beginBatch()}/{@link #endBatch()} so they share a
 * group id; a plain single edit gets its own fresh group. {@link #rewindLast} undoes the whole most
 * recent group, so one apply_patch is undone in one rewind while a single edit still undoes one file.
 *
 * A brand-new file is recorded with existed=false; rewinding it deletes the file (undo a creation).
 * Snapshot bytes are copied under .imini/checkpoints/; metadata rows live in the DB (or an in-memory
 * per-session stack when persistence is unavailable).
 */
@Component
public class CheckpointStore {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CheckpointStore.class);

    private static final Path DIR = Path.of(".imini", "checkpoints");

    private final Database db;
    private final Map<String, Deque<Entry>> mem = new ConcurrentHashMap<>(); // fallback
    private final ThreadLocal<String> batch = new ThreadLocal<>();

    public record Entry(String id, String original, String snapshot, boolean existed, String when, String group) {}

    public CheckpointStore(Database db) {
        this.db = db;
    }

    /** Begin a change set: snapshots taken until {@link #endBatch()} share one group id. */
    public String beginBatch() {
        String id = UUID.randomUUID().toString();
        batch.set(id);
        return id;
    }

    /** End the current change set (subsequent snapshots get their own groups again). */
    public void endBatch() {
        batch.remove();
    }

    private String currentGroup() {
        String b = batch.get();
        return b != null ? b : UUID.randomUUID().toString();
    }

    /** Snapshot a file before it changes, scoped to the current session and change set. */
    public synchronized void snapshot(Path original) {
        String session = SessionContext.sessionId();
        String group = currentGroup();
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
                db.update("INSERT INTO checkpoints(id, session_id, path, snapshot_path, existed, created_at, group_id) "
                        + "VALUES(?,?,?,?,?,?,?)", id, session, abs, snapPath, existed ? 1 : 0, now, group);
            } else {
                mem.computeIfAbsent(session, k -> new ArrayDeque<>())
                        .push(new Entry(id, abs, snapPath, existed, Instant.ofEpochMilli(now).toString(), group));
            }
        } catch (IOException e) {
            log.warn("[checkpoint] could not snapshot " + original + ": " + e.getMessage());
        }
    }

    /** Restore (or delete) every file in the most recent change set for a session. */
    public synchronized String rewindLast(String session) {
        List<Entry> group = popLatestGroup(session);
        if (group.isEmpty()) return "Nothing to rewind for session " + session + ".";
        List<String> restored = new ArrayList<>();
        String err = null;
        for (Entry e : group) {
            try {
                Path original = Path.of(e.original());
                if (e.existed() && e.snapshot() != null) {
                    Files.copy(Path.of(e.snapshot()), original, StandardCopyOption.REPLACE_EXISTING);
                    restored.add(original.getFileName().toString());
                } else {
                    Files.deleteIfExists(original); // was newly created -> undo = remove
                    restored.add(original.getFileName() + " (removed)");
                }
            } catch (IOException ex) {
                err = "ERROR rewinding " + e.original() + ": " + ex.getMessage();
            }
        }
        if (restored.isEmpty()) return err != null ? err : "Nothing to rewind for session " + session + ".";
        String msg = restored.size() == 1
                ? "Rewound the last change (session " + session + "): " + restored.get(0)
                : "Rewound the last change set of " + restored.size() + " file(s) (session " + session + "): "
                        + String.join(", ", restored);
        return err == null ? msg : msg + "; " + err;
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

    /** Remove and return all entries in the most recent change set (group) for a session. */
    private List<Entry> popLatestGroup(String session) {
        if (db.available()) {
            List<Entry> latest = db.query(
                    "SELECT id, session_id, path, snapshot_path, existed, created_at, group_id FROM checkpoints "
                            + "WHERE session_id=? ORDER BY created_at DESC, rowid DESC LIMIT 1",
                    CheckpointStore::mapEntry, session);
            if (latest.isEmpty()) return List.of();
            Entry top = latest.get(0);
            List<Entry> group;
            if (top.group() == null) {
                group = List.of(top); // legacy row predating grouping -> undo just it
            } else {
                group = db.query(
                        "SELECT id, session_id, path, snapshot_path, existed, created_at, group_id FROM checkpoints "
                                + "WHERE session_id=? AND group_id=? ORDER BY created_at DESC, rowid DESC",
                        CheckpointStore::mapEntry, session, top.group());
            }
            for (Entry e : group) db.update("DELETE FROM checkpoints WHERE id=?", e.id());
            return group;
        }
        Deque<Entry> d = mem.get(session);
        if (d == null || d.isEmpty()) return List.of();
        String g = d.peek().group();
        List<Entry> group = new ArrayList<>();
        while (!d.isEmpty() && Objects.equals(d.peek().group(), g)) group.add(d.pop());
        return group;
    }

    private static Entry mapEntry(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Entry(rs.getString(1), rs.getString(3), rs.getString(4),
                rs.getInt(5) == 1, Instant.ofEpochMilli(rs.getLong(6)).toString(), rs.getString(7));
    }
}
