package com.example.imini;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite persistence with a tiny forward-only migration runner. Sessions, checkpoints, and the
 * retrieval index live here instead of loose JSON files, so they survive restarts and can be queried.
 *
 * One shared connection guarded by synchronized methods (SQLite serializes writes anyway) -- fine for
 * a low-end single-node kit. If the driver/file can't be opened, {@link #available()} stays false and
 * the stores fall back to in-memory behavior, so the app still runs.
 *
 * Needs org.xerial:sqlite-jdbc on the classpath (added to pom.xml); the JDBC API itself is the JDK's.
 */
@Component
public class Database {

    private static final List<String> MIGRATIONS = List.of(
            "CREATE TABLE sessions (session_id TEXT PRIMARY KEY, messages TEXT NOT NULL, updated_at INTEGER NOT NULL)",
            "CREATE TABLE checkpoints (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, path TEXT NOT NULL, " +
                    "snapshot_path TEXT, existed INTEGER NOT NULL, created_at INTEGER NOT NULL)",
            "CREATE INDEX idx_ckpt_session ON checkpoints(session_id, created_at)",
            "CREATE TABLE mem_chunks (id TEXT PRIMARY KEY, source TEXT NOT NULL, ordinal INTEGER NOT NULL, " +
                    "text TEXT NOT NULL, embedding TEXT, indexed_at INTEGER NOT NULL)",
            "CREATE INDEX idx_chunk_source ON mem_chunks(source)");

    @Value("${persistence.enabled:true}") private boolean enabled;
    @Value("${persistence.db-path:.imini/imini.db}") private String dbPath;

    private Connection conn;
    private volatile boolean available = false;

    @PostConstruct
    public void init() {
        if (!enabled) {
            System.out.println("[db] persistence disabled; stores run in-memory.");
            return;
        }
        try {
            Path p = Path.of(dbPath);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
            }
            migrate();
            available = true;
            System.out.println("[db] SQLite ready at " + dbPath + " (schema v" + MIGRATIONS.size() + ").");
        } catch (Throwable t) {
            System.out.println("[db] could not open SQLite (" + t.getMessage() + "); using in-memory stores.");
            available = false;
        }
    }

    private void migrate() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        }
        int current = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
            if (rs.next()) current = rs.getInt(1); // NULL on empty table -> 0
        }
        for (int v = current; v < MIGRATIONS.size(); v++) {
            try (Statement st = conn.createStatement()) {
                st.execute(MIGRATIONS.get(v));
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO schema_version(version) VALUES(?)")) {
                ps.setInt(1, v + 1);
                ps.executeUpdate();
            }
            System.out.println("[db] applied migration -> v" + (v + 1));
        }
    }

    public boolean available() {
        return available;
    }

    public synchronized int update(String sql, Object... params) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("[db] update failed: " + e.getMessage());
            return -1;
        }
    }

    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    public synchronized <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        List<T> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapper.map(rs));
            }
        } catch (SQLException e) {
            System.out.println("[db] query failed: " + e.getMessage());
        }
        return out;
    }

    private void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
    }

    @PreDestroy
    public void close() {
        try {
            if (conn != null) conn.close();
        } catch (Exception ignore) {
            // best effort
        }
    }
}
