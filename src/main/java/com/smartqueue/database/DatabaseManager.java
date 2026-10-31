package com.smartqueue.database;

import com.smartqueue.config.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseManager {
    private final Path databasePath;

    public DatabaseManager() {
        this(com.smartqueue.config.AppConfig.databasePath());
    }

    public DatabaseManager(Path databasePath) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T execute(Connection connection) throws Exception;
    }

    public Connection openConnection() throws SQLException {
        try {
            Files.createDirectories(databasePath.getParent());
        } catch (IOException ex) {
            throw new SQLException("Unable to create the application data directory.", ex);
        }

        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        } catch (SQLException ex) {
            connection.close();
            throw ex;
        }
        return connection;
    }

    public <T> T inWriteTransaction(SqlWork<T> work) throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                T result = work.execute(connection);
                statement.execute("COMMIT");
                return result;
            } catch (Exception ex) {
                try {
                    statement.execute("ROLLBACK");
                } catch (SQLException rollbackError) {
                    ex.addSuppressed(rollbackError);
                }
                if (ex instanceof SQLException sqlException) {
                    throw sqlException;
                }
                if (ex instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new SQLException("Database transaction failed.", ex);
            }
        }
    }

    public boolean isHealthy() {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT 1").close();
            return true;
        } catch (SQLException ex) {
            return false;
        }
    }

    public Path databasePath() {
        return databasePath;
    }
}