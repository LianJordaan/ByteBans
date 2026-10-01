package io.github.lianjordaan.byteBans.util;

import io.github.lianjordaan.byteBans.ByteBans;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.logging.Level;

public class BBLogger {
    private final ByteBans plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private volatile boolean console;
    private volatile boolean verbose;
    private volatile String verboseType;

    public BBLogger(ByteBans plugin) {
        this.plugin = plugin;
        refresh();
    }

    /** Capture Bukkit configuration on the main thread for asynchronous database callbacks. */
    public void refresh() {
        console = plugin.getConfig().getBoolean("logging.console");
        verbose = plugin.getConfig().getBoolean("logging.verbose.enabled");
        verboseType = plugin.getConfig().getString("logging.verbose.type", "console").toLowerCase(Locale.ROOT);
    }

    public void info(String message) {
        if (console) {
            plugin.getLogger().info(message);
        }
        this.verbose(message, "INFO");
    }

    public void error(String message, Throwable t) {
        if (console) {
            plugin.getLogger().log(Level.SEVERE, message, t);
        }
        this.verbose(message, "ERROR");
    }

    public void error(String message) {
        if (console) {
            plugin.getLogger().severe(message);
        }
        this.verbose(message, "ERROR");
    }

    public void verbose(String message) {
        this.verbose(message, "");
    }

    public void verbose(String message, String messageType) {
        if (!verbose) return;
        String type = verboseType;
        if (type.equals("console") || type.equals("both")) {
            plugin.getLogger().info("[VERBOSE] " + messageType + ": " + message);
        }
        if (type.equals("chat") || type.equals("both")) {
            Runnable sendToStaff = () -> {
                if (plugin.isShuttingDown()) return;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.isOp()) {
                        player.sendMessage(miniMessage.deserialize("[ByteBans VERBOSE] " + messageType + ": " + message));
                    }
                }
            };
            if (Bukkit.isPrimaryThread()) sendToStaff.run();
            else if (!plugin.isShuttingDown()) Bukkit.getScheduler().runTask(plugin, sendToStaff);
        }
    }
}

