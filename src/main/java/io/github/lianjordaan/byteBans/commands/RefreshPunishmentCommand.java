package io.github.lianjordaan.byteBans.commands;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.model.Result;
import io.github.lianjordaan.byteBans.util.BBLogger;
import io.github.lianjordaan.byteBans.util.CommandUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.Map;

public class RefreshPunishmentCommand implements CommandExecutor {
    private ByteBans plugin;
    private BBLogger logger;
    private MiniMessage miniMessage = MiniMessage.miniMessage();

    public RefreshPunishmentCommand(ByteBans plugin) {
        this.plugin = plugin;
        this.logger = plugin.getBBLogger();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {


        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getPunishmentsHandler().reloadPunishments();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(miniMessage.deserialize("<green>Successfully reloaded punishments."));
                });
            } catch (SQLException e) {
                e.printStackTrace();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(miniMessage.deserialize("<red>Failed to refresh punishment. Please check console for more details."));
                });
            }
        });
        return true;
    }
}
