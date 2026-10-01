package io.github.lianjordaan.byteBans.commands;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.model.PunishmentData;
import io.github.lianjordaan.byteBans.model.Result;
import io.github.lianjordaan.byteBans.punishments.PunishmentsHandler;
import io.github.lianjordaan.byteBans.util.CommandUtils;
import io.github.lianjordaan.byteBans.util.IpAddresses;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Paged, read-only punishment history for a player or address. */
public class HistoryCommand implements CommandExecutor {
    private static final int PAGE_SIZE = 10;
    private final ByteBans plugin;
    private final PunishmentsHandler handler;

    public HistoryCommand(ByteBans plugin) {
        this.plugin = plugin;
        handler = plugin.getPunishmentsHandler();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("bytebans.history")) {
            reply(sender, "You do not have permission to view punishment history.");
            return true;
        }
        Map<String, String> fields = CommandUtils.parseArgs(args, "user", "ip", "page");
        if (fields.containsKey("user") == fields.containsKey("ip")) {
            reply(sender, "Use /history user:<name> [page:<number>] or /history ip:<address> [page:<number>].");
            return true;
        }
        int page;
        try {
            page = Integer.parseInt(fields.getOrDefault("page", "1"));
            if (page < 1) throw new NumberFormatException();
        } catch (NumberFormatException error) {
            reply(sender, "page: must be a positive number.");
            return true;
        }
        boolean ip = fields.containsKey("ip");
        String subject;
        if (ip) {
            try {
                subject = IpAddresses.canonical(fields.get("ip"));
            } catch (IllegalArgumentException error) {
                reply(sender, error.getMessage());
                return true;
            }
        } else {
            Result resolved = CommandUtils.getUuidFromUsername(fields.get("user"));
            if (!resolved.isSuccess()) {
                reply(sender, "Unknown player: " + fields.get("user") + ".");
                return true;
            }
            subject = resolved.getMessage();
        }
        List<PunishmentData> rows = handler.history(ip ? "IP" : "PLAYER", subject);
        int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > pages) {
            reply(sender, "There are only " + pages + " page(s) of history.");
            return true;
        }
        reply(sender, "History for " + (ip ? subject : fields.get("user")) + " — page " + page + "/" + pages);
        if (rows.isEmpty()) {
            reply(sender, "No punishments recorded.");
            return true;
        }
        int start = (page - 1) * PAGE_SIZE;
        for (PunishmentData row : rows.subList(start, Math.min(start + PAGE_SIZE, rows.size()))) {
            boolean expired = row.getDuration() > 0
                    && row.getStartTime() + row.getDuration() <= System.currentTimeMillis();
            String state = row.isActive() && !expired ? "active" : expired ? "expired" : "inactive";
            reply(sender, "#" + row.getId() + " " + row.getType() + " [" + state + "] "
                    + row.getReason() + " · " + row.getScope() + " · " + Instant.ofEpochMilli(row.getCreatedAt()));
        }
        return true;
    }

    private void reply(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message));
    }
}
