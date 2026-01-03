package io.github.lianjordaan.byteBans.listeners;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.model.PunishmentData;
import io.github.lianjordaan.byteBans.model.Result;
import io.github.lianjordaan.byteBans.punishments.PunishmentsHandler;
import io.github.lianjordaan.byteBans.util.BBLogger;
import io.github.lianjordaan.byteBans.util.CommandUtils;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatEvent;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class ChatListener implements Listener {
    private ByteBans plugin;
    private BBLogger logger;
    private PunishmentsHandler handler;
    private MiniMessage miniMessage = MiniMessage.miniMessage();

    public ChatListener(ByteBans plugin) {
        this.plugin = plugin;
        this.logger = plugin.getBBLogger();
        this.handler = plugin.getPunishmentsHandler();
    }

    // Listen for the AsyncChatEvent
    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString();
        PunishmentData mutedPunishment = handler.isPlayerMuted(uuid);

        String serverName = plugin.getConfig().getString("server.name", "*");

        if (mutedPunishment != null) {
            String scope = mutedPunishment.getScope();

            if (!handler.matchesScope(scope, serverName)) {
                return;
            }

            boolean permanent = mutedPunishment.getDuration() == 0;
            String chatMessage;
            String chatStaffMessage;
            String bypassChatMessage;


            Map<String, String> placeholders = new HashMap<>();

            if (permanent) {
                chatMessage  = plugin.getConfig().getString("messages.general.muted.permanent");
            } else {
                chatMessage = plugin.getConfig().getString("messages.general.muted.temporary");
                long endTime = mutedPunishment.getStartTime() + mutedPunishment.getDuration();
                String expiresIn = CommandUtils.formatDurationNatural(endTime - System.currentTimeMillis());
                placeholders.put("duration_left", expiresIn);
            }
            chatStaffMessage = plugin.getConfig().getString("messages.general.muted.notification");
            bypassChatMessage = plugin.getConfig().getString("messages.general.muted.bypass");
            placeholders.put("user", player.getName());
            Result punisherResult = CommandUtils.getUsernameFromUuid(mutedPunishment.getPunisherUuid());
            if (punisherResult.isSuccess()) {
                placeholders.put("executor", punisherResult.getMessage());
            } else if (!mutedPunishment.getPunisherUuid().equalsIgnoreCase("CONSOLE")) {
                placeholders.put("executor", "UNKNOWN");
            } else {
                placeholders.put("executor", "CONSOLE");
            }
            placeholders.put("reason", mutedPunishment.getReason());
            placeholders.put("scope", mutedPunishment.getScope());
            placeholders.put("punishment_id", String.valueOf(mutedPunishment.getId()));
            logger.verbose("<gold>Muted punishment: " + mutedPunishment.toString());

            boolean adminBypass = PunishmentsHandler.hasPunishmentBypass(player);
            boolean notifyAdminBypass = plugin.getConfig().getBoolean("punishments.staff_bypass.notify");
            if (adminBypass) {
                logger.verbose("<red>" + player.getName() + " is muted but has permission to bypass this punishment.</red>");
                if (notifyAdminBypass) {
                    player.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(bypassChatMessage, placeholders)));
                }

                logger.verbose(CommandUtils.parseMessageWithPlaceholders(chatMessage, placeholders));
                return;
            } else {

                logger.verbose("<red>Player " + player.getName() + " tried to chat but is muted.</red>");
                // loop all online staff and notify them
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission("bytebans.notify.speak_muted")) {
                        staff.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(chatStaffMessage, placeholders)));
                    }
                }

                player.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(chatMessage, placeholders)));
                event.setCancelled(true);
                return;
            }
        }
    }
}
