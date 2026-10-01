package io.github.lianjordaan.byteBans;

import io.github.lianjordaan.byteBans.commands.*;
import io.github.lianjordaan.byteBans.database.Database;
import io.github.lianjordaan.byteBans.database.MySQLDatabase;
import io.github.lianjordaan.byteBans.database.SQLiteDatabase;
import io.github.lianjordaan.byteBans.listeners.ChatListener;
import io.github.lianjordaan.byteBans.listeners.FreezeListener;
import io.github.lianjordaan.byteBans.listeners.LoginListener;
import io.github.lianjordaan.byteBans.punishments.PunishmentUpdater;
import io.github.lianjordaan.byteBans.punishments.PunishmentsHandler;
import io.github.lianjordaan.byteBans.servers.AvailableServersScanner;
import io.github.lianjordaan.byteBans.util.BBLogger;
import io.github.lianjordaan.byteBans.util.DatabaseUtils;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class ByteBans extends JavaPlugin {
    private BBLogger logger;
    private Database database;
    private PunishmentsHandler punishmentsHandler;
    private PunishmentUpdater punishmentUpdater;
    private boolean usingMysql;
    private BukkitTask punishmentUpdateLoop;
    private BukkitTask punishmentFullUpdateLoop;
    private BukkitTask punishmentUpdatePurgeLoop;
    private AvailableServersScanner availableServersScanner;
    private BukkitTask availableServersHeartbeatLoop;
    private BukkitTask availableServersUpdateLoop;
    private BukkitTask availableServersCleanupLoop;
    private String databaseTablePrefix;
    private volatile boolean shuttingDown;
    private volatile String serverName = "*";
    private final Map<String, String> onlineAddresses = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        shuttingDown = false;

        // remove config.yml if it exists
//        File configFile = new File(getDataFolder(), "config.yml");
//        if (configFile.exists()) {
//            configFile.delete();
//        }

        // Plugin startup logic
        saveDefaultConfig();
        serverName = getConfig().getString("server.name", "*");
        try {
            logger = new BBLogger(this);
            logger.info("Custom logging system initialized successfully.");
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to initialize logging system! Disabling plugin...", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }


        logger.verbose("Connecting to database...");
        String type = getConfig().getString("storage.type", "sqlite");
        usingMysql = type.equalsIgnoreCase("mysql");
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
            logger.verbose("Successfully connected to database.");

        } catch (Exception e) {
            logger.error("Failed to initialize database!", e);
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        logger.verbose("Initializing database...");
        try {
            Connection conn = database.getConnection();
            databaseTablePrefix = getConfig().getString("storage.table_prefix", "bytebans_");
            if (databaseTablePrefix == null || !databaseTablePrefix.matches("[A-Za-z][A-Za-z0-9_]{0,30}")) {
                throw new IllegalArgumentException("storage.table_prefix must contain only letters, digits and underscores");
            }

            DatabaseUtils.migrate(conn, databaseTablePrefix, usingMysql);
            logger.info("Database schema is ready (version " + DatabaseUtils.SCHEMA_VERSION + ").");
        } catch (Exception e) {
            logger.error("Failed to create tables for database!", e);
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        logger.verbose("Successfully initialized database.");
        logger.verbose("Initializing punishments handler and loading punishments...");
        try {
            punishmentsHandler = new PunishmentsHandler(this);
        } catch (SQLException e) {
            logger.error("Failed to initialize punishments handler!", e);
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            punishmentsHandler.loadPunishments();
        } catch (SQLException e) {
            logger.error("Failed to load punishments into punishments handler!", e);
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        logger.verbose("Successfully initialized punishments handler and loaded punishments.");
        logger.verbose("Initializing and starting punishment updater...");
        try {
            punishmentUpdater = new PunishmentUpdater(this);
            punishmentUpdateLoop = punishmentUpdater.startUpdate();
            logger.verbose("Started punishment update loop.");
            punishmentFullUpdateLoop = punishmentUpdater.startFullUpdate();
            logger.verbose("Started punishment refresh loop.");
            punishmentUpdatePurgeLoop = punishmentUpdater.startUpdatePurge();
            logger.verbose("Started punishment update purge loop.");
        } catch (SQLException e) {
            logger.error("Failed to initialize punishment updater!", e);
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        logger.verbose("Successfully initialized and started punishment updater.");
        logger.verbose("Initializing available servers updater...");
        try {
            availableServersScanner = new AvailableServersScanner(this);
            availableServersHeartbeatLoop = availableServersScanner.startHeartbeat();
            logger.verbose("Started available servers heartbeat loop.");
            availableServersUpdateLoop = availableServersScanner.startUpdate();
            logger.verbose("Started available servers update loop.");
            availableServersCleanupLoop = availableServersScanner.startCleanup();
            logger.verbose("Started available servers cleanup loop.");
        } catch (SQLException e) {
            logger.error("Failed to initialize available servers updater!", e);
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        logger.verbose("Successfully initialized and started available servers updater.");

        logger.verbose("Initializing plugin commands...");
        try {
            getCommand("mute").setExecutor(new MuteCommand(this));
            getCommand("tempmute").setExecutor(new TempMuteCommand(this));
            getCommand("unmute").setExecutor(new UnmuteCommand(this));

            getCommand("ban").setExecutor(new BanCommand(this));
            getCommand("tempban").setExecutor(new TempBanCommand(this));
            getCommand("unban").setExecutor(new UnbanCommand(this));

            getCommand("kick").setExecutor(new KickCommand(this));

            getCommand("removepunishment").setExecutor(new RemovePunishmentCommand(this));
            getCommand("refreshpunishments").setExecutor(new RefreshPunishmentCommand(this));
            ModerationCommand moderation = new ModerationCommand(this);
            for (String name : new String[]{"ipban", "ipunban", "ipmute", "ipunmute", "warn",
                    "unwarn", "note", "removenote", "freeze", "unfreeze"}) {
                getCommand(name).setExecutor(moderation);
            }
            getCommand("history").setExecutor(new HistoryCommand(this));
        } catch (Exception e) {
            logger.error("Failed to initialize plugin commands!", e);
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        logger.verbose("Successfully initialized plugin commands.");
        logger.verbose("Registering tab completers...");
        try {
            getCommand("mute").setTabCompleter(new TabCompleter(this));
            getCommand("tempmute").setTabCompleter(new TabCompleter(this));
            getCommand("unmute").setTabCompleter(new TabCompleter(this));

            getCommand("ban").setTabCompleter(new TabCompleter(this));
            getCommand("tempban").setTabCompleter(new TabCompleter(this));
            getCommand("unban").setTabCompleter(new TabCompleter(this));

            getCommand("kick").setTabCompleter(new TabCompleter(this));

            getCommand("removepunishment").setTabCompleter(new TabCompleter(this));
            logger.verbose("Registered removepunishment tab completer.");
            TabCompleter moderationCompletion = new TabCompleter(this);
            for (String name : new String[]{"ipban", "ipunban", "ipmute", "ipunmute", "warn",
                    "unwarn", "note", "removenote", "history", "freeze", "unfreeze"}) {
                getCommand(name).setTabCompleter(moderationCompletion);
            }
        } catch (Exception e) {
            logger.error("Failed to register tab completers!", e);
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        logger.verbose("Successfully registered tab completers.");
        try {
            getServer().getPluginManager().registerEvents(new ChatListener(this), this);
            getServer().getPluginManager().registerEvents(new LoginListener(this), this);
            getServer().getPluginManager().registerEvents(new FreezeListener(this), this);
        } catch (Exception e) {
            logger.error("Failed to register event listeners!", e);
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        logger.verbose("Successfully registered event listeners.");


        logger.info("ByteBans was successfully initialized.");
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        onlineAddresses.clear();
        for (BukkitTask task : new BukkitTask[]{punishmentUpdateLoop, punishmentFullUpdateLoop,
                punishmentUpdatePurgeLoop, availableServersHeartbeatLoop, availableServersUpdateLoop,
                availableServersCleanupLoop}) {
            if (task != null) task.cancel();
        }
        if (database != null) {
            synchronized (DatabaseUtils.class) {
                try {
                    database.close();
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "Failed to close ByteBans database", e);
                }
            }
        }
    }

    public void reloadPlugin() {
        logger.info("Reloading plugin...");
        try {
            reloadConfig();
            serverName = getConfig().getString("server.name", "*");
            logger.refresh();
            logger.info("Configuration reloaded successfully.");
        } catch (Exception e) {
            logger.error("Failed to reload plugin configuration", e);
            return;
        }
        logger.verbose("Reloading update loops...");
        try {
            if (punishmentUpdateLoop != null) {
                punishmentUpdateLoop.cancel();
            }
            if (punishmentFullUpdateLoop != null) {
                punishmentFullUpdateLoop.cancel();
            }
            if (punishmentUpdatePurgeLoop != null) {
                punishmentUpdatePurgeLoop.cancel();
            }
            if (availableServersHeartbeatLoop != null) {
                availableServersHeartbeatLoop.cancel();
            }
            if (availableServersUpdateLoop != null) {
                availableServersUpdateLoop.cancel();
            }
            if (availableServersCleanupLoop != null) {
                availableServersCleanupLoop.cancel();
            }
            punishmentUpdateLoop = punishmentUpdater.startUpdate();
            logger.verbose("Started punishment update loop.");
            punishmentFullUpdateLoop = punishmentUpdater.startFullUpdate();
            logger.verbose("Started punishment refresh loop.");
            punishmentUpdatePurgeLoop = punishmentUpdater.startUpdatePurge();
            logger.verbose("Started punishment update purge loop.");
            availableServersHeartbeatLoop = availableServersScanner.startHeartbeat();
            logger.verbose("Started available servers heartbeat loop.");
            availableServersUpdateLoop = availableServersScanner.startUpdate();
            logger.verbose("Started available servers update loop.");
            availableServersCleanupLoop = availableServersScanner.startCleanup();
            logger.verbose("Started available servers cleanup loop.");
        } catch (Exception e) {
            logger.error("Failed to reload update loops!", e);
            e.printStackTrace();
        }
        logger.info("ByteBans reloaded successfully.");
    }

    public BBLogger getBBLogger() {
        return logger;
    }

    public Database getDatabase() {
        return database;
    }

    public PunishmentsHandler getPunishmentsHandler() {
        return punishmentsHandler;
    }

    public PunishmentUpdater getPunishmentUpdater() {
        return punishmentUpdater;
    }

    public boolean isUsingMysql() {
        return usingMysql;
    }

    public AvailableServersScanner getAvailableServersScanner() {
        return availableServersScanner;
    }

    public String getDatabaseTablePrefix() {
        return databaseTablePrefix;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    public String getServerName() {
        return serverName;
    }

    public void setOnlineAddress(String uuid, String address) {
        onlineAddresses.put(uuid, address);
    }

    public String getOnlineAddress(String uuid) {
        return onlineAddresses.get(uuid);
    }

    public void clearOnlineAddress(String uuid) {
        onlineAddresses.remove(uuid);
    }
}
