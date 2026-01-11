package io.github.lianjordaan.byteBans.listeners;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.model.PunishmentData;
import io.github.lianjordaan.byteBans.model.Result;
import io.github.lianjordaan.byteBans.punishments.PunishmentsHandler;
import io.github.lianjordaan.byteBans.util.BBLogger;
import io.github.lianjordaan.byteBans.util.CommandUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;

import java.util.HashMap;
import java.util.Map;

public class LoginListener implements Listener {
    private ByteBans plugin;
    private BBLogger logger;
    private PunishmentsHandler handler;
    private MiniMessage miniMessage = MiniMessage.miniMessage();

    public LoginListener(ByteBans plugin) {
        this.plugin = plugin;
        this.logger = plugin.getBBLogger();
        this.handler = plugin.getPunishmentsHandler();
    }

    // Listen for the AsyncChatEvent
    @EventHandler
    public void onChat(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString();
        PunishmentData bannedPunishment = handler.isPlayerBanned(uuid);

        String serverName = plugin.getConfig().getString("server.name", "*");

        if (bannedPunishment != null) {
            String scope = bannedPunishment.getScope();

            if (!handler.matchesScope(scope, serverName)) {
                return;
            }

            boolean permanent = bannedPunishment.getDuration() == 0;
            String banMessage;
            String staffMessage;
            String bypassChatMessage;


            Map<String, String> placeholders = new HashMap<>();

            String dateFormat = plugin.getConfig().getString("punishments.date_format");
            String dateTimezone = plugin.getConfig().getString("punishments.date_timezone");

            placeholders.put("start_time_formatted", handler.formatMillis(bannedPunishment.getStartTime(), dateFormat, dateTimezone));
            if (permanent) {
                banMessage  = plugin.getConfig().getString("messages.general.banned.ban_screen.permanent");
            } else {
                banMessage = plugin.getConfig().getString("messages.general.banned.ban_screen.temporary");
                long endTime = bannedPunishment.getStartTime() + bannedPunishment.getDuration();
                String expiresIn = CommandUtils.formatDurationNatural(endTime - System.currentTimeMillis());
                placeholders.put("duration_left", expiresIn);
            }
            staffMessage = plugin.getConfig().getString("messages.general.banned.notification");
            bypassChatMessage = plugin.getConfig().getString("messages.general.banned.bypass");
            placeholders.put("user", player.getName());
            Result punisherResult = CommandUtils.getUsernameFromUuid(bannedPunishment.getPunisherUuid());
            if (punisherResult.isSuccess()) {
                placeholders.put("executor", punisherResult.getMessage());
            } else if (!bannedPunishment.getPunisherUuid().equalsIgnoreCase("CONSOLE")) {
                placeholders.put("executor", "UNKNOWN");
            } else {
                placeholders.put("executor", "CONSOLE");
            }
            placeholders.put("reason", bannedPunishment.getReason());
            placeholders.put("scope", bannedPunishment.getScope());
            placeholders.put("punishment_id", String.valueOf(bannedPunishment.getId()));
            logger.verbose("<gold>Banned punishment: " + bannedPunishment.toString());

            boolean adminBypass = PunishmentsHandler.hasPunishmentBypass(player);
            boolean notifyAdminBypass = plugin.getConfig().getBoolean("punishments.staff_bypass.notify");
            if (adminBypass) {
                logger.verbose("<red>" + player.getName() + " is banned but has permission to bypass this punishment.</red>");
                if (notifyAdminBypass) {
                    player.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(bypassChatMessage, placeholders)));
                }

                logger.verbose(CommandUtils.parseMessageWithPlaceholders(banMessage, placeholders));
                return;
            } else {

                logger.verbose("<red>Player " + player.getName() + " tried to join but is banned.</red>");
                // loop all online staff and notify them
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission("bytebans.notify.join_banned")) {
                        staff.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(staffMessage, placeholders)));
                    }
                }

                logger.verbose(CommandUtils.parseMessageWithPlaceholders(banMessage, placeholders));
                event.disallow(PlayerLoginEvent.Result.KICK_OTHER, miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(banMessage, placeholders)));
                return;
            }
        }
    }
}
