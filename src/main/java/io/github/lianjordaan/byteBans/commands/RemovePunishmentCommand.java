package io.github.lianjordaan.byteBans.commands;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.model.Result;
import io.github.lianjordaan.byteBans.util.BBLogger;
import io.github.lianjordaan.byteBans.util.CommandUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public class RemovePunishmentCommand implements CommandExecutor {
    private ByteBans plugin;
    private BBLogger logger;
    private MiniMessage miniMessage = MiniMessage.miniMessage();

    public RemovePunishmentCommand(ByteBans plugin) {
        this.plugin = plugin;
        this.logger = plugin.getBBLogger();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {

        Map<String, String> parsed = CommandUtils.parseArgs(args, "id");

        String id = parsed.get("id");

        logger.verbose("RemovePunishment command executed.");
        logger.verbose("ID: " + id);
        long idNum = 0;
        try {
            idNum = Long.parseLong(id);
        } catch (NumberFormatException e) {
            logger.verbose("Invalid ID: " + id);
            sender.sendMessage(miniMessage.deserialize("<red>Invalid ID: " + id));
            return true;
        }
        String uuid = "CONSOLE";
        if (sender instanceof Player) {
            uuid = ((Player) sender).getUniqueId().toString();
        }

        Result result = plugin.getPunishmentsHandler().removePunishment(uuid, idNum);
        if (result.isSuccess()) {
            sender.sendMessage(miniMessage.deserialize("<green>Successfully removed punishment."));
        } else {
            sender.sendMessage(miniMessage.deserialize("<red>Failed to remove punishment. Error: <u>" + result.getMessage() + "</u>"));
        }
        return true;
    }
}
