package io.github.lianjordaan.byteBans.punishments;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.model.PunishmentData;
import io.github.lianjordaan.byteBans.util.BBLogger;
import io.github.lianjordaan.byteBans.util.DatabaseUtils;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;


public class PunishmentUpdater {
    private ByteBans plugin;
    private Connection connection;
    private BBLogger logger;
    private long lastProcessedUpdateId;

    public PunishmentUpdater(ByteBans plugin) throws SQLException {
        this.plugin = plugin;
        this.connection = plugin.getDatabase().getConnection();
        this.logger = plugin.getBBLogger();

        setLastProcessedUpdateId();
        logger.verbose("Last processed update ID: " + lastProcessedUpdateId);
    }

    public void setLastProcessedUpdateId() {
        try {
            lastProcessedUpdateId = DatabaseUtils.getLastProcessedUpdateId(connection, plugin.getDatabaseTablePrefix());
        } catch (SQLException e) {
            logger.error("Failed to get last processed update ID!", e);
            e.printStackTrace();
        }
    }

    public BukkitTask startUpdate() {
        long interval = plugin.getConfig().getLong("sync.update_interval", 1) * 20;
        return new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String prefix = plugin.getDatabaseTablePrefix();

                    long newLastUpdateId = DatabaseUtils.getLastUpdateId(connection, prefix, lastProcessedUpdateId);
                    if (newLastUpdateId > lastProcessedUpdateId) {
                        logger.verbose("Updating punishments...");
                        logger.verbose("Old last processed update ID: " + lastProcessedUpdateId);
                        lastProcessedUpdateId = newLastUpdateId;
                        logger.verbose("New last processed update ID: " + lastProcessedUpdateId);

                        plugin.getPunishmentsHandler().reloadPunishments();
                    } else {
//                        logger.verbose("No updates found.");
                    }
                } catch (SQLException e) {
                    logger.error("Failed to update punishments!", e);
                    e.printStackTrace();
                }
            }
        }.runTaskTimerAsynchronously(plugin, interval, interval);
    }

    public BukkitTask startFullUpdate() {
        long interval = plugin.getConfig().getLong("sync.full_update_interval", 600) * 20;
        return new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String prefix = plugin.getDatabaseTablePrefix();

                    logger.verbose("Getting all punishments from the database...");
                    plugin.getPunishmentsHandler().reloadPunishments();
                    logger.verbose("All punishments refreshed.");
                } catch (SQLException e) {
                    logger.error("Failed to update punishments!", e);
                    e.printStackTrace();
                }
            }
        }.runTaskTimerAsynchronously(plugin, interval, interval);
    }

    public BukkitTask startUpdatePurge() {
        long interval = plugin.getConfig().getLong("sync.update_retention", 10) * 20 * 60;
        return new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String prefix = plugin.getDatabaseTablePrefix();

                    logger.verbose("Purging old punishment updates...");
                    DatabaseUtils.purgeOldUpdates(connection, prefix, plugin.getConfig().getLong("sync.update_retention", 10));
                    logger.verbose("Old punishment updates purged.");
                } catch (SQLException e) {
                    logger.error("Failed to purge old punishment updates!", e);
                    e.printStackTrace();
                }
            }
        }.runTaskTimerAsynchronously(plugin, interval, interval);
    }
}
