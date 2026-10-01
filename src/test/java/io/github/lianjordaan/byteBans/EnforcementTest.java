package io.github.lianjordaan.byteBans;

import io.github.lianjordaan.byteBans.commands.HistoryCommand;
import io.github.lianjordaan.byteBans.commands.ModerationCommand;
import io.github.lianjordaan.byteBans.database.Database;
import io.github.lianjordaan.byteBans.listeners.ChatListener;
import io.github.lianjordaan.byteBans.listeners.FreezeListener;
import io.github.lianjordaan.byteBans.listeners.LoginListener;
import io.github.lianjordaan.byteBans.punishments.PunishmentsHandler;
import io.github.lianjordaan.byteBans.util.BBLogger;
import io.github.lianjordaan.byteBans.util.DatabaseUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EnforcementTest {
    private static final UUID PLAYER_ID = UUID.fromString("1a567205-91c5-4ec1-a6e4-eb395393528f");

    @Test
    void newCommandsRejectSendersWithoutTheirExplicitPermissions() {
        ByteBans plugin = mock(ByteBans.class);
        when(plugin.getPunishmentsHandler()).thenReturn(mock(PunishmentsHandler.class));
        CommandSender sender = mock(CommandSender.class);
        Command freeze = mock(Command.class);
        when(freeze.getName()).thenReturn("freeze");
        assertTrue(new ModerationCommand(plugin).onCommand(sender, freeze, "freeze",
                new String[]{"user:someone"}));
        verify(sender).hasPermission("bytebans.freeze");
        verify(sender).sendMessage(any(Component.class));
        verifyNoInteractions(plugin.getPunishmentsHandler());

        Command history = mock(Command.class);
        when(history.getName()).thenReturn("history");
        assertTrue(new HistoryCommand(plugin).onCommand(sender, history, "history",
                new String[]{"user:someone"}));
        verify(sender).hasPermission("bytebans.history");
        verify(sender, times(2)).sendMessage(any(Component.class));
    }

    @Test
    void ipBanDisallowsLoginOnlyWhereItsScopeApplies() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DatabaseUtils.migrate(connection, "bytebans_", false);
            insert(connection, "IP", "IP", "203.0.113.7", "ipban", "hub");
            Fixture fixture = fixture(connection);
            LoginListener listener = new LoginListener(fixture.plugin());
            Player player = player();
            PlayerLoginEvent event = mock(PlayerLoginEvent.class);
            when(event.getPlayer()).thenReturn(player);
            when(event.getAddress()).thenReturn(InetAddress.getByName("203.0.113.7"));
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getOfflinePlayers).thenReturn(new OfflinePlayer[0]);
                bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
                listener.onChat(event);
            }
            verify(event).disallow(eq(PlayerLoginEvent.Result.KICK_OTHER), any(Component.class));

            when(fixture.plugin().getServerName()).thenReturn("survival");
            reset(event);
            when(event.getPlayer()).thenReturn(player);
            when(event.getAddress()).thenReturn(InetAddress.getByName("203.0.113.7"));
            listener.onChat(event);
            verify(event, never()).disallow(any(), any(Component.class));
        }
    }

    @Test
    void ipMuteCancelsChatAndFreezePreservesLookDirection() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DatabaseUtils.migrate(connection, "bytebans_", false);
            insert(connection, "IP", "IP", "203.0.113.7", "ipmute", "hub");
            insert(connection, PLAYER_ID.toString(), "PLAYER", PLAYER_ID.toString(), "freeze", "hub");
            Fixture fixture = fixture(connection);
            when(fixture.plugin().getOnlineAddress(PLAYER_ID.toString())).thenReturn("203.0.113.7");
            Player player = player();
            AsyncPlayerChatEvent chat = mock(AsyncPlayerChatEvent.class);
            when(chat.getPlayer()).thenReturn(player);
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
                bukkit.when(Bukkit::getOfflinePlayers).thenReturn(new OfflinePlayer[0]);
                bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
                new ChatListener(fixture.plugin()).onChat(chat);
            }
            verify(chat).setCancelled(true);
            verify(player).sendMessage(any(Component.class));

            PlayerMoveEvent move = mock(PlayerMoveEvent.class);
            Location from = new Location(null, 1, 65, 1, 0, 0);
            Location to = new Location(null, 2, 65, 1, 90, 20);
            when(move.getPlayer()).thenReturn(player);
            when(move.getFrom()).thenReturn(from);
            when(move.getTo()).thenReturn(to);
            new FreezeListener(fixture.plugin()).onMove(move);
            ArgumentCaptor<Location> captured = ArgumentCaptor.forClass(Location.class);
            verify(move).setTo(captured.capture());
            assertEquals(1, captured.getValue().getX());
            assertEquals(65, captured.getValue().getY());
            assertEquals(90, captured.getValue().getYaw());
            assertEquals(20, captured.getValue().getPitch());
        }
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("TestPlayer");
        return player;
    }

    private static Fixture fixture(Connection connection) throws Exception {
        ByteBans plugin = mock(ByteBans.class);
        Database database = mock(Database.class);
        when(plugin.getDatabase()).thenReturn(database);
        when(database.getConnection()).thenReturn(connection);
        when(plugin.getDatabaseTablePrefix()).thenReturn("bytebans_");
        when(plugin.getBBLogger()).thenReturn(mock(BBLogger.class));
        when(plugin.getServerName()).thenReturn("hub");
        YamlConfiguration config = new YamlConfiguration();
        config.set("server.name", "hub");
        config.set("punishments.date_format", "yyyy-MM-dd");
        config.set("punishments.date_timezone", "UTC");
        config.set("messages.general.banned.ban_screen.permanent", "<red>Access denied");
        config.set("messages.general.banned.notification", "<red>Login denied");
        config.set("messages.general.banned.bypass", "<yellow>Bypassed");
        config.set("messages.general.muted.permanent", "<red>Muted");
        config.set("messages.general.muted.notification", "");
        when(plugin.getConfig()).thenReturn(config);
        PunishmentsHandler handler = new PunishmentsHandler(plugin);
        handler.loadPunishments();
        when(plugin.getPunishmentsHandler()).thenReturn(handler);
        return new Fixture(plugin, handler);
    }

    private static void insert(Connection connection, String uuid, String subjectType, String subject,
                               String type, String scope) throws Exception {
        long now = System.currentTimeMillis();
        DatabaseUtils.executeUpdate(connection,
                "INSERT INTO bytebans_punishments (uuid, subject_type, subject, punisher_uuid, type, reason, "
                        + "scope, start_time, duration, active, created_at, updated_at, silent) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                uuid, subjectType, subject, "CONSOLE", type, "test", scope,
                now, 0, true, now, now, false);
    }

    private record Fixture(ByteBans plugin, PunishmentsHandler handler) {}
}
