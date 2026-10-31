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

    private ServiceItem map(ResultSet result) throws SQLException {
        return new ServiceItem(result.getLong("id"), result.getString("name"), result.getString("prefix"),
                result.getString("description"), result.getInt("average_service_time"),
                result.getInt("is_active") == 1, result.getInt("active_count"));
    }
}