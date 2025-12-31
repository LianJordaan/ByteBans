package io.github.lianjordaan.byteBans.database;

import java.sql.Connection;
import java.sql.SQLException;

public interface Database {
    Connection getConnection() throws SQLException;
    void init() throws SQLException;
    void close() throws SQLException;
}
