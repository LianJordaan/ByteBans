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

public class TempBanCommand implements CommandExecutor {
    private ByteBans plugin;
    private BBLogger logger;
    private MiniMessage miniMessage = MiniMessage.miniMessage();

    public TempBanCommand(ByteBans plugin) {
        this.plugin = plugin;
        this.logger = plugin.getBBLogger();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        boolean silent = CommandUtils.hasFlag("-s", args);

        String[] filteredArgs = Arrays.stream(args).filter(arg -> !arg.equals("-s")).toArray(String[]::new);

        Map<String, String> parsed = CommandUtils.parseArgs(filteredArgs, "user", "time", "reason", "scope");

        String username = parsed.get("user");
        String time = parsed.get("time");
        String reason = parsed.get("reason");
        String scope = parsed.get("scope");

        logger.verbose("Tempmute command executed.");
        logger.verbose("User: " + username);
        logger.verbose("Time: " + time);
        logger.verbose("Reason: " + reason);
        logger.verbose("Scope: " + scope);
        logger.verbose("Silent: " + silent);

        if (scope == null) {
            scope = plugin.getConfig().getString("punishments.default_scope", "*");
        }
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

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("user", username);
        placeholders.put("time", time);
        placeholders.put("executor", punisherName);
        placeholders.put("reason", reason);
        placeholders.put("scope", scope);

        String alreadyBannedMessage = plugin.getConfig().getString("messages.commands.tempban.already_banned");
        String tempBanErrorMessage = plugin.getConfig().getString("messages.commands.tempban.command_error");
        String invalidUsernameMessage = plugin.getConfig().getString("messages.commands.tempban.player_not_found");
        String invalidTimeMessage = plugin.getConfig().getString("messages.commands.tempban.time_invalid");

        long parsedTime;
        try {
            parsedTime = CommandUtils.parseToMillis(time);
        } catch (Exception e) {
            sender.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(invalidTimeMessage, placeholders)));
            return true;
        }
        String formattedTime = CommandUtils.formatDurationNatural(parsedTime);
        placeholders.put("duration_left", formattedTime);

        Result result = CommandUtils.getUuidFromUsername(username);
        if (!result.isSuccess()) {
            sender.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(invalidUsernameMessage, placeholders)));
            return true;
        }
        String usernameUuid = result.getMessage();

        PunishmentsHandler handler = plugin.getPunishmentsHandler();

        boolean isAlreadyBanned = handler.isPlayerBanned(usernameUuid) != null;
        if (isAlreadyBanned) {
            sender.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(alreadyBannedMessage, placeholders)));
            return true;
        }

        String finalReason = reason;
        String finalScope = scope;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Result banResult = handler.tempBanPlayer(usernameUuid, punisherUuid, parsedTime, finalReason, finalScope, silent);
            if (banResult.isSuccess()) {
//            sender.sendMessage(miniMessage.deserialize("<green>Successfully tempbanned player."));
            } else {
                placeholders.put("error", banResult.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(tempBanErrorMessage, placeholders)));
                });
            }
        });
        return true;
    }
}
