package io.github.lianjordaan.byteBans.listeners;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.model.PunishmentData;
import io.github.lianjordaan.byteBans.punishments.PunishmentsHandler;
import io.github.lianjordaan.byteBans.util.BBLogger;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatEvent;
import org.jetbrains.annotations.NotNull;

public class ChatListener implements Listener {
    private ByteBans plugin;
    private BBLogger logger;
    private PunishmentsHandler handler;

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
        if (mutedPunishment != null) {
            logger.verbose("<red>Player " + player.getName() + " tried to chat but is muted.</red>");
            logger.verbose("<gold>Muted punishment: " + mutedPunishment.toString());
            return;
        }
    }
}
