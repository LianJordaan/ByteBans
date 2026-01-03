package io.github.lianjordaan.byteBans.commands;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.model.Result;
import io.github.lianjordaan.byteBans.punishments.PunishmentsHandler;
import io.github.lianjordaan.byteBans.util.BBLogger;
import io.github.lianjordaan.byteBans.util.CommandUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class KickCommand implements CommandExecutor {
    private ByteBans plugin;
    private BBLogger logger;
    private MiniMessage miniMessage = MiniMessage.miniMessage();

    public KickCommand(ByteBans plugin) {
        this.plugin = plugin;
        this.logger = plugin.getBBLogger();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        boolean silent = CommandUtils.hasFlag("-s", args);

        String[] filteredArgs = Arrays.stream(args).filter(arg -> !arg.equals("-s")).toArray(String[]::new);

        Map<String, String> parsed = CommandUtils.parseArgs(filteredArgs, "user", "reason", "scope");

        String username = parsed.get("user");
        String reason = parsed.get("reason");
        String scope = parsed.get("scope");

        logger.verbose("Kick command executed.");
        logger.verbose("User: " + username);
        logger.verbose("Reason: " + reason);
        logger.verbose("Scope: " + scope);
        logger.verbose("Silent: " + silent);

        if (scope == null) {
            scope = plugin.getConfig().getString("punishments.default_scope", "*");
        }
        if (reason == null) {
            reason = plugin.getConfig().getString("punishments.default_reason", "No reason specified");
        }


        String kickErrorMessage = plugin.getConfig().getString("messages.commands.kick.command_error");
        String invalidUsernameMessage = plugin.getConfig().getString("messages.commands.kick.player_not_found");

        String punisherUuid = "CONSOLE";
        String punisherName = "CONSOLE";
        if (sender instanceof Player) {
            punisherUuid = ((Player) sender).getUniqueId().toString();
            punisherName = sender.getName();
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("user", username);
        placeholders.put("executor", punisherName);
        placeholders.put("reason", reason);
        placeholders.put("scope", scope);

        Result result = CommandUtils.getUuidFromUsername(username);
        if (!result.isSuccess()) {
            sender.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(invalidUsernameMessage, placeholders)));
            return true;
        }
        String usernameUuid = result.getMessage();

        PunishmentsHandler handler = plugin.getPunishmentsHandler();

        boolean kickResult = handler.kickPlayer(usernameUuid, punisherUuid, reason, scope, silent);
        if (kickResult) {
//            sender.sendMessage(miniMessage.deserialize("<green>Successfully kickned player."));
        } else {
            placeholders.put("error", "Unknown error");
            sender.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(kickErrorMessage, placeholders)));
        }
        return true;
    }
}
