package io.github.lianjordaan.byteBans.servers;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.util.BBLogger;
import io.github.lianjordaan.byteBans.util.DatabaseUtils;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class AvailableServersScanner {
    private ByteBans plugin;
    private Connection connection;
    private BBLogger logger;
    private List<String> availableServers;

    public AvailableServersScanner(ByteBans plugin) throws SQLException {
        this.plugin = plugin;
        this.connection = plugin.getDatabase().getConnection();
        this.logger = plugin.getBBLogger();
    }

    public BukkitTask startHeartbeat() {
        long interval = plugin.getConfig().getLong("available_servers.send_interval", 60) * 20;
        return new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    logger.verbose("Sending server heartbeat...");
                    DatabaseUtils.updateServerHeartbeat(connection, plugin.getDatabaseTablePrefix(), plugin.getConfig().getString("server.name", "*"));
                    logger.verbose("Server heartbeat sent.");
                } catch (SQLException e) {
                    logger.error("Failed to send server heartbeat!", e);
                    e.printStackTrace();
                }
            }
        }.runTaskTimerAsynchronously(plugin, 0, interval);
    }

    public BukkitTask startUpdate() {
        long interval = plugin.getConfig().getLong("available_servers.update_interval", 60) * 20;
        return new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    long heartbeatTimeout = plugin.getConfig().getLong("available_servers.heartbeat_timeout", 600) * 1000;
                    String prefix = plugin.getDatabaseTablePrefix();

                    logger.verbose("Updating available servers...");
                    availableServers = DatabaseUtils.getServerHeartbeats(connection, prefix, heartbeatTimeout);
                    logger.verbose("Available servers updated.");
                } catch (SQLException e) {
                    logger.error("Failed to update available servers!", e);
                    e.printStackTrace();
                }
            }
        }.runTaskTimerAsynchronously(plugin, 0, interval);
    }

    public BukkitTask startCleanup() {
        long interval = plugin.getConfig().getLong("available_servers.heartbeat_cleanup_interval", 600) * 20;
        return new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    long heartbeatRetention = plugin.getConfig().getLong("available_servers.heartbeat_retention", 3600) * 1000;
                    String prefix = plugin.getDatabaseTablePrefix();

                    logger.verbose("Cleaning up old heartbeats...");
                    DatabaseUtils.cleanupOldHeartbeats(connection, prefix, heartbeatRetention);
                    logger.verbose("Old heartbeats cleaned up.");
                } catch (SQLException e) {
                    logger.error("Failed to clean up old heartbeats!", e);
                    e.printStackTrace();
                }
            }
        }.runTaskTimerAsynchronously(plugin, interval, interval);
    }

    public List<String> getAvailableServers() {
        return availableServers;
    }
}
