package io.github.lianjordaan.byteBans;

import io.github.lianjordaan.byteBans.model.PunishmentData;
import io.github.lianjordaan.byteBans.util.DatabaseUtils;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseMigrationTest {
    @Test
    void upgradesExistingPunishmentsWithoutChangingHistoryOrIds() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DatabaseUtils.createTables(connection, "bytebans_", false);
            DatabaseUtils.executeUpdate(connection,
                    "INSERT INTO bytebans_punishments "
                            + "(uuid, punisher_uuid, type, reason, scope, start_time, duration, active, created_at, updated_at, silent) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "1a567205-91c5-4ec1-a6e4-eb395393528f", "CONSOLE", "ban", "legacy reason", "hub",
                    1234L, 3600000L, true, 1234L, 1234L, false);
            DatabaseUtils.migrate(connection, "bytebans_", false);

            List<PunishmentData> rows = DatabaseUtils.getPunishments(connection, "bytebans_");
            assertEquals(1, rows.size());
            PunishmentData legacy = rows.getFirst();
            assertEquals(1, legacy.getId());
            assertEquals("PLAYER", legacy.getSubjectType());
            assertEquals(legacy.getUuid(), legacy.getSubject());
            assertEquals("legacy reason", legacy.getReason());
            assertEquals("hub", legacy.getScope());
            assertEquals(3600000L, legacy.getDuration());
            assertTrue(legacy.isActive());

            DatabaseUtils.migrate(connection, "bytebans_", false);
            assertEquals(1, DatabaseUtils.getPunishments(connection, "bytebans_").size());
            try (Statement statement = connection.createStatement();
                 ResultSet version = statement.executeQuery("SELECT version FROM bytebans_schema_version")) {
                assertTrue(version.next());
                assertEquals(DatabaseUtils.SCHEMA_VERSION, version.getInt(1));
                assertFalse(version.next());
            }
        }
    }

    @Test
    void freshSqliteSchemaSupportsIpSubjectsAndPlayerAddresses() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DatabaseUtils.migrate(connection, "bytebans_", false);
            DatabaseUtils.executeUpdate(connection,
                    "INSERT INTO bytebans_punishments "
                            + "(uuid, subject_type, subject, punisher_uuid, type, reason, scope, start_time, "
                            + "duration, active, created_at, updated_at, silent) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "IP", "IP", "2001:db8::1", "CONSOLE", "ipban", "test", "*",
                    2345L, 0L, true, 2345L, 2345L, false);
            DatabaseUtils.executeUpdate(connection,
                    "INSERT INTO bytebans_player_addresses (uuid, address, updated_at) VALUES (?, ?, ?)",
                    "1a567205-91c5-4ec1-a6e4-eb395393528f", "2001:db8::1", 2345L);
            PunishmentData ip = DatabaseUtils.getPunishments(connection, "bytebans_").getFirst();
            assertEquals("IP", ip.getSubjectType());
            assertEquals("2001:db8::1", ip.getSubject());
            DatabaseUtils.migrate(connection, "bytebans_", false);
            assertEquals("2001:db8::1", DatabaseUtils.getPunishments(connection, "bytebans_").getFirst().getSubject());
        }
    }

    @Test
    void rejectsUnsafeTablePrefixesBeforeExecutingSql() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            assertThrows(IllegalArgumentException.class,
                    () -> DatabaseUtils.migrate(connection, "bad;DROP TABLE users", false));
        }
    }
}
