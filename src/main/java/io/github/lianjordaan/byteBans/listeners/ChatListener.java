package io.github.lianjordaan.byteBans.listeners;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.model.PunishmentData;
import io.github.lianjordaan.byteBans.model.Result;
import io.github.lianjordaan.byteBans.punishments.PunishmentsHandler;
import io.github.lianjordaan.byteBans.util.CommandUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Cancels muted chat on its event thread; all player and Bukkit API work runs on the main thread. */
public class ChatListener implements Listener {
    private final ByteBans plugin;
    private final PunishmentsHandler handler;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public ChatListener(ByteBans plugin) {
        this.plugin = plugin;
        this.handler = plugin.getPunishmentsHandler();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString();
        PunishmentData punishment = handler.isPlayerMuted(uuid);
        if (punishment == null) {
            String address = plugin.getOnlineAddress(uuid);
            if (address != null) punishment = handler.isIpMuted(address);
        }
        if (punishment == null) return;

        boolean bypass;
        try {
            if (event.isAsynchronous()) {
                bypass = Bukkit.getScheduler().callSyncMethod(plugin,
                        () -> handler.hasPunishmentBypass(player)).get(2, TimeUnit.SECONDS);
            } else {
                bypass = handler.hasPunishmentBypass(player);
            }
        } catch (Exception error) {
            event.setCancelled(true);
            plugin.getLogger().warning("Muted chat was blocked because staff-bypass status could not be checked");
            return;
        }

        PunishmentData applied = punishment;
        if (bypass) {
            onMain(() -> {
                if (plugin.getConfig().getBoolean("punishments.staff_bypass.notify")) {
                    player.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(
                            plugin.getConfig().getString("messages.general.muted.bypass", "<yellow>Mute bypassed."),
                            placeholders(applied, player))));
                }
            });
            return;
        }

        event.setCancelled(true);
        onMain(() -> {
            Map<String, String> fields = placeholders(applied, player);
            boolean permanent = applied.getDuration() == 0;
            String message = plugin.getConfig().getString(permanent
                    ? "messages.general.muted.permanent" : "messages.general.muted.temporary", "<red>You are muted.");
            player.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(message, fields)));
            String staff = plugin.getConfig().getString("messages.general.muted.notification", "");
            if (!staff.isBlank()) {
                for (Player moderator : Bukkit.getOnlinePlayers()) {
                    if (moderator.hasPermission("bytebans.notify.speak_muted")) {
                        moderator.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(staff, fields)));
                    }
                }
            }
        });
    }

    private Map<String, String> placeholders(PunishmentData punishment, Player player) {
        Map<String, String> fields = new HashMap<>();
        fields.put("user", player.getName());
        Result punisher = CommandUtils.getUsernameFromUuid(punishment.getPunisherUuid());
        fields.put("executor", "CONSOLE".equalsIgnoreCase(punishment.getPunisherUuid())
                ? "CONSOLE" : punisher.isSuccess() ? punisher.getMessage() : "UNKNOWN");
        fields.put("reason", punishment.getReason());
        fields.put("scope", punishment.getScope());
        fields.put("punishment_id", String.valueOf(punishment.getId()));
        if (punishment.getDuration() > 0) {
            long remaining = punishment.getStartTime() + punishment.getDuration() - System.currentTimeMillis();
            fields.put("duration_left", CommandUtils.formatDurationNatural(Math.max(0, remaining)));
        }
        return fields;
    }

    private void onMain(Runnable action) {
        if (plugin.isShuttingDown()) return;
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }
}
