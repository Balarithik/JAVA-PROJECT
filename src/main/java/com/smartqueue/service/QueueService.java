package com.smartqueue.service;

import com.smartqueue.database.DatabaseManager;
import com.smartqueue.model.AppUser;
import com.smartqueue.model.GuestQueueEntry;
import com.smartqueue.model.QueueAdvanceResult;
import com.smartqueue.model.QueueStatus;
import com.smartqueue.model.QueueToken;
import com.smartqueue.model.ServiceItem;
import com.smartqueue.model.ServiceQueueSnapshot;
import com.smartqueue.model.UserRole;
import com.smartqueue.repository.QueueRepository;
import com.smartqueue.repository.ServiceRepository;
import com.smartqueue.repository.UserRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public final class QueueService {
    private final DatabaseManager databaseManager;
    private final QueueRepository queueRepository;
    private final ServiceRepository serviceRepository;
    private final UserRepository userRepository;

    public QueueService(DatabaseManager databaseManager, QueueRepository queueRepository,
                        ServiceRepository serviceRepository, UserRepository userRepository) {
        this.databaseManager = databaseManager;
        this.queueRepository = queueRepository;
        this.serviceRepository = serviceRepository;
        this.userRepository = userRepository;
    }

    public GuestQueueEntry joinGuest(String name, int age, long serviceId) throws SQLException {
        String cleanedName = name == null ? "" : name.trim();
        if (cleanedName.length() < 2 || cleanedName.length() > 150) {
            throw new IllegalArgumentException("Enter a name between 2 and 150 characters.");
        }
        if (age < 0 || age > 130) {
            throw new IllegalArgumentException("Enter an age between 0 and 130.");
        }

        return databaseManager.inWriteTransaction(connection -> {
            ServiceItem service = serviceRepository.findById(connection, serviceId)
                    .orElseThrow(() -> new IllegalArgumentException("Selected service does not exist."));
            if (!service.active()) {
                throw new IllegalArgumentException("This service department is currently inactive.");
            }
            AppUser guest = userRepository.createGuest(connection, cleanedName, age);
            String tokenNumber = (service.prefix().isBlank() ? "A" : service.prefix().trim().toUpperCase())
                    + "-" + String.format("%03d", queueRepository.nextSequence(connection, service.id()));
            QueueToken token = queueRepository.insert(connection, guest.id(), service, tokenNumber);
            return new GuestQueueEntry(guest, token);
        });
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
        requireRole(user, UserRole.USER, UserRole.STAFF, UserRole.ADMIN);
        return queueRepository.findActiveForUser(user.id()).map(this::withEstimate);
    }

    public Optional<QueueToken> currentForStaff(AppUser staff) throws SQLException {
        requireRole(staff, UserRole.STAFF, UserRole.ADMIN);
        return queueRepository.findCurrentForStaff(staff.id());
    }

    public List<QueueToken> history(AppUser user) throws SQLException {
        requireRole(user, UserRole.USER, UserRole.STAFF, UserRole.ADMIN);
        return queueRepository.history(user.id());
    }

    public List<ServiceQueueSnapshot> overview() throws SQLException {
        return queueRepository.queueOverview();
    }

    public List<QueueToken> operationalQueue(AppUser staff, long serviceId) throws SQLException {
        requireRole(staff, UserRole.STAFF, UserRole.ADMIN);
        return queueRepository.operationalQueue(serviceId);
    }

    public QueueToken cancel(AppUser user, Long tokenId) throws SQLException {
        requireRole(user, UserRole.USER, UserRole.STAFF, UserRole.ADMIN);
        return databaseManager.inWriteTransaction(connection -> {
            QueueToken token = tokenId == null
                    ? queueRepository.findActiveForUser(user.id()).orElse(null)
                    : queueRepository.findById(connection, tokenId).orElse(null);
            if (token == null || token.userId() != user.id()
                    || (token.status() != QueueStatus.WAITING && token.status() != QueueStatus.CALLED)) {
                throw new IllegalArgumentException("No active or cancellable queue token found.");
            }
            return queueRepository.transition(connection, token.id(), 0, QueueStatus.CANCELLED,
                    List.of(QueueStatus.WAITING, QueueStatus.CALLED), null);
        });
    }

    public Optional<QueueToken> callNext(AppUser staff, Long serviceId, String counter) throws SQLException {
        requireRole(staff, UserRole.STAFF, UserRole.ADMIN);
        return databaseManager.inWriteTransaction(connection -> {
            if (queueRepository.findCurrentForStaff(connection, staff.id()).isPresent()) {
                throw new IllegalStateException("Complete or skip your currently assigned token before calling another customer.");
            }
            return queueRepository.claimNext(connection, serviceId, staff.id(),
                    counter == null || counter.isBlank() ? "Counter 1" : counter.trim());
        });
    }

    public QueueAdvanceResult advanceQueue(AppUser admin, long serviceId, String counter) throws SQLException {
        requireRole(admin, UserRole.STAFF, UserRole.ADMIN);
        String counterName = counter == null || counter.isBlank() ? "Counter 1" : counter.trim();
        return databaseManager.inWriteTransaction(connection -> {
            ServiceItem service = serviceRepository.findById(connection, serviceId)
                    .orElseThrow(() -> new IllegalArgumentException("Selected service does not exist."));
            if (!service.active()) {
                throw new IllegalArgumentException("This service department is currently inactive.");
            }

            Optional<QueueToken> current = queueRepository.findCurrentForStaff(connection, admin.id());
            if (current.isPresent() && current.get().service().id() != serviceId) {
                throw new IllegalArgumentException("Complete the active token for "
                        + current.get().service().name() + " before selecting another service.");
            }

            Optional<QueueToken> completed = Optional.empty();
            if (current.isPresent()) {
                QueueToken token = queueRepository.transition(connection, current.get().id(), admin.id(),
                        QueueStatus.COMPLETED, List.of(QueueStatus.CALLED, QueueStatus.SERVING), null);
                completed = Optional.of(token);
            }
            Optional<QueueToken> next = queueRepository.claimNext(connection, serviceId, admin.id(), counterName);
            if (next.isPresent()) {
                QueueToken serving = queueRepository.transition(connection, next.get().id(), admin.id(),
                        QueueStatus.SERVING, List.of(QueueStatus.CALLED), null);
                next = Optional.of(serving);
            }
            return new QueueAdvanceResult(completed, next);
        });
    }

    public QueueToken startServing(AppUser staff, long tokenId) throws SQLException {
        requireRole(staff, UserRole.STAFF, UserRole.ADMIN);
        return transition(staff, tokenId, QueueStatus.SERVING, List.of(QueueStatus.CALLED), null);
    }

    public QueueToken complete(AppUser staff, long tokenId) throws SQLException {
        requireRole(staff, UserRole.STAFF, UserRole.ADMIN);
        return transition(staff, tokenId, QueueStatus.COMPLETED, List.of(QueueStatus.SERVING, QueueStatus.CALLED), null);
    }

    public QueueToken skip(AppUser staff, long tokenId) throws SQLException {
        requireRole(staff, UserRole.STAFF, UserRole.ADMIN);
        return transition(staff, tokenId, QueueStatus.SKIPPED, List.of(QueueStatus.CALLED), null);
    }

    public QueueToken recall(AppUser staff, long tokenId, String counter) throws SQLException {
        requireRole(staff, UserRole.STAFF, UserRole.ADMIN);
        return databaseManager.inWriteTransaction(connection -> {
            if (queueRepository.findCurrentForStaff(connection, staff.id()).isPresent()) {
                throw new IllegalStateException("Complete or skip your currently assigned token before recalling another customer.");
            }
            return queueRepository.transition(connection, tokenId, staff.id(), QueueStatus.CALLED,
                    List.of(QueueStatus.CALLED, QueueStatus.SKIPPED), counter);
        });
    }

    private QueueToken transition(AppUser staff, long tokenId, QueueStatus target, List<QueueStatus> allowed,
                                  String counter) throws SQLException {
        return databaseManager.inWriteTransaction(connection ->
                queueRepository.transition(connection, tokenId, staff.id(), target, allowed, counter));
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