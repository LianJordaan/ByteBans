package io.github.lianjordaan.byteBans.database;

import io.github.lianjordaan.byteBans.ByteBans;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class SQLiteDatabase implements Database {
    private final ByteBans plugin;
    private Connection connection;
    private String dbFile;

    public SQLiteDatabase(ByteBans plugin, String dbFile) {
        this.plugin = plugin;
        this.dbFile = dbFile;
    }

    @Override
    public void init() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC not found", e);
        }

        String url = "jdbc:sqlite:" + plugin.getDataFolder() + "/" + dbFile;
        connection = DriverManager.getConnection(url);
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) init();
        return connection;
    }

    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) connection.close();
    }
}
