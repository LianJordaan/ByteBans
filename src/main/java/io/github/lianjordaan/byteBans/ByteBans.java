package io.github.lianjordaan.byteBans;

import io.github.lianjordaan.byteBans.database.Database;
import io.github.lianjordaan.byteBans.database.MySQLDatabase;
import io.github.lianjordaan.byteBans.database.SQLiteDatabase;
import io.github.lianjordaan.byteBans.util.BBLogger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Level;

public final class ByteBans extends JavaPlugin {
    private BBLogger logger;
    private Database database;

    @Override
    public void onEnable() {

        // remove config.yml if it exists
        File configFile = new File(getDataFolder(), "config.yml");
        if (configFile.exists()) {
            configFile.delete();
        }

        // Plugin startup logic
        saveDefaultConfig();
        try {
            logger = new BBLogger(getConfig(), this);
            logger.info("Custom logging system initialized successfully.");
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to initialize logging system! Disabling plugin...", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }


        logger.info("Initializing database...");
        String type = getConfig().getString("storage.type", "mysql");
        try {
            if (type.equalsIgnoreCase("sqlite")) {
                database = new SQLiteDatabase(this, getConfig().getString("storage.sqlite.file", "database.db"));
            } else if (type.equalsIgnoreCase("mysql")) {
                database = new MySQLDatabase(
                        getConfig().getString("storage.mysql.host", "localhost"),
                        getConfig().getInt("storage.mysql.port", 3306),
                        getConfig().getString("storage.mysql.database", "bytebans"),
                        getConfig().getString("storage.mysql.username", ""),
                        getConfig().getString("storage.mysql.password", "")
                );
            } else {
                throw new Exception("Invalid database type: " + type);
            }
            database.init();
            getLogger().info("Database initialized successfully!");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize database!");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    public void reloadPlugin() {
        logger.info("Reloading plugin...");
        try {
            reloadConfig();
            logger = new BBLogger(getConfig(), this);
            logger.info("Configuration reloaded successfully.");
        } catch (Exception e) {
            logger.error("Failed to reload plugin configuration", e);
            return;
        }
    }

    public BBLogger getBBLogger() {
        return logger;
    }

    public Database getDatabase() {
        return database;
    }
}
