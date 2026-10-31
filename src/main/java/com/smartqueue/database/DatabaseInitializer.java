package com.smartqueue.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public final class DatabaseInitializer {
    private final DatabaseManager databaseManager;

    public DatabaseInitializer(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void initialize() throws SQLException {
        try (Connection connection = databaseManager.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
        }

        databaseManager.inWriteTransaction(connection -> {
            for (String sql : schemaStatements()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            return null;
        });
    }

    private List<String> schemaStatements() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS smartqueue_schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS accounts_user ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, password VARCHAR(128) NOT NULL, last_login TEXT NULL, "
                        + "is_superuser INTEGER NOT NULL DEFAULT 0, email VARCHAR(255) NOT NULL UNIQUE, "
                        + "name VARCHAR(150) NOT NULL, phone VARCHAR(25) NOT NULL DEFAULT '', "
                        + "role VARCHAR(20) NOT NULL DEFAULT 'USER', is_active INTEGER NOT NULL DEFAULT 1, "
                        + "is_staff INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS accounts_user_role_idx ON accounts_user(role)",
                "CREATE TABLE IF NOT EXISTS services_service ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, name VARCHAR(150) NOT NULL UNIQUE, "
                        + "prefix VARCHAR(5) NOT NULL DEFAULT 'A', description TEXT NOT NULL DEFAULT '', "
                        + "average_service_time INTEGER NOT NULL DEFAULT 5 CHECK(average_service_time >= 0), "
                        + "is_active INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS services_service_active_idx ON services_service(is_active)",
                "CREATE TABLE IF NOT EXISTS queues_queuetoken ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, token_number VARCHAR(30) NOT NULL, "
                        + "user_id INTEGER NOT NULL REFERENCES accounts_user(id) ON DELETE CASCADE, "
                        + "service_id INTEGER NOT NULL REFERENCES services_service(id) ON DELETE CASCADE, "
                        + "status VARCHAR(20) NOT NULL DEFAULT 'WAITING', joined_at TEXT NOT NULL, "
                        + "called_at TEXT NULL, started_at TEXT NULL, completed_at TEXT NULL, "
                        + "counter_number VARCHAR(50) NOT NULL DEFAULT '', "
                        + "served_by_id INTEGER NULL REFERENCES accounts_user(id) ON DELETE SET NULL)",
                "CREATE INDEX IF NOT EXISTS queues_token_number_idx ON queues_queuetoken(token_number)",
                "CREATE INDEX IF NOT EXISTS queues_service_status_joined_idx ON queues_queuetoken(service_id, status, joined_at)",
                "CREATE INDEX IF NOT EXISTS queues_user_status_idx ON queues_queuetoken(user_id, status)",
                "CREATE INDEX IF NOT EXISTS queues_status_joined_idx ON queues_queuetoken(status, joined_at)",
                "CREATE TABLE IF NOT EXISTS smartqueue_session ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES accounts_user(id) ON DELETE CASCADE, "
                    + "token_hash VARCHAR(64) NOT NULL UNIQUE, created_at TEXT NOT NULL, expires_at TEXT NOT NULL, revoked_at TEXT NULL)",
                "CREATE INDEX IF NOT EXISTS smartqueue_session_user_expiry_idx ON smartqueue_session(user_id, expires_at)",
                "INSERT OR IGNORE INTO smartqueue_schema_version(version, applied_at) VALUES (1, CURRENT_TIMESTAMP)",
                "INSERT OR IGNORE INTO smartqueue_schema_version(version, applied_at) VALUES (2, CURRENT_TIMESTAMP)"
        );
    }
}