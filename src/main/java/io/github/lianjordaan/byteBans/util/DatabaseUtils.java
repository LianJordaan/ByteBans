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

    // Create tables (same as before)
    public static boolean createTables(Connection connection, String prefix, boolean isMySQL) throws SQLException {
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
    public static void executeUpdate(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    // Generic query (SELECT)
    public static ResultSet executeQuery(Connection connection, String sql, Object... params) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
        return ps.executeQuery(); // caller must close ResultSet & PreparedStatement
    }

    // Execute insert and return generated ID
    public static long executeInsert(Connection connection, String sql, Object... params) throws SQLException {
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


    /**
     * Checks if a table exists in the current database.
     *
     * @param connection the database connection
     * @param tableName the table name to check
     * @return true if the table exists, false otherwise
     * @throws SQLException if a database error occurs
     */
    public static boolean tableExists(Connection connection, String tableName) throws SQLException {
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
    public static boolean tablesExist(Connection connection, String... tables) throws SQLException {
        for (String table : tables) {
            if (!tableExists(connection, table)) return false;
        }
        return true;
    }

    public static List<PunishmentData> getPunishments(Connection connection, String prefix) throws SQLException {
        List<PunishmentData> punishments = new ArrayList<>();

        String sql = """
            SELECT
                id,
                uuid,
                punisher_uuid,
                type,
                reason,
                scope,
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

    public static long getLastProcessedUpdateId(Connection connection, String prefix) throws SQLException {
        String sql = "SELECT id FROM " + prefix + "punishment_updates ORDER BY id DESC LIMIT 1";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong("id") : 0;
        }
    }

    public static long getLastUpdateId(Connection connection, String prefix, long lastProcessedUpdateId) throws SQLException {
        String sql = "SELECT MAX(id) AS last_id FROM " + prefix + "punishment_updates WHERE id > ?";

        try (ResultSet rs = DatabaseUtils.executeQuery(connection, sql, lastProcessedUpdateId)) {
            if (rs.next()) {
                long lastId = rs.getLong("last_id");
                return rs.wasNull() ? 0 : lastId; // If no new updates, MAX returns null
            }
            return 0;
        }
    }

    public static void purgeOldUpdates(Connection connection, String prefix, long retentionMinutes) throws SQLException {
        String sql = "DELETE FROM " + prefix + "punishment_updates WHERE timestamp < ?";

        long cutoff = System.currentTimeMillis() - (retentionMinutes * 60 * 1000); // Convert minutes to ms

        executeUpdate(connection, sql, cutoff);
    }

    /**
     * Updates the server heartbeat in the database.
     * If the server already exists, updates last_seen.
     * Otherwise, inserts a new row.
     */
    public static void updateServerHeartbeat(Connection conn, String prefix, String serverName) throws SQLException {
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


    public static List<String> getServerHeartbeats(Connection conn, String prefix, long heartbeatTimeout) throws SQLException {
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

    public static void cleanupOldHeartbeats(Connection conn, String prefix, long retention) throws SQLException {
        String sql = "DELETE FROM " + prefix + "servers WHERE last_seen < ?";

        long cutoff = System.currentTimeMillis() - retention;

        executeUpdate(conn, sql, cutoff);
    }


}
