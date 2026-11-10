package com.smartqueue.service;

import com.smartqueue.database.DatabaseManager;
import com.smartqueue.model.AnalyticsSummary;
import com.smartqueue.model.AppUser;
import com.smartqueue.model.Metric;
import com.smartqueue.model.UserRole;
import com.smartqueue.repository.QueueRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class AnalyticsService {
    private final DatabaseManager databaseManager;
    private final QueueRepository queueRepository;

    public AnalyticsService(DatabaseManager databaseManager, QueueRepository queueRepository) {
        this.databaseManager = databaseManager;
        this.queueRepository = queueRepository;
    }

    public AnalyticsSummary load(AppUser user) throws SQLException {
        if (user == null || user.role() != UserRole.ADMIN || !user.active()) {
            throw new SecurityException("Administrator access is required.");
        }
        try (Connection connection = databaseManager.openConnection()) {
            return new AnalyticsSummary(
                    query(connection, "SELECT s.name,CAST(AVG((julianday(q.called_at)-julianday(q.joined_at))*1440) AS INTEGER) "
                                    + "FROM queues_queuetoken q JOIN services_service s ON s.id=q.service_id "
                                    + "WHERE q.status='COMPLETED' AND q.called_at IS NOT NULL GROUP BY s.id,s.name ORDER BY s.name"),
                    query(connection, "SELECT date(completed_at),COUNT(*) FROM queues_queuetoken "
                                    + "WHERE status='COMPLETED' GROUP BY date(completed_at) ORDER BY date(completed_at) DESC LIMIT 7"),
                    query(connection, "SELECT CAST(strftime('%H',joined_at) AS INTEGER),COUNT(*) FROM queues_queuetoken "
                                    + "GROUP BY strftime('%H',joined_at) ORDER BY strftime('%H',joined_at)"),
                    query(connection, "SELECT s.name,COUNT(*) FROM queues_queuetoken q "
                                    + "JOIN services_service s ON s.id=q.service_id GROUP BY s.id,s.name ORDER BY COUNT(*) DESC"));
        }
    }

    public int activeTokenCount() throws SQLException {
        return queueRepository.countActiveTokens();
    }

    private List<Metric> query(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            List<Metric> metrics = new ArrayList<>();
            while (result.next()) {
                metrics.add(new Metric(result.getString(1), result.getLong(2)));
            }
            return metrics;
        }
    }
}