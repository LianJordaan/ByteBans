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

        Result result = CommandUtils.getUuidFromUsername(username);
        if (!result.isSuccess()) {
            sender.sendMessage(miniMessage.deserialize("<red>Invalid username: " + username));
            return true;
        }
        String usernameUuid = result.getMessage();

        PunishmentsHandler handler = plugin.getPunishmentsHandler();

        String uuid = "CONSOLE";
        if (sender instanceof Player) {
            uuid = ((Player) sender).getUniqueId().toString();
        }

        boolean isAlreadyMuted = handler.isPlayerMuted(usernameUuid) != null;
        if (!isAlreadyMuted) {
            sender.sendMessage(miniMessage.deserialize("<red>That player is not muted.</red>"));
            return true;
        }

        Result unmuteResult = handler.unmutePlayer(usernameUuid, uuid, reason, null, silent);
        if (unmuteResult.isSuccess()) {
            sender.sendMessage(miniMessage.deserialize("<green>Successfully unmuted player."));
        } else {
            sender.sendMessage(miniMessage.deserialize("<red>Failed to mute player. Error: <u>" + unmuteResult.getMessage() + "</u>"));
        }
        return true;
    }
}
