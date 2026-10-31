package com.smartqueue.service;

import com.smartqueue.database.DatabaseManager;
import com.smartqueue.model.AppUser;
import com.smartqueue.model.QueueStatus;
import com.smartqueue.model.QueueToken;
import com.smartqueue.model.ServiceItem;
import com.smartqueue.model.ServiceQueueSnapshot;
import com.smartqueue.model.UserRole;
import com.smartqueue.repository.QueueRepository;
import com.smartqueue.repository.ServiceRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public final class QueueService {
    private final DatabaseManager databaseManager;
    private final QueueRepository queueRepository;
    private final ServiceRepository serviceRepository;

    public QueueService(DatabaseManager databaseManager, QueueRepository queueRepository,
                        ServiceRepository serviceRepository) {
        this.databaseManager = databaseManager;
        this.queueRepository = queueRepository;
        this.serviceRepository = serviceRepository;
    }

    public QueueToken join(AppUser user, long serviceId) throws SQLException {
        requireRole(user, UserRole.USER, UserRole.STAFF, UserRole.ADMIN);
        return databaseManager.inWriteTransaction(connection -> {
            ServiceItem service = serviceRepository.findById(connection, serviceId)
                    .orElseThrow(() -> new IllegalArgumentException("Selected service does not exist."));
            if (!service.active()) {
                throw new IllegalArgumentException("This service department is currently inactive.");
            }
            if (queueRepository.hasActiveToken(connection, user.id())) {
                throw new IllegalArgumentException("You already have an active queue token. Complete or cancel it first.");
            }
            String tokenNumber = (service.prefix().isBlank() ? "A" : service.prefix().trim().toUpperCase())
                    + "-" + String.format("%03d", queueRepository.nextSequence(connection, service.id()));
            return queueRepository.insert(connection, user.id(), service, tokenNumber);
        });
    }

    public Optional<QueueToken> activeToken(AppUser user) throws SQLException {
        return queueRepository.findActiveForUser(user.id()).map(this::withEstimate);
    }

    public Optional<QueueToken> currentForStaff(AppUser staff) throws SQLException {
        requireRole(staff, UserRole.STAFF, UserRole.ADMIN);
        return queueRepository.findCurrentForStaff(staff.id());
    }

    public List<QueueToken> history(AppUser user) throws SQLException {
        return queueRepository.history(user.id());
    }

    public List<ServiceQueueSnapshot> overview() throws SQLException {
        return queueRepository.queueOverview();
    }

    public QueueToken cancel(AppUser user, Long tokenId) throws SQLException {
        return databaseManager.inWriteTransaction(connection -> {
            QueueToken token = tokenId == null
                    ? queueRepository.findActiveForUser(user.id()).orElse(null)
                    : queueRepository.findById(connection, tokenId).orElse(null);
            if (token == null || token.userId() != user.id()
                    || (token.status() != QueueStatus.WAITING && token.status() != QueueStatus.CALLED)) {
                throw new IllegalArgumentException("No active or cancellable queue token found.");
            }
            return queueRepository.transition(connection, token.id(), user.id(), QueueStatus.CANCELLED,
                    List.of(QueueStatus.WAITING, QueueStatus.CALLED), null);
        });
    }

    public Optional<QueueToken> callNext(AppUser staff, Long serviceId, String counter) throws SQLException {
        requireRole(staff, UserRole.STAFF, UserRole.ADMIN);
        return databaseManager.inWriteTransaction(connection -> queueRepository.claimNext(
                connection, serviceId, staff.id(), counter == null || counter.isBlank() ? "Counter 1" : counter.trim()));
    }

    public QueueToken startServing(AppUser staff, long tokenId) throws SQLException {
        requireRole(staff, UserRole.STAFF, UserRole.ADMIN);
        return transition(tokenId, QueueStatus.SERVING, List.of(QueueStatus.CALLED), null);
    }

    public QueueToken complete(AppUser staff, long tokenId) throws SQLException {
        requireRole(staff, UserRole.STAFF, UserRole.ADMIN);
        return transition(tokenId, QueueStatus.COMPLETED, List.of(QueueStatus.SERVING, QueueStatus.CALLED), null);
    }

    public QueueToken skip(AppUser staff, long tokenId) throws SQLException {
        requireRole(staff, UserRole.STAFF, UserRole.ADMIN);
        return transition(tokenId, QueueStatus.SKIPPED, List.of(QueueStatus.CALLED), null);
    }

    public QueueToken recall(AppUser staff, long tokenId, String counter) throws SQLException {
        requireRole(staff, UserRole.STAFF, UserRole.ADMIN);
        return transition(tokenId, QueueStatus.CALLED, List.of(QueueStatus.CALLED, QueueStatus.SKIPPED), counter);
    }

    private QueueToken transition(long tokenId, QueueStatus target, List<QueueStatus> allowed, String counter)
            throws SQLException {
        return databaseManager.inWriteTransaction(connection ->
                queueRepository.transition(connection, tokenId, 0, target, allowed, counter));
    }

    private QueueToken withEstimate(QueueToken token) {
        try {
            int ahead = queueRepository.countPeopleAhead(token);
            int wait = ahead <= 0 ? 0 : ahead * Math.max(token.service().averageServiceTime(), 1);
            return new QueueToken(token.id(), token.tokenNumber(), token.userId(), token.userName(), token.userEmail(),
                    token.service(), token.status(), token.joinedAt(), token.calledAt(), token.startedAt(),
                    token.completedAt(), token.counterNumber(), token.servedById(), token.servedByName(), ahead, wait);
        } catch (SQLException ex) {
            throw new IllegalStateException("Unable to calculate the current queue position.", ex);
        }
    }

    private void requireRole(AppUser user, UserRole... roles) {
        if (user == null || !user.active()) {
            throw new IllegalArgumentException("Authentication required.");
        }
        for (UserRole role : roles) {
            if (user.role() == role) {
                return;
            }
        }
        throw new SecurityException("Your account role cannot perform this queue action.");
    }
}