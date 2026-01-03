package io.github.lianjordaan.byteBans.listeners;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.model.PunishmentData;
import io.github.lianjordaan.byteBans.punishments.PunishmentsHandler;
import io.github.lianjordaan.byteBans.util.BBLogger;
import io.github.lianjordaan.byteBans.util.CommandUtils;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatEvent;
import org.jetbrains.annotations.NotNull;

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

            logger.verbose("<red>Player " + player.getName() + " tried to chat but is muted.</red>");
            logger.verbose("<gold>Muted punishment: " + mutedPunishment.toString());

            boolean adminBypass = PunishmentsHandler.hasPunishmentBypass(player);
            boolean notifyAdminBypass = plugin.getConfig().getBoolean("punishments.staff_bypass.notify");
            if (adminBypass) {
                logger.verbose("<red>" + player.getName() + " is muted but has permission to bypass this punishment.</red>");
                if (notifyAdminBypass) {
                    player.sendMessage(miniMessage.deserialize("<red>You are muted but have permission to bypass this punishment.</red>"));
                }
                return;
            } else {
                boolean permanent = mutedPunishment.getDuration() == 0;
                long endTime = mutedPunishment.getStartTime() + mutedPunishment.getDuration();
                String expiresIn = CommandUtils.formatDurationNatural(endTime - System.currentTimeMillis());

                String message = "<red>You are muted. <red>This punishment is permanent.";
                if (!permanent) {
                    message = "<red>You are muted. <newline><green>This punishment expires in " + expiresIn + ".";
                }

                player.sendMessage(miniMessage.deserialize(message));
                event.setCancelled(true);
                return;
            }
        }
    }
}
