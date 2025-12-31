package io.github.lianjordaan.byteBans.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class MySQLDatabase implements Database {
    private Connection connection;
    private final String host, database, username, password;
    private final int port;

    public MySQLDatabase(String host, int port, String database, String username, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
    }

    @Override
    public void init() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL JDBC driver not found", e);
        }

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&serverTimezone=UTC";
        connection = DriverManager.getConnection(url, username, password);
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
