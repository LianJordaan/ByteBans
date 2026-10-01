package io.github.lianjordaan.byteBans.commands;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.util.BBLogger;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RemovePunishmentCommandTest {
    @Test
    void permanentHistoryDeletionIsConsoleOnly() {
        assertTrue(RemovePunishmentCommand.consoleOnly(mock(ConsoleCommandSender.class)));
        assertTrue(RemovePunishmentCommand.consoleOnly(mock(RemoteConsoleCommandSender.class)));
        assertFalse(RemovePunishmentCommand.consoleOnly(mock(Player.class)));

        ByteBans plugin = mock(ByteBans.class);
        when(plugin.getBBLogger()).thenReturn(mock(BBLogger.class));
        Player player = mock(Player.class);
        RemovePunishmentCommand command = new RemovePunishmentCommand(plugin);
        assertTrue(command.onCommand(player, mock(Command.class), "removepunishment", new String[]{"id:42"}));
        verify(player).sendMessage(any(Component.class));
        verify(plugin, never()).getPunishmentsHandler();
    }
}
