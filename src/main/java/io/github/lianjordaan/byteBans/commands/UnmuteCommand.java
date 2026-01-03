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

public class UnmuteCommand implements CommandExecutor {
    private ByteBans plugin;
    private BBLogger logger;
    private MiniMessage miniMessage = MiniMessage.miniMessage();

    public UnmuteCommand(ByteBans plugin) {
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

        logger.verbose("Unmute command executed.");
        logger.verbose("User: " + username);
        logger.verbose("Reason: " + reason);
        logger.verbose("Silent: " + silent);

        if (reason == null) {
            reason = plugin.getConfig().getString("punishments.default_reason", "No reason specified");
        }

        String punisherUuid = "CONSOLE";
        String punisherName = "CONSOLE";
        if (sender instanceof Player) {
            punisherUuid = ((Player) sender).getUniqueId().toString();
            punisherName = sender.getName();
        }

        String alreadyUnmutedMessage = plugin.getConfig().getString("messages.commands.unmute.already_unmuted");
        String unmuteErrorMessage = plugin.getConfig().getString("messages.commands.unmute.command_error");
        String invalidUsernameMessage = plugin.getConfig().getString("messages.commands.unmute.player_not_found");

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

        boolean isMuted = handler.isPlayerMuted(usernameUuid) != null;
        if (!isMuted) {
            sender.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(alreadyUnmutedMessage, placeholders)));
            return true;
        }

        Result unmuteResult = handler.unmutePlayer(usernameUuid, punisherUuid, reason, null, silent);
        if (unmuteResult.isSuccess()) {
//            sender.sendMessage(miniMessage.deserialize("<green>Successfully unmuted player."));
        } else {
            placeholders.put("error", unmuteResult.getMessage());
            sender.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(unmuteErrorMessage, placeholders)));
        }
        return true;
    }
}
