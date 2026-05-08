package dev.traktseerr.state;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.Set;

/**
 * SQLite-backed deduplication store.
 * Mirrors the Python state.py module: same schema, same status values.
 */
public class StateManager {

    private static final Set<String> SKIP_STATUSES =
        Set.of("requested", "duplicate", "error_forbidden");

    private final Path dbPath;

    public StateManager(Path dbPath) {
        this.dbPath = dbPath;
        ensureParentDir();
        initSchema();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public record StateRow(String mediaType, int tmdbId, String status, String detail, double updatedAt) {}

    public StateRow getStatus(String mediaType, int tmdbId) {
        String sql = "SELECT media_type, tmdb_id, status, detail, updated_at " +
                     "FROM sync_state WHERE media_type=? AND tmdb_id=?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, mediaType);
            ps.setInt(2, tmdbId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new StateRow(rs.getString(1), rs.getInt(2),
                                    rs.getString(3), rs.getString(4), rs.getDouble(5));
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query state: " + e.getMessage(), e);
        }
    }

    public void upsert(String mediaType, int tmdbId, String status, String detail) {
        if (detail != null && detail.length() > 500) detail = detail.substring(0, 500);
        String sql = """
            MERGE INTO sync_state (media_type, tmdb_id, status, detail, updated_at)
            KEY (media_type, tmdb_id)
            VALUES (?, ?, ?, ?, ?)
            """;
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, mediaType);
            ps.setInt(2, tmdbId);
            ps.setString(3, status);
            ps.setString(4, detail);
            ps.setDouble(5, Instant.now().toEpochMilli() / 1000.0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert state: " + e.getMessage(), e);
        }
    }

    /**
     * Returns true when the item has already been successfully processed and force is false.
     */
    public boolean shouldSkip(String mediaType, int tmdbId, boolean force) {
        if (force) return false;
        StateRow row = getStatus(mediaType, tmdbId);
        return row != null && SKIP_STATUSES.contains(row.status());
    }

    public static void clearStateFile(Path dbPath) {
        if (dbPath.toFile().exists() && !dbPath.toFile().delete()) {
            throw new RuntimeException("Failed to delete state file: " + dbPath);
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private Connection connect() throws SQLException {
        // H2 appends .mv.db to the path; strip any legacy .sqlite3 extension first.
        String base = dbPath.toAbsolutePath().toString()
                           .replaceAll("\\.(sqlite3|sqlite|db)$", "");
        return DriverManager.getConnection(
            "jdbc:h2:file:" + base + ";DB_CLOSE_ON_EXIT=FALSE");
    }

    private void ensureParentDir() {
        Path parent = dbPath.getParent();
        if (parent != null) parent.toFile().mkdirs();
    }

    private void initSchema() {
        String sql = """
            CREATE TABLE IF NOT EXISTS sync_state (
                media_type TEXT    NOT NULL,
                tmdb_id    INTEGER NOT NULL,
                status     TEXT    NOT NULL,
                detail     TEXT,
                updated_at REAL    NOT NULL,
                PRIMARY KEY (media_type, tmdb_id)
            )
            """;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialise schema: " + e.getMessage(), e);
        }
    }
}
