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
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.Map;

/** Shared command path for new punishments; database work never runs on the server thread. */
public class ModerationCommand implements CommandExecutor {
    private final ByteBans plugin;
    private final PunishmentsHandler handler;

    public ModerationCommand(ByteBans plugin) {
        this.plugin = plugin;
        this.handler = plugin.getPunishmentsHandler();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String action = command.getName().toLowerCase();
        if (!sender.hasPermission("bytebans." + action)) {
            reply(sender, "You do not have permission to use /" + action + ".");
            return true;
        }
        boolean ipTarget = action.startsWith("ip");
        boolean undo = action.startsWith("un") || action.startsWith("ipun") || action.equals("removenote");
        String type = switch (action) {
            case "ipban", "ipunban" -> "ipban";
            case "ipmute", "ipunmute" -> "ipmute";
            case "warn", "unwarn" -> "warn";
            case "note", "removenote" -> "note";
            case "freeze", "unfreeze" -> "freeze";
            default -> null;
        };
        if (type == null) return false;

        Map<String, String> fields = CommandUtils.parseArgs(args, "user", "ip", "reason", "scope", "time", "id");
        String user = fields.get("user");
        String ip = fields.get("ip");
        String subject = null;
        String playerUuid = null;
        Long id = null;
        try {
            if (fields.containsKey("id")) {
                id = Long.parseLong(fields.get("id"));
                if (id <= 0) throw new NumberFormatException();
            }
        } catch (NumberFormatException error) {
            reply(sender, "id: must be a positive punishment number.");
            return true;
        }
        if (ipTarget && ip != null && user != null) {
            reply(sender, "Choose either ip: or user:, not both.");
            return true;
        }
        if (!ipTarget && ip != null) {
            reply(sender, "This command expects user:, not ip:.");
            return true;
        }
        if (ipTarget && ip != null) {
            try {
                subject = IpAddresses.canonical(ip);
            } catch (IllegalArgumentException error) {
                reply(sender, error.getMessage());
                return true;
            }
        } else if (user != null && !user.isBlank()) {
            Result resolved = CommandUtils.getUuidFromUsername(user);
            if (!resolved.isSuccess()) {
                reply(sender, "Unknown player: " + user + ". This player must have joined before.");
                return true;
            }
            playerUuid = resolved.getMessage();
            if (!ipTarget) subject = playerUuid;
        }
        if (subject == null && playerUuid == null && !(undo && id != null)) {
            reply(sender, "Specify " + (ipTarget ? "ip:<address> or user:<name>" : "user:<name>")
                    + (undo ? " or id:<number>." : "."));
            return true;
        }

        String reason = fields.getOrDefault("reason", plugin.getConfig().getString(
                "punishments.default_reason", "No reason specified"));
        String scope = fields.getOrDefault("scope", plugin.getConfig().getString("punishments.default_scope", "*"));
        if (scope == null || scope.isBlank() || scope.length() > 64) {
            reply(sender, "scope: must be 1–64 characters.");
            return true;
        }
        long duration = 0;
        if (fields.containsKey("time")) {
            if (undo || action.equals("note")) {
                reply(sender, "time: is not used by this command.");
                return true;
            }
            try {
                duration = CommandUtils.parseToMillis(fields.get("time"));
                if (duration <= 0 || duration > 10L * 365 * 24 * 60 * 60 * 1000) {
                    throw new IllegalArgumentException("Duration out of range");
                }
            } catch (RuntimeException error) {
                reply(sender, "time: must be a positive duration such as 30m or 7d (maximum 10 years).");
                return true;
            }
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            reply(sender, "reason: must be 1–500 characters.");
            return true;
        }

        String finalSubject = subject;
        String finalUuid = playerUuid;
        Long finalId = id;
        long finalDuration = duration;
        String finalReason = reason;
        String finalScope = scope;
        String punisher = sender instanceof Player player ? player.getUniqueId().toString() : "CONSOLE";
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String target = finalSubject;
            if (ipTarget && target == null && finalUuid != null) {
                try {
                    target = handler.lastPlayerAddress(finalUuid);
                } catch (SQLException error) {
                    plugin.getLogger().warning("Could not resolve the last known player address: " + error.getMessage());
                    onMain(() -> reply(sender, "Could not read that player's last known address."));
                    return;
                }
                if (target == null) {
                    onMain(() -> reply(sender, "No last known address for that player. Use ip:<address> instead."));
                    return;
                }
            }
            if (undo) {
                Result result = handler.deactivateSubject(ipTarget ? "IP" : "PLAYER", target,
                        type, finalId, punisher, finalReason, action);
                onMain(() -> reply(sender, result.getMessage()));
                return;
            }
            if (!type.equals("warn") && !type.equals("note")
                    && handler.findActiveSubjectInScope(ipTarget ? "IP" : "PLAYER", target, type, finalScope) != null) {
                onMain(() -> reply(sender, "That " + type + " is already active for this scope."));
                return;
            }
            boolean saved = handler.punishSubject(ipTarget ? "IP" : target, ipTarget ? "IP" : "PLAYER",
                    target, punisher, type, finalReason, finalScope, finalDuration, true, false);
            if (!saved) {
                onMain(() -> reply(sender, "Could not save the " + type + ". Check the server log."));
                return;
            }
            PunishmentData newest = handler.history(ipTarget ? "IP" : "PLAYER", target).stream()
                    .filter(row -> row.getType().equals(type)).findFirst().orElse(null);
            onMain(() -> {
                reply(sender, "Saved " + type + (newest == null ? "." : " #" + newest.getId() + "."));
                if (!ipTarget && (type.equals("warn") || type.equals("freeze"))
                        && handler.matchesScope(finalScope, plugin.getServerName())) {
                    Player online = Bukkit.getPlayer(java.util.UUID.fromString(finalSubject));
                    if (online != null) {
                        online.sendMessage(Component.text(type.equals("warn")
                                ? "You have been warned: " + finalReason : "You have been frozen: " + finalReason));
                    }
                }
            });
        });
        return true;
    }

    private void onMain(Runnable action) {
        if (plugin.isShuttingDown()) return;
        Bukkit.getScheduler().runTask(plugin, action);
    }

    private void reply(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message));
    }
}
