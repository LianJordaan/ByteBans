package io.github.lianjordaan.byteBans.util;

import io.github.lianjordaan.byteBans.model.PunishmentData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class DatabaseUtils {
    public static final int SCHEMA_VERSION = 2;

    private static void validatePrefix(String prefix) {
        if (prefix == null || !prefix.matches("[A-Za-z][A-Za-z0-9_]{0,30}")) {
            throw new IllegalArgumentException("Invalid database table prefix");
        }
    }

    /** Idempotently extend the original schema without replacing its rows or IDs. */
    public static synchronized void migrate(Connection connection, String prefix, boolean isMySQL) throws SQLException {
        validatePrefix(prefix);
        createTables(connection, prefix, isMySQL);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix
                    + "schema_version (version INTEGER NOT NULL)");
        }
        int version;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT MAX(version) FROM " + prefix + "schema_version")) {
            version = result.next() ? result.getInt(1) : 0;
        }
        if (version > SCHEMA_VERSION) {
            throw new SQLException("ByteBans database schema " + version + " is newer than this plugin");
        }
        String table = prefix + "punishments";
        try (Statement statement = connection.createStatement()) {
            if (!columnExists(connection, table, "subject_type")) {
                statement.executeUpdate("ALTER TABLE " + table
                        + " ADD COLUMN subject_type VARCHAR(16) NOT NULL DEFAULT 'PLAYER'");
            }
            if (!columnExists(connection, table, "subject")) {
                statement.executeUpdate("ALTER TABLE " + table
                        + " ADD COLUMN subject VARCHAR(45) NOT NULL DEFAULT ''");
            }
            statement.executeUpdate("UPDATE " + table + " SET subject = uuid WHERE subject = '' AND subject_type = 'PLAYER'");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "player_addresses ("
                    + "uuid CHAR(36) PRIMARY KEY, address VARCHAR(45) NOT NULL, updated_at BIGINT NOT NULL)");
            if (version == 0) {
                statement.executeUpdate("INSERT INTO " + prefix + "schema_version (version) VALUES (" + SCHEMA_VERSION + ")");
            } else {
                statement.executeUpdate("UPDATE " + prefix + "schema_version SET version = " + SCHEMA_VERSION);
            }
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(null, null, table, column)) {
            return columns.next();
        }
    }

    // Create tables (same as before)
    public static synchronized boolean createTables(Connection connection, String prefix, boolean isMySQL) throws SQLException {
        String autoIncrement = isMySQL ? "AUTO_INCREMENT" : "AUTOINCREMENT";
        String integerType = isMySQL ? "BIGINT" : "INTEGER";

        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS %spunishments (
                    id %s PRIMARY KEY %s,
                    uuid CHAR(36) NOT NULL,
                    punisher_uuid CHAR(36),
                    type VARCHAR(16) NOT NULL,
                    reason TEXT DEFAULT '',
                    scope VARCHAR(64) DEFAULT '*',
                    start_time BIGINT NOT NULL,
                    duration BIGINT DEFAULT 0,
                    active BOOLEAN DEFAULT TRUE,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    silent BOOLEAN DEFAULT FALSE
                )
            """.formatted(prefix, integerType, autoIncrement));

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS %spunishment_updates (
                    id %s PRIMARY KEY %s,
                    punishment_id BIGINT NOT NULL,
                    action VARCHAR(16) NOT NULL,
                    timestamp BIGINT NOT NULL,
                    changed_by CHAR(36)
                )
            """.formatted(prefix, integerType, autoIncrement));

            // NEW: servers table for heartbeat
            stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS %sservers (
                id %s PRIMARY KEY %s,
                server_name VARCHAR(64) NOT NULL UNIQUE,
                last_seen BIGINT NOT NULL
            )
        """.formatted(prefix, integerType, autoIncrement));
        }
        return true;
    }

    // Generic update (INSERT, UPDATE, DELETE)
    public static synchronized void executeUpdate(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    // Execute insert and return generated ID
    public static synchronized long executeInsert(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();

            try (ResultSet generatedKeys = ps.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getLong(1);
                } else {
                    throw new SQLException("Creating record failed, no ID obtained.");
                }
            }
        }
    }

    /** Deactivate one record and insert its undo audit row as a single database transaction. */
    public static synchronized PunishmentData deactivateWithAudit(Connection connection, String prefix,
            PunishmentData target, String actor, String reason, String auditType) throws SQLException {
        validatePrefix(prefix);
        if (!connection.getAutoCommit()) throw new SQLException("ByteBans database has an unexpected open transaction");
        long now = System.currentTimeMillis();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement("UPDATE " + prefix
                    + "punishments SET active = ?, updated_at = ? WHERE id = ? AND active = ?")) {
                update.setBoolean(1, false);
                update.setLong(2, now);
                update.setLong(3, target.getId());
                update.setBoolean(4, true);
                if (update.executeUpdate() != 1) throw new SQLException("Punishment was already changed by another server");
            }
            long auditId = executeInsert(connection, "INSERT INTO " + prefix
                            + "punishments (uuid, subject_type, subject, punisher_uuid, type, reason, scope, "
                            + "start_time, duration, active, created_at, updated_at, silent) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    target.getUuid(), target.getSubjectType(), target.getSubject(), actor, auditType, reason,
                    target.getScope(), now, 0, false, now, now, true);
            executeUpdate(connection, "INSERT INTO " + prefix
                    + "punishment_updates (punishment_id, action, timestamp, changed_by) VALUES (?, ?, ?, ?)",
                    target.getId(), auditType, now, actor);
            executeUpdate(connection, "INSERT INTO " + prefix
                    + "punishment_updates (punishment_id, action, timestamp, changed_by) VALUES (?, ?, ?, ?)",
                    auditId, auditType, now, actor);
            connection.commit();
            PunishmentData audit = new PunishmentData();
            audit.setId(auditId);
            audit.setUuid(target.getUuid());
            audit.setSubjectType(target.getSubjectType());
            audit.setSubject(target.getSubject());
            audit.setPunisherUuid(actor);
            audit.setType(auditType);
            audit.setReason(reason);
            audit.setScope(target.getScope());
            audit.setStartTime(now);
            audit.setDuration(0);
            audit.setActive(false);
            audit.setCreatedAt(now);
            audit.setUpdatedAt(now);
            audit.setSilent(true);
            return audit;
        } catch (SQLException error) {
            try { connection.rollback(); }
            catch (SQLException rollback) { error.addSuppressed(rollback); }
            throw error;
        } finally {
            connection.setAutoCommit(true);
        }
    }


    /**
     * Checks if a table exists in the current database.
     *
     * @param connection the database connection
     * @param tableName the table name to check
     * @return true if the table exists, false otherwise
     * @throws SQLException if a database error occurs
     */
    public static synchronized boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getTables(null, null, tableName, null)) {
            return rs.next();
        }
    }

    /**
     * Checks if all given tables exist.
     *
     * @param connection the database connection
     * @param tables array of table names
     * @return true if all tables exist, false if any is missing
     * @throws SQLException if a database error occurs
     */
    public static synchronized boolean tablesExist(Connection connection, String... tables) throws SQLException {
        for (String table : tables) {
            if (!tableExists(connection, table)) return false;
        }
        return true;
    }

    public static synchronized List<PunishmentData> getPunishments(Connection connection, String prefix) throws SQLException {
        List<PunishmentData> punishments = new ArrayList<>();

        String sql = """
            SELECT
                id,
                uuid,
                punisher_uuid,
                type,
                reason,
                scope,
                subject_type,
                subject,
                start_time,
                duration,
                active,
                created_at,
                updated_at,
                silent
            FROM %spunishments
        """.formatted(prefix);

        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                PunishmentData data = new PunishmentData();

                data.setId(rs.getLong("id"));
                data.setUuid(rs.getString("uuid"));
                data.setPunisherUuid(rs.getString("punisher_uuid"));
                data.setType(rs.getString("type"));
                data.setReason(rs.getString("reason"));
                data.setScope(rs.getString("scope"));
                data.setSubjectType(rs.getString("subject_type"));
                data.setSubject(rs.getString("subject"));
                data.setStartTime(rs.getLong("start_time"));
                data.setDuration(rs.getLong("duration"));
                data.setActive(rs.getBoolean("active"));
                data.setCreatedAt(rs.getLong("created_at"));
                data.setUpdatedAt(rs.getLong("updated_at"));
                data.setSilent(rs.getBoolean("silent"));

                punishments.add(data);
            }
        }

        return punishments;
    }

    public static synchronized long getLastProcessedUpdateId(Connection connection, String prefix) throws SQLException {
        String sql = "SELECT id FROM " + prefix + "punishment_updates ORDER BY id DESC LIMIT 1";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong("id") : 0;
        }
    }

    public static synchronized long getLastUpdateId(Connection connection, String prefix, long lastProcessedUpdateId) throws SQLException {
        String sql = "SELECT MAX(id) AS last_id FROM " + prefix + "punishment_updates WHERE id > ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lastProcessedUpdateId);
            try (ResultSet rs = statement.executeQuery()) {
            if (rs.next()) {
                long lastId = rs.getLong("last_id");
                return rs.wasNull() ? 0 : lastId; // If no new updates, MAX returns null
            }
            return 0;
            }
        }
    }

    public static synchronized void purgeOldUpdates(Connection connection, String prefix, long retentionMinutes) throws SQLException {
        String sql = "DELETE FROM " + prefix + "punishment_updates WHERE timestamp < ?";

        long cutoff = System.currentTimeMillis() - (retentionMinutes * 60 * 1000); // Convert minutes to ms

        executeUpdate(connection, sql, cutoff);
    }

    /**
     * Updates the server heartbeat in the database.
     * If the server already exists, updates last_seen.
     * Otherwise, inserts a new row.
     */
    public static synchronized void updateServerHeartbeat(Connection conn, String prefix, String serverName) throws SQLException {
        long now = System.currentTimeMillis();

        // Check if the server already exists
        try (PreparedStatement check = conn.prepareStatement(
                "SELECT server_name FROM " + prefix + "servers WHERE server_name = ?"
        )) {
            check.setString(1, serverName);
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) {
                    // Server exists → update last_seen
                    try (PreparedStatement update = conn.prepareStatement(
                            "UPDATE " + prefix + "servers SET last_seen = ? WHERE server_name = ?"
                    )) {
                        update.setLong(1, now);
                        update.setString(2, serverName);
                        update.executeUpdate();
                    }
                } else {
                    // Server does not exist → insert new row
                    try (PreparedStatement insert = conn.prepareStatement(
                            "INSERT INTO " + prefix + "servers (server_name, last_seen) VALUES (?, ?)"
                    )) {
                        insert.setString(1, serverName);
                        insert.setLong(2, now);
                        insert.executeUpdate();
                    }
                }
            }
        }
    }


    public static synchronized List<String> getServerHeartbeats(Connection conn, String prefix, long heartbeatTimeout) throws SQLException {
        List<String> servers = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT server_name FROM " + prefix + "servers WHERE last_seen > ?"
        )) {
            ps.setLong(1, System.currentTimeMillis() - heartbeatTimeout);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    servers.add(rs.getString("server_name"));
                }
            }
        }

        return servers;
    }

    public static synchronized void cleanupOldHeartbeats(Connection conn, String prefix, long retention) throws SQLException {
        String sql = "DELETE FROM " + prefix + "servers WHERE last_seen < ?";

        long cutoff = System.currentTimeMillis() - retention;

        executeUpdate(conn, sql, cutoff);
    }

    public static synchronized void savePlayerAddress(Connection connection, String prefix, boolean isMySQL,
                                                      String uuid, String address) throws SQLException {
        String sql = isMySQL
                ? "INSERT INTO " + prefix + "player_addresses (uuid, address, updated_at) VALUES (?, ?, ?) "
                  + "ON DUPLICATE KEY UPDATE address = VALUES(address), updated_at = VALUES(updated_at)"
                : "INSERT INTO " + prefix + "player_addresses (uuid, address, updated_at) VALUES (?, ?, ?) "
                  + "ON CONFLICT(uuid) DO UPDATE SET address = excluded.address, updated_at = excluded.updated_at";
        executeUpdate(connection, sql, uuid, address, System.currentTimeMillis());
    }

    public static synchronized String getPlayerAddress(Connection connection, String prefix, String uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT address FROM " + prefix + "player_addresses WHERE uuid = ?")) {
            statement.setString(1, uuid);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }


}
