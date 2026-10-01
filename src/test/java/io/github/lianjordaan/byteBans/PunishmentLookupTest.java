package io.github.lianjordaan.byteBans;

import io.github.lianjordaan.byteBans.database.Database;
import io.github.lianjordaan.byteBans.model.PunishmentData;
import io.github.lianjordaan.byteBans.punishments.PunishmentsHandler;
import io.github.lianjordaan.byteBans.util.BBLogger;
import io.github.lianjordaan.byteBans.util.DatabaseUtils;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PunishmentLookupTest {
    private static final String PLAYER = "1a567205-91c5-4ec1-a6e4-eb395393528f";

    @Test
    void scopedAndExpiredRowsCannotHideApplicablePlayerOrIpPunishments() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DatabaseUtils.migrate(connection, "bytebans_", false);
            long now = System.currentTimeMillis();
            insert(connection, PLAYER, "PLAYER", PLAYER, "ban", "survival", now, 0, true);
            insert(connection, PLAYER, "PLAYER", PLAYER, "ban", "hub", now - 10_000, 1_000, true);
            insert(connection, PLAYER, "PLAYER", PLAYER, "ban", "*", now, 0, true);
            insert(connection, "IP", "IP", "2001:db8:0:0:0:0:0:1", "ipmute", "hub", now, 0, true);
            insert(connection, PLAYER, "PLAYER", PLAYER, "freeze", "hub", now, 0, true);

            ByteBans plugin = mock(ByteBans.class);
            Database database = mock(Database.class);
            when(plugin.getDatabase()).thenReturn(database);
            when(database.getConnection()).thenReturn(connection);
            when(plugin.getDatabaseTablePrefix()).thenReturn("bytebans_");
            when(plugin.getBBLogger()).thenReturn(mock(BBLogger.class));
            YamlConfiguration config = new YamlConfiguration();
            config.set("server.name", "hub");
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getServerName()).thenReturn("hub");
            PunishmentsHandler handler = new PunishmentsHandler(plugin);
            handler.loadPunishments();

            assertEquals("*", handler.isPlayerBanned(PLAYER).getScope());
            assertNotNull(handler.isIpMuted("2001:db8:0:0:0:0:0:1"));
            assertNotNull(handler.isPlayerFrozen(PLAYER));
            assertNull(handler.isIpBanned("2001:db8:0:0:0:0:0:1"));
            assertTrue(handler.matchesScope("survival,hub", "hub"));
            assertFalse(handler.matchesScope("survival", "hub"));
            List<PunishmentData> history = handler.history("PLAYER", PLAYER);
            assertEquals(4, history.size());
            assertTrue(history.getFirst().getId() > history.getLast().getId());
        }
    }

    @Test
    void ipBanUndoKeepsAnAuditableHistoryAcrossCacheReloads() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DatabaseUtils.migrate(connection, "bytebans_", false);
            ByteBans plugin = mock(ByteBans.class);
            Database database = mock(Database.class);
            when(plugin.getDatabase()).thenReturn(database);
            when(database.getConnection()).thenReturn(connection);
            when(plugin.getDatabaseTablePrefix()).thenReturn("bytebans_");
            when(plugin.getBBLogger()).thenReturn(mock(BBLogger.class));
            when(plugin.getServerName()).thenReturn("hub");
            when(plugin.isShuttingDown()).thenReturn(true); // no Bukkit server in this database test
            PunishmentsHandler handler = new PunishmentsHandler(plugin);
            handler.loadPunishments();

            assertTrue(handler.punishSubject("IP", "IP", "203.0.113.7", "CONSOLE",
                    "ipban", "abuse", "*", 0, true, false));
            assertNotNull(handler.isIpBanned("203.0.113.7"));
            assertTrue(handler.deactivateSubject("IP", "203.0.113.7", "ipban", null,
                    "CONSOLE", "appeal accepted", "ipunban").isSuccess());
            assertNull(handler.isIpBanned("203.0.113.7"));
            assertEquals(2, handler.history("IP", "203.0.113.7").size());

            PunishmentsHandler reloaded = new PunishmentsHandler(plugin);
            reloaded.loadPunishments();
            assertNull(reloaded.isIpBanned("203.0.113.7"));
            assertEquals("ipunban", reloaded.history("IP", "203.0.113.7").getFirst().getType());
        }
    }

    @Test
    void warningsNotesAndFreezesKeepHistoryAndRequireIdsWhenAmbiguous() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DatabaseUtils.migrate(connection, "bytebans_", false);
            ByteBans plugin = mock(ByteBans.class);
            Database database = mock(Database.class);
            when(plugin.getDatabase()).thenReturn(database);
            when(database.getConnection()).thenReturn(connection);
            when(plugin.getDatabaseTablePrefix()).thenReturn("bytebans_");
            when(plugin.getBBLogger()).thenReturn(mock(BBLogger.class));
            when(plugin.getServerName()).thenReturn("hub");
            when(plugin.isShuttingDown()).thenReturn(true);
            PunishmentsHandler handler = new PunishmentsHandler(plugin);
            handler.loadPunishments();

            assertTrue(handler.punishSubject(PLAYER, "PLAYER", PLAYER, "CONSOLE",
                    "warn", "first", "*", 0, true, true));
            assertTrue(handler.punishSubject(PLAYER, "PLAYER", PLAYER, "CONSOLE",
                    "warn", "second", "*", 0, true, true));
            assertFalse(handler.deactivateSubject("PLAYER", PLAYER, "warn", null,
                    "CONSOLE", "correction", "unwarn").isSuccess());
            long firstWarning = handler.history("PLAYER", PLAYER).getLast().getId();
            assertTrue(handler.deactivateSubject("PLAYER", PLAYER, "warn", firstWarning,
                    "CONSOLE", "correction", "unwarn").isSuccess());
            assertTrue(handler.history("PLAYER", PLAYER).stream()
                    .anyMatch(row -> row.getType().equals("unwarn") && !row.isActive()));

            assertTrue(handler.punishSubject(PLAYER, "PLAYER", PLAYER, "CONSOLE",
                    "note", "staff context", "*", 0, true, true));
            assertTrue(handler.deactivateSubject("PLAYER", PLAYER, "note", null,
                    "CONSOLE", "resolved", "removenote").isSuccess());
            assertTrue(handler.punishSubject(PLAYER, "PLAYER", PLAYER, "CONSOLE",
                    "freeze", "investigation", "hub", 0, true, true));
            assertNotNull(handler.isPlayerFrozen(PLAYER));
            assertTrue(handler.deactivateSubject("PLAYER", PLAYER, "freeze", null,
                    "CONSOLE", "resolved", "unfreeze").isSuccess());
            assertNull(handler.isPlayerFrozen(PLAYER));

            PunishmentData exposed = handler.history("PLAYER", PLAYER).getFirst();
            exposed.setReason("tampered");
            assertNotEquals("tampered", handler.history("PLAYER", PLAYER).getFirst().getReason());
        }
    }

    private static void insert(Connection connection, String uuid, String subjectType, String subject,
                               String type, String scope, long start, long duration, boolean active) throws Exception {
        DatabaseUtils.executeUpdate(connection,
                "INSERT INTO bytebans_punishments (uuid, subject_type, subject, punisher_uuid, type, reason, "
                        + "scope, start_time, duration, active, created_at, updated_at, silent) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                uuid, subjectType, subject, "CONSOLE", type, "test", scope,
                start, duration, active, start, start, false);
    }
}
