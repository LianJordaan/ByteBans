package io.github.lianjordaan.byteBans.commands;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.util.BBLogger;
import io.github.lianjordaan.byteBans.util.CommandUtils;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public class TempMuteCommand implements CommandExecutor {
    private ByteBans plugin;
    private BBLogger logger;

    public TempMuteCommand(ByteBans plugin) {
        this.plugin = plugin;
        this.logger = plugin.getBBLogger();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        Map<String, String> parsed = CommandUtils.parseArgs(args, "user", "time", "reason", "scope");

        String username = parsed.get("user");
        String time = parsed.get("time");
        String reason = parsed.get("reason");
        String scope = parsed.get("scope");
        boolean silent = CommandUtils.hasFlag("-s", args);

        logger.verbose("Tempmute command executed.");
        logger.verbose("User: " + username);
        logger.verbose("Time: " + time);
        logger.verbose("Reason: " + reason);
        logger.verbose("Scope: " + scope);
        logger.verbose("Silent: " + silent);
        return false;
    }
}
