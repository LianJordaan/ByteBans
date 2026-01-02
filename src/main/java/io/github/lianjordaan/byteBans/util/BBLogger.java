package io.github.lianjordaan.byteBans.util;

import io.github.lianjordaan.byteBans.ByteBans;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public class BBLogger {
    private final FileConfiguration config;
    private final ByteBans plugin;
    private MiniMessage miniMessage = MiniMessage.miniMessage();

    public BBLogger(ByteBans plugin) {
        this.config = plugin.getConfig();
        this.plugin = plugin;
    }

    public void info(String message) {
        if (config.getBoolean("logging.console")) {
            Bukkit.getLogger().info("[ByteBans] " + message);
        }
        this.verbose(message, "INFO");
    }

    public void error(String message, Throwable t) {
        if (config.getBoolean("logging.console")) {
            Bukkit.getLogger().severe("[ByteBans] " + message);
        }
        this.verbose(message, "ERROR");
    }

    public void error(String message) {
        if (config.getBoolean("logging.console")) {
            Bukkit.getLogger().severe("[ByteBans] " + message);
        }
        this.verbose(message, "ERROR");
    }

    public void verbose(String message) {
        this.verbose(message, "");
    }

    public void verbose(String message, String messageType) {
        if (!config.getBoolean("logging.verbose.enabled")) return;

        String type = config.getString("logging.verbose.type", "console");

        if (type.equalsIgnoreCase("console") || type.equalsIgnoreCase("both")) {
            Bukkit.getLogger().info("[ByteBans VERBOSE] " + messageType + ": " + message);
        }

        if ((type.equalsIgnoreCase("chat") || type.equalsIgnoreCase("both")) && !Bukkit.getOnlinePlayers().isEmpty()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.isOp()) {
                    player.sendMessage(miniMessage.deserialize("[ByteBans VERBOSE] " + messageType + ": " + message));
                }
            }
        }
    }
}

