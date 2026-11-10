package com.smartqueue.repository;

import com.smartqueue.database.DatabaseManager;
import com.smartqueue.model.QueueStatus;
import com.smartqueue.model.QueueToken;
import com.smartqueue.model.ServiceItem;
import com.smartqueue.model.ServiceQueueSnapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class QueueRepository {
    private static final String TOKEN_SELECT = "SELECT q.id,q.token_number,q.user_id,u.name AS user_name,u.email AS user_email, "
            + "q.service_id,s.name AS service_name,s.prefix AS service_prefix,s.description AS service_description, "
            + "s.average_service_time,s.is_active AS service_active,q.status,q.joined_at,q.called_at,q.started_at, "
            + "q.completed_at,q.counter_number,q.served_by_id,staff.name AS served_by_name "
            + "FROM queues_queuetoken q JOIN accounts_user u ON u.id=q.user_id "
            + "JOIN services_service s ON s.id=q.service_id "
            + "LEFT JOIN accounts_user staff ON staff.id=q.served_by_id ";

    private final DatabaseManager databaseManager;

    public QueueRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public boolean hasActiveToken(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM queues_queuetoken WHERE user_id=? AND status IN ('WAITING','CALLED','SERVING') LIMIT 1")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public int nextSequence(Connection connection, long serviceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*)+1 FROM queues_queuetoken WHERE service_id=?")) {
            statement.setLong(1, serviceId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    public QueueToken insert(Connection connection, long userId, ServiceItem service, String tokenNumber)
            throws SQLException {
        Instant now = Instant.now();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO queues_queuetoken(token_number,user_id,service_id,status,joined_at,counter_number) "
                        + "VALUES(?,?,?,'WAITING',?,'')", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, tokenNumber);
            statement.setLong(2, userId);
            statement.setLong(3, service.id());
            statement.setString(4, now.toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The database did not return the new token id.");
                }
                return new QueueToken(keys.getLong(1), tokenNumber, userId, "", "", service,
                        QueueStatus.WAITING, now, null, null, null, "", null, null, 0, 0);
            }
        }
    }

    public Optional<QueueToken> findActiveForUser(long userId) throws SQLException {
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(TOKEN_SELECT
                     + "WHERE q.user_id=? AND q.status IN ('WAITING','CALLED','SERVING') ORDER BY q.joined_at LIMIT 1")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapToken(result)) : Optional.empty();
            }
        }
    }

    public Optional<QueueToken> findCurrentForStaff(long staffId) throws SQLException {
        try (Connection connection = databaseManager.openConnection()) {
            return findCurrentForStaff(connection, staffId);
        }
    }

    public Optional<QueueToken> findCurrentForStaff(Connection connection, long staffId) throws SQLException {
        String staffFilter = staffId == 0 ? "q.served_by_id IS NULL" : "q.served_by_id=?";
        String sql = TOKEN_SELECT + "WHERE " + staffFilter + " AND q.status IN ('CALLED','SERVING') "
                + "ORDER BY q.called_at DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (staffId != 0) {
                statement.setLong(1, staffId);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapToken(result)) : Optional.empty();
            }
        }
    }

    public Optional<QueueToken> findById(Connection connection, long tokenId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TOKEN_SELECT + "WHERE q.id=?")) {
            statement.setLong(1, tokenId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapToken(result)) : Optional.empty();
            }
        }
    }

    public Optional<QueueToken> findById(long tokenId) throws SQLException {
        try (Connection connection = databaseManager.openConnection()) {
            return findById(connection, tokenId);
        }
    }

    public int countPeopleAhead(QueueToken token) throws SQLException {
        if (token.status() != QueueStatus.WAITING) {
            return 0;
        }
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM queues_queuetoken WHERE service_id=? AND status='WAITING' AND joined_at<? AND id<>?")) {
            statement.setLong(1, token.service().id());
            statement.setString(2, token.joinedAt().toString());
            statement.setLong(3, token.id());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    public List<QueueToken> history(long userId) throws SQLException {
        String sql = TOKEN_SELECT + "WHERE q.user_id=? AND q.status IN ('COMPLETED','SKIPPED','CANCELLED') "
                + "ORDER BY q.joined_at DESC LIMIT 50";
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                List<QueueToken> tokens = new ArrayList<>();
                while (result.next()) {
                    tokens.add(mapToken(result));
                }
                return tokens;
            }
        }
    }

    public List<ServiceQueueSnapshot> queueOverview() throws SQLException {
        String sql = "SELECT s.id,s.name,s.prefix,s.description,s.average_service_time,s.is_active, "
                + "(SELECT COUNT(*) FROM queues_queuetoken w WHERE w.service_id=s.id AND w.status='WAITING') AS waiting_count, "
                + "(SELECT q.token_number FROM queues_queuetoken q WHERE q.service_id=s.id "
                + "AND q.status IN ('CALLED','SERVING') ORDER BY q.called_at DESC LIMIT 1) AS current_token, "
                + "(SELECT q.counter_number FROM queues_queuetoken q WHERE q.service_id=s.id "
                + "AND q.status IN ('CALLED','SERVING') ORDER BY q.called_at DESC LIMIT 1) AS current_counter "
                + "FROM services_service s WHERE s.is_active=1 ORDER BY s.name";
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            List<ServiceQueueSnapshot> snapshots = new ArrayList<>();
            while (result.next()) {
                int waiting = result.getInt("waiting_count");
                ServiceItem service = new ServiceItem(result.getLong("id"), result.getString("name"),
                        result.getString("prefix"), result.getString("description"),
                        result.getInt("average_service_time"), result.getInt("is_active") == 1, 0);
                snapshots.add(new ServiceQueueSnapshot(service, waiting, result.getString("current_token"),
                        result.getString("current_counter"), waiting * Math.max(service.averageServiceTime(), 1)));
            }
            return snapshots;
        }
    }

    public Optional<QueueToken> claimNext(Connection connection, Long serviceId, long staffId, String counter)
            throws SQLException {
        String selectSql = "SELECT id FROM queues_queuetoken WHERE status='WAITING' "
                + (serviceId == null ? "" : "AND service_id=? ") + "ORDER BY joined_at,id LIMIT 1";
        long tokenId;
        try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
            if (serviceId != null) {
                statement.setLong(1, serviceId);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                tokenId = result.getLong(1);
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE queues_queuetoken SET status='CALLED',called_at=?,counter_number=?,served_by_id=? "
                        + "WHERE id=? AND status='WAITING'")) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, counter);
            if (staffId == 0) {
                statement.setNull(3, java.sql.Types.INTEGER);
            } else {
                statement.setLong(3, staffId);
            }
            statement.setLong(4, tokenId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("The next waiting token was claimed by another action.");
            }
        }
        return findById(connection, tokenId);
    }

    public QueueToken transition(Connection connection, long tokenId, long staffId, QueueStatus target,
                                 List<QueueStatus> allowed, String counter) throws SQLException {
        String expected = allowed.stream().map(status -> "'" + status.name() + "'")
                .reduce((left, right) -> left + "," + right).orElse("''");
        StringBuilder sql = new StringBuilder("UPDATE queues_queuetoken SET status=?");
        switch (target) {
            case SERVING -> sql.append(",started_at=?");
            case COMPLETED, SKIPPED, CANCELLED -> sql.append(",completed_at=?");
            case CALLED -> sql.append(",called_at=?,completed_at=NULL");
            default -> {
            }
        }
        if (target == QueueStatus.CALLED && counter != null && !counter.isBlank()) {
            sql.append(",counter_number=?");
        }
        sql.append(" WHERE id=? AND status IN (").append(expected).append(')');

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            statement.setString(parameter++, target.name());
            if (target == QueueStatus.SERVING || target == QueueStatus.COMPLETED
                    || target == QueueStatus.SKIPPED || target == QueueStatus.CANCELLED
                    || target == QueueStatus.CALLED) {
                statement.setString(parameter++, Instant.now().toString());
            }
            if (target == QueueStatus.CALLED && counter != null && !counter.isBlank()) {
                statement.setString(parameter++, counter);
            }
            statement.setLong(parameter, tokenId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("This token cannot transition to " + target + " from its current status.");
            }
        }
        return findById(connection, tokenId).orElseThrow(() -> new SQLException("Updated token was not found."));
    }

    public QueueToken transitionForStaff(Connection connection, long tokenId, long staffId, QueueStatus target,
                                         List<QueueStatus> allowed, String counter) throws SQLException {
        QueueToken token = findById(connection, tokenId)
                .orElseThrow(() -> new IllegalArgumentException("Queue token was not found."));
        if (target == QueueStatus.SERVING || target == QueueStatus.COMPLETED
                || target == QueueStatus.SKIPPED || target == QueueStatus.CALLED) {
            return transition(connection, tokenId, staffId, target, allowed, counter);
        }
        return token;
    }

    public int countActiveTokens() throws SQLException {
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM queues_queuetoken WHERE status IN ('WAITING','CALLED','SERVING')");
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private QueueToken mapToken(ResultSet result) throws SQLException {
        ServiceItem service = new ServiceItem(result.getLong("service_id"), result.getString("service_name"),
                result.getString("service_prefix"), result.getString("service_description"),
                result.getInt("average_service_time"), result.getInt("service_active") == 1, 0);
        long servedBy = result.getLong("served_by_id");
        Long servedById = result.wasNull() ? null : servedBy;
        return new QueueToken(result.getLong("id"), result.getString("token_number"),
                result.getLong("user_id"), result.getString("user_name"), result.getString("user_email"), service,
                QueueStatus.valueOf(result.getString("status")), parseInstant(result.getString("joined_at")),
                parseInstant(result.getString("called_at")), parseInstant(result.getString("started_at")),
                parseInstant(result.getString("completed_at")), result.getString("counter_number"), servedById,
                result.getString("served_by_name"), 0, 0);
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return OffsetDateTime.parse(value).toInstant();
        }
    }
}