package io.github.lianjordaan.byteBans.commands;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.model.Result;
import io.github.lianjordaan.byteBans.punishments.PunishmentsHandler;
import io.github.lianjordaan.byteBans.util.BBLogger;
import io.github.lianjordaan.byteBans.util.CommandUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class UnbanCommand implements CommandExecutor {
    private ByteBans plugin;
    private BBLogger logger;
    private MiniMessage miniMessage = MiniMessage.miniMessage();

    public UnbanCommand(ByteBans plugin) {
        this.plugin = plugin;
        this.logger = plugin.getBBLogger();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        boolean silent = CommandUtils.hasFlag("-s", args);

        String[] filteredArgs = Arrays.stream(args).filter(arg -> !arg.equals("-s")).toArray(String[]::new);

        Map<String, String> parsed = CommandUtils.parseArgs(filteredArgs, "user", "reason");

        String username = parsed.get("user");
        String reason = parsed.get("reason");

        logger.verbose("Unban command executed.");
        logger.verbose("User: " + username);
        logger.verbose("Reason: " + reason);
        logger.verbose("Silent: " + silent);

        if (reason == null) {
            reason = plugin.getConfig().getString("punishments.default_reason", "No reason specified");
        }

        String punisherUuid;
        String punisherName = "CONSOLE";
        if (sender instanceof Player) {
            punisherUuid = ((Player) sender).getUniqueId().toString();
            punisherName = sender.getName();
        } else {
            punisherUuid = "CONSOLE";
        }

        String alreadyUnbannedMessage = plugin.getConfig().getString("messages.commands.unban.already_unbanned");
        String unbanErrorMessage = plugin.getConfig().getString("messages.commands.unban.command_error");
        String invalidUsernameMessage = plugin.getConfig().getString("messages.commands.unban.player_not_found");

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("user", username);
        placeholders.put("executor", punisherName);
        placeholders.put("reason", reason);

        Result result = CommandUtils.getUuidFromUsername(username);
        if (!result.isSuccess()) {
            sender.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(invalidUsernameMessage, placeholders)));
            return true;
        }
        String usernameUuid = result.getMessage();

        PunishmentsHandler handler = plugin.getPunishmentsHandler();

        boolean isBanned = handler.isPlayerBanned(usernameUuid) != null;
        if (!isBanned) {
            sender.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(alreadyUnbannedMessage, placeholders)));
            return true;
        }

        String finalReason = reason;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Result unbanResult = handler.unBanPlayer(usernameUuid, punisherUuid, finalReason, null, silent);
            if (unbanResult.isSuccess()) {
//            sender.sendMessage(miniMessage.deserialize("<green>Successfully unbanned player."));
            } else {
                placeholders.put("error", unbanResult.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(unbanErrorMessage, placeholders)));
                });
            }
        });
        return true;
    }
}
