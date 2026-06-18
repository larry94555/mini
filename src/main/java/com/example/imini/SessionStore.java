package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-turn history per session, now persisted in SQLite (table {@code sessions}) so a conversation
 * survives a restart. The whole message list is stored as a JSON blob in one row -- the same list the
 * engine works on (including compaction), so sessions stay small automatically. A small in-memory
 * cache fronts the DB and also serves as the fallback when persistence is unavailable.
 */
@Component
public class SessionStore {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SessionStore.class);


    private final Database db;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, List<Map<String, Object>>> cache = new ConcurrentHashMap<>();
    private final Map<String, String> owners = new ConcurrentHashMap<>(); // session_id -> owner
    private final Map<String, String> titles = new ConcurrentHashMap<>(); // session_id -> display title
    private final Map<String, java.util.Set<String>> shares = new ConcurrentHashMap<>(); // session_id -> readers

    public SessionStore(Database db) {
        this.db = db;
    }

    /** Returns stored history for a session, or null if it doesn't exist yet. */
    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> get(String id) {
        if (cache.containsKey(id)) return cache.get(id);
        if (db.available()) {
            List<String> rows = db.query("SELECT messages FROM sessions WHERE session_id=?",
                    rs -> rs.getString(1), id);
            if (!rows.isEmpty()) {
                try {
                    List<Map<String, Object>> h = mapper.readValue(rows.get(0), List.class);
                    cache.put(id, h);
                    return h;
                } catch (Exception e) {
                    log.warn("[session] could not parse '" + id + "': " + e.getMessage());
                }
            }
        }
        return null;
    }

    public synchronized void save(String id, List<Map<String, Object>> history) {
        cache.put(id, history);
        if (db.available()) {
            try {
                String json = mapper.writeValueAsString(history);
                db.update("INSERT INTO sessions(session_id, messages, updated_at) VALUES(?,?,?) "
                                + "ON CONFLICT(session_id) DO UPDATE SET messages=excluded.messages, updated_at=excluded.updated_at",
                        id, json, System.currentTimeMillis());
            } catch (Exception e) {
                log.warn("[session] could not save '" + id + "': " + e.getMessage());
            }
        }
    }

    /** Record the owner of a session the first time it is used (no-op if already owned). */
    public synchronized void claim(String id, String user) {
        if (id == null || id.isBlank() || user == null || user.isBlank()) return;
        if (owner(id) != null) return;
        owners.put(id, user);
        if (db.available()) {
            try {
                db.update("INSERT INTO session_owners(session_id, owner) VALUES(?,?) "
                        + "ON CONFLICT(session_id) DO NOTHING", id, user);
            } catch (Exception e) {
                log.warn("[session] could not set owner for '" + id + "': " + e.getMessage());
            }
        }
    }

    /** The owning user of a session, or null if unowned (legacy/new). */
    public synchronized String owner(String id) {
        if (owners.containsKey(id)) return owners.get(id);
        if (db.available()) {
            List<String> r = db.query("SELECT owner FROM session_owners WHERE session_id=?",
                    rs -> rs.getString(1), id);
            if (!r.isEmpty()) { owners.put(id, r.get(0)); return r.get(0); }
        }
        return null;
    }

    /** Users a session has been explicitly shared with (read access), merged from cache and DB. */
    public synchronized java.util.Set<String> readers(String id) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>(shares.getOrDefault(id, java.util.Set.of()));
        if (db.available()) {
            out.addAll(db.query("SELECT grantee FROM session_shares WHERE session_id=?",
                    rs -> rs.getString(1), id));
        }
        return out;
    }

    /** Grant read access to a session. */
    public synchronized void share(String id, String grantee) {
        if (id == null || id.isBlank() || grantee == null || grantee.isBlank()) return;
        shares.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(grantee);
        if (db.available()) {
            try {
                db.update("INSERT INTO session_shares(session_id, grantee, created_at) VALUES(?,?,?) "
                        + "ON CONFLICT(session_id, grantee) DO NOTHING", id, grantee, System.currentTimeMillis());
            } catch (Exception e) {
                log.warn("[session] could not share '" + id + "': " + e.getMessage());
            }
        }
    }

    /** Revoke a previously granted read access. */
    public synchronized void unshare(String id, String grantee) {
        java.util.Set<String> set = shares.get(id);
        if (set != null) set.remove(grantee);
        if (db.available()) {
            try {
                db.update("DELETE FROM session_shares WHERE session_id=? AND grantee=?", id, grantee);
            } catch (Exception e) {
                log.warn("[session] could not unshare '" + id + "': " + e.getMessage());
            }
        }
    }

    /** Set (overwrite) the owner of a session. Unlike {@link #claim}, this replaces an existing owner. */
    public synchronized void setOwner(String id, String user) {
        if (id == null || id.isBlank() || user == null || user.isBlank()) return;
        owners.put(id, user);
        if (db.available()) {
            try {
                db.update("INSERT INTO session_owners(session_id, owner) VALUES(?,?) "
                        + "ON CONFLICT(session_id) DO UPDATE SET owner=excluded.owner", id, user);
            } catch (Exception e) {
                log.warn("[session] could not set owner for '" + id + "': " + e.getMessage());
            }
        }
    }

    /** Transfer ownership to {@code newOwner}; the previous owner keeps read access. Returns the old owner. */
    public synchronized String transfer(String id, String newOwner) {
        String previous = owner(id);
        setOwner(id, newOwner);
        if (previous != null && !previous.equals(newOwner)) share(id, previous);
        unshare(id, newOwner); // the new owner need not also appear as a reader
        return previous;
    }

    /** Set (or clear, when blank) a session's friendly title. */
    public synchronized void setTitle(String id, String title) {
        String clean = SessionNaming.cleanTitle(title);
        if (clean.isEmpty()) {
            titles.remove(id);
            if (db.available()) {
                try { db.update("DELETE FROM session_titles WHERE session_id=?", id); }
                catch (Exception e) { log.warn("[session] could not clear title for '" + id + "': " + e.getMessage()); }
            }
            return;
        }
        titles.put(id, clean);
        if (db.available()) {
            try {
                db.update("INSERT INTO session_titles(session_id, title) VALUES(?,?) "
                        + "ON CONFLICT(session_id) DO UPDATE SET title=excluded.title", id, clean);
            } catch (Exception e) {
                log.warn("[session] could not set title for '" + id + "': " + e.getMessage());
            }
        }
    }

    /** A session's title, or "" if none set. */
    public synchronized String title(String id) {
        if (titles.containsKey(id)) return titles.get(id);
        if (db.available()) {
            try {
                List<String> r = db.query("SELECT title FROM session_titles WHERE session_id=?",
                        rs -> rs.getString(1), id);
                if (!r.isEmpty()) { titles.put(id, r.get(0)); return r.get(0); }
            } catch (Exception e) {
                log.warn("[session] could not read title for '" + id + "': " + e.getMessage());
            }
        }
        return "";
    }

    /** Titles for several sessions (id -> title); ids with no title are omitted. */
    public synchronized Map<String, String> titlesFor(List<String> ids) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String id : ids) {
            String t = title(id);
            if (!t.isEmpty()) out.put(id, t);
        }
        return out;
    }

    public synchronized List<String> list() {
        if (db.available()) {
            List<String> ids = db.query("SELECT session_id FROM sessions ORDER BY updated_at DESC",
                    rs -> rs.getString(1));
            for (String k : cache.keySet()) if (!ids.contains(k)) ids.add(k);
            return ids;
        }
        return new ArrayList<>(cache.keySet());
    }

    /** Pure predicate: a session is expired if it has been idle (no update) for more than ttlMs. */
    public static boolean isExpired(long updatedAt, long nowMs, long ttlMs) {
        if (ttlMs <= 0) return false; // ttl disabled
        return (nowMs - updatedAt) > ttlMs;
    }

    /**
     * Delete sessions idle longer than {@code ttlMs}, plus their owner/share/title rows. Returns the number
     * of sessions pruned. ttlMs <= 0 disables pruning (returns 0). Pinned/owned state travels with the
     * session id, so removing the session row and its satellites fully retires it.
     */
    public synchronized int pruneExpired(long ttlMs, long nowMs) {
        if (ttlMs <= 0) return 0;
        long cutoff = nowMs - ttlMs;
        int pruned = 0;
        if (db.available()) {
            List<String> expired = db.query(
                    "SELECT session_id FROM sessions WHERE updated_at < ?", rs -> rs.getString(1), cutoff);
            for (String id : expired) {
                db.update("DELETE FROM sessions WHERE session_id=?", id);
                db.update("DELETE FROM session_owners WHERE session_id=?", id);
                db.update("DELETE FROM session_shares WHERE session_id=?", id);
                db.update("DELETE FROM session_titles WHERE session_id=?", id);
                cache.remove(id);
                owners.remove(id);
                titles.remove(id);
                shares.remove(id);
                pruned++;
            }
        }
        if (pruned > 0) log.info("[sessions] pruned " + pruned + " session(s) idle > " + ttlMs + "ms");
        return pruned;
    }

    /** Age/size distribution of stored sessions for the /sessions/summary endpoint. */
    public synchronized Map<String, Object> summary(long nowMs) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        long total = 0, idleOverDay = 0, idleOverWeek = 0;
        long oldestUpdated = Long.MAX_VALUE, newestUpdated = 0;
        if (db.available()) {
            List<long[]> rows = db.query("SELECT updated_at, length(messages) FROM sessions",
                    rs -> new long[]{rs.getLong(1), rs.getLong(2)});
            long totalBytes = 0;
            for (long[] r : rows) {
                total++;
                long age = nowMs - r[0];
                if (age > 86_400_000L) idleOverDay++;
                if (age > 604_800_000L) idleOverWeek++;
                if (r[0] < oldestUpdated) oldestUpdated = r[0];
                if (r[0] > newestUpdated) newestUpdated = r[0];
                totalBytes += r[1];
            }
            out.put("totalBytes", totalBytes);
        } else {
            total = cache.size();
        }
        out.put("total", total);
        out.put("idleOverOneDay", idleOverDay);
        out.put("idleOverOneWeek", idleOverWeek);
        out.put("oldestUpdatedAt", total == 0 ? 0 : oldestUpdated);
        out.put("newestUpdatedAt", newestUpdated);
        return out;
    }
}
