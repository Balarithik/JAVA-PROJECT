package com.smartqueue.repository;

import com.smartqueue.database.DatabaseManager;
import com.smartqueue.model.ServiceItem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ServiceRepository {
    private final DatabaseManager databaseManager;

    public ServiceRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public List<ServiceItem> list(boolean activeOnly) throws SQLException {
        try (Connection connection = databaseManager.openConnection()) {
            String sql = "SELECT s.id,s.name,s.prefix,s.description,s.average_service_time,s.is_active, "
                    + "(SELECT COUNT(*) FROM queues_queuetoken q WHERE q.service_id=s.id "
                    + "AND q.status IN ('WAITING','CALLED','SERVING')) AS active_count "
                    + "FROM services_service s " + (activeOnly ? "WHERE s.is_active=1 " : "")
                    + "ORDER BY s.name";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet result = statement.executeQuery()) {
                List<ServiceItem> services = new ArrayList<>();
                while (result.next()) {
                    services.add(map(result));
                }
                return services;
            }
        }
    }

    public Optional<ServiceItem> findById(Connection connection, long serviceId) throws SQLException {
        String sql = "SELECT id,name,prefix,description,average_service_time,is_active,0 AS active_count "
                + "FROM services_service WHERE id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, serviceId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    public List<ServiceItem> list(Connection connection, boolean activeOnly) throws SQLException {
        String sql = "SELECT s.id,s.name,s.prefix,s.description,s.average_service_time,s.is_active, "
                + "(SELECT COUNT(*) FROM queues_queuetoken q WHERE q.service_id=s.id "
                + "AND q.status IN ('WAITING','CALLED','SERVING')) AS active_count "
                + "FROM services_service s " + (activeOnly ? "WHERE s.is_active=1 " : "")
                + "ORDER BY s.name";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            List<ServiceItem> services = new ArrayList<>();
            while (result.next()) {
                services.add(map(result));
            }
            return services;
        }
    }

    public ServiceItem create(String name, String prefix, String description, int averageMinutes)
            throws SQLException {
        String now = java.time.Instant.now().toString();
        return databaseManager.inWriteTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO services_service(name,prefix,description,average_service_time,is_active,created_at,updated_at) "
                            + "VALUES(?,?,?,?,1,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, name.trim());
                statement.setString(2, prefix.trim().toUpperCase(java.util.Locale.ROOT));
                statement.setString(3, description == null ? "" : description.trim());
                statement.setInt(4, averageMinutes);
                statement.setString(5, now);
                statement.setString(6, now);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("The database did not return the new service id.");
                    }
                    return findById(connection, keys.getLong(1))
                            .orElseThrow(() -> new SQLException("The newly created service could not be loaded."));
                }
            }
        });
    }

    public void setActive(long serviceId, boolean active) throws SQLException {
        databaseManager.inWriteTransaction(connection -> {
            if (!active) {
                try (PreparedStatement check = connection.prepareStatement(
                        "SELECT COUNT(*) FROM queues_queuetoken WHERE service_id=? "
                                + "AND status IN ('WAITING','CALLED','SERVING')")) {
                    check.setLong(1, serviceId);
                    try (ResultSet result = check.executeQuery()) {
                        result.next();
                        if (result.getInt(1) > 0) {
                            throw new IllegalStateException("Complete or cancel all active tokens before deactivating this service.");
                        }
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE services_service SET is_active=?,updated_at=? WHERE id=?")) {
                statement.setInt(1, active ? 1 : 0);
                statement.setString(2, java.time.Instant.now().toString());
                statement.setLong(3, serviceId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalArgumentException("Service was not found.");
                }
            }
            return null;
        });
    }

    private ServiceItem map(ResultSet result) throws SQLException {
        return new ServiceItem(result.getLong("id"), result.getString("name"), result.getString("prefix"),
                result.getString("description"), result.getInt("average_service_time"),
                result.getInt("is_active") == 1, result.getInt("active_count"));
    }
}