package com.smartqueue.service;

import com.smartqueue.database.DatabaseInitializer;
import com.smartqueue.database.DatabaseManager;
import com.smartqueue.model.AppUser;
import com.smartqueue.model.AnalyticsSummary;
import com.smartqueue.model.QueueStatus;
import com.smartqueue.model.QueueToken;
import com.smartqueue.model.UserRole;
import com.smartqueue.repository.QueueRepository;
import com.smartqueue.repository.ServiceRepository;
import com.smartqueue.repository.UserRepository;
import com.smartqueue.seed.DemoDataSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class QueueServiceTest {
    @TempDir
    Path temporaryDirectory;

    private DatabaseManager databaseManager;
    private QueueService queueService;
    private UserRepository userRepository;
    private long serviceId;

    @BeforeEach
    void setUp() throws Exception {
        databaseManager = new DatabaseManager(temporaryDirectory.resolve("smartqueue.db"));
        new DatabaseInitializer(databaseManager).initialize();
        userRepository = new UserRepository(databaseManager);
        QueueRepository queueRepository = new QueueRepository(databaseManager);
        ServiceRepository serviceRepository = new ServiceRepository(databaseManager);
        queueService = new QueueService(databaseManager, queueRepository, serviceRepository, userRepository);
        serviceId = createService("General Consultation", "A", 5, true);
    }

    @Test
    void joinUsesServiceSequenceFifoPositionAndWaitingFormula() throws Exception {
        AppUser first = createGuest("First Customer");
        AppUser second = createGuest("Second Customer");

        QueueToken firstToken = queueService.join(first, serviceId);
        QueueToken secondToken = queueService.join(second, serviceId);
        QueueToken secondWithEstimate = queueService.activeToken(second).orElseThrow();

        assertEquals("A-001", firstToken.tokenNumber());
        assertEquals("A-002", secondToken.tokenNumber());
        assertEquals(QueueStatus.WAITING, secondWithEstimate.status());
        assertEquals(1, secondWithEstimate.peopleAhead());
        assertEquals(5, secondWithEstimate.estimatedWaitTime());
        assertThrows(IllegalArgumentException.class, () -> queueService.join(second, serviceId));
    }

    @Test
    void kioskJoinStoresNameAndAgeWithoutCredentials() throws Exception {
        var joined = queueService.joinGuest("Mina Reyes", 34, serviceId);
        QueueToken token = joined.token();
        AppUser guest = joined.guest();

        assertEquals("Mina Reyes", guest.name());
        assertEquals(34, guest.age());
        assertEquals(QueueStatus.WAITING, token.status());
        assertTrue(queueService.activeToken(guest).isPresent());
        assertThrows(IllegalArgumentException.class, () -> queueService.joinGuest("M", 34, serviceId));
        assertThrows(IllegalArgumentException.class, () -> queueService.joinGuest("Mina Reyes", -1, serviceId));
        assertThrows(IllegalArgumentException.class, () -> queueService.joinGuest("Mina Reyes", 131, serviceId));
    }

    @Test
    void ageColumnMigrationPreservesLegacyAccounts() throws Exception {
        DatabaseManager legacyDatabase = new DatabaseManager(temporaryDirectory.resolve("legacy.db"));
        try (Connection connection = legacyDatabase.openConnection();
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE accounts_user ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, password VARCHAR(128) NOT NULL, last_login TEXT NULL, "
                    + "is_superuser INTEGER NOT NULL DEFAULT 0, email VARCHAR(255) NOT NULL UNIQUE, "
                    + "name VARCHAR(150) NOT NULL, phone VARCHAR(25) NOT NULL DEFAULT '', role VARCHAR(20) NOT NULL DEFAULT 'USER', "
                    + "is_active INTEGER NOT NULL DEFAULT 1, is_staff INTEGER NOT NULL DEFAULT 0, "
                    + "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            statement.execute("INSERT INTO accounts_user(password,email,name,created_at,updated_at) "
                    + "VALUES('!','legacy@example.invalid','Legacy Customer','2025-01-01T00:00:00Z','2025-01-01T00:00:00Z')");
        }

        new DatabaseInitializer(legacyDatabase).initialize();
        AppUser legacyUser = new UserRepository(legacyDatabase).findByEmail("legacy@example.invalid").orElseThrow();

        assertEquals("Legacy Customer", legacyUser.name());
        assertNull(legacyUser.age());
    }

    @Test
    void staffLifecycleSkipRecallAndCustomerCancellationMatchBackendTransitions() throws Exception {
        AppUser customer = createGuest("Customer One");
        AppUser staff = createPrivilegedUser("staff@example.com", "Counter Staff", UserRole.STAFF);
        QueueToken waiting = queueService.join(customer, serviceId);

        QueueToken called = queueService.callNext(staff, serviceId, "Counter 4").orElseThrow();
        assertEquals(waiting.id(), called.id());
        assertEquals(QueueStatus.CALLED, called.status());
        assertEquals("Counter 4", called.counterNumber());

        QueueToken serving = queueService.startServing(staff, called.id());
        assertEquals(QueueStatus.SERVING, serving.status());
        assertThrows(IllegalStateException.class, () -> queueService.skip(staff, serving.id()));
        assertEquals(QueueStatus.COMPLETED, queueService.complete(staff, serving.id()).status());

        AppUser secondCustomer = createGuest("Customer Two");
        QueueToken second = queueService.join(secondCustomer, serviceId);
        QueueToken secondCalled = queueService.callNext(staff, serviceId, "Counter 4").orElseThrow();
        assertEquals(second.id(), secondCalled.id());
        QueueToken skipped = queueService.skip(staff, second.id());
        assertEquals(QueueStatus.SKIPPED, skipped.status());
        QueueToken recalled = queueService.recall(staff, second.id(), "Counter 5");
        assertEquals(QueueStatus.CALLED, recalled.status());
        assertNull(recalled.completedAt());
        assertEquals("Counter 5", recalled.counterNumber());

        AppUser thirdCustomer = createGuest("Customer Three");
        QueueToken cancellable = queueService.join(thirdCustomer, serviceId);
        QueueToken cancelled = queueService.cancel(thirdCustomer, cancellable.id());
        assertEquals(QueueStatus.CANCELLED, cancelled.status());
    }

    @Test
    void concurrentCallNextClaimsDistinctFifoTokens() throws Exception {
        AppUser firstCustomer = createGuest("Customer One");
        AppUser secondCustomer = createGuest("Customer Two");
        AppUser firstStaff = createPrivilegedUser("staff-one@example.com", "Staff One", UserRole.STAFF);
        AppUser secondStaff = createPrivilegedUser("staff-two@example.com", "Staff Two", UserRole.STAFF);
        QueueToken first = queueService.join(firstCustomer, serviceId);
        QueueToken second = queueService.join(secondCustomer, serviceId);

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<QueueToken>> claims = new ArrayList<>();
            claims.add(workers.submit(() -> queueService.callNext(firstStaff, serviceId, "Counter 1").orElseThrow()));
            claims.add(workers.submit(() -> queueService.callNext(secondStaff, serviceId, "Counter 2").orElseThrow()));
            List<Long> claimedIds = List.of(claims.get(0).get().id(), claims.get(1).get().id());

            assertTrue(claimedIds.contains(first.id()));
            assertTrue(claimedIds.contains(second.id()));
            assertFalse(claimedIds.get(0).equals(claimedIds.get(1)));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void developmentSeederIsExplicitAndDoesNotDuplicateOrOverwriteRecords() throws Exception {
        AppUser guest = queueService.joinGuest("Local Guest", 29, serviceId).guest();
        DemoDataSeeder seeder = new DemoDataSeeder(databaseManager);
        seeder.seed();
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "UPDATE accounts_user SET name='Locally Customized' WHERE id=?")) {
            statement.setLong(1, guest.id());
            statement.executeUpdate();
        }
        seeder.seed();

        try (Connection connection = databaseManager.openConnection();
             PreparedStatement users = connection.prepareStatement("SELECT COUNT(*) FROM accounts_user");
             ResultSet userRows = users.executeQuery();
             PreparedStatement services = connection.prepareStatement("SELECT COUNT(*) FROM services_service");
             ResultSet serviceRows = services.executeQuery()) {
            userRows.next();
            serviceRows.next();
            assertEquals(1, userRows.getInt(1));
            assertEquals(5, serviceRows.getInt(1));
        }
        assertEquals("Locally Customized", userRepository.findById(guest.id()).orElseThrow().name());
    }

    @Test
    void databaseInitializationPreservesRowsAndForeignKeysRejectOrphans() throws Exception {
        AppUser user = createGuest("Persist User");
        new DatabaseInitializer(databaseManager).initialize();
        assertEquals(user.id(), userRepository.findById(user.id()).orElseThrow().id());

        try (Connection connection = databaseManager.openConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO queues_queuetoken(token_number,user_id,service_id,status,joined_at) VALUES(?,?,?,'WAITING',?)")) {
            insert.setString(1, "A-999");
            insert.setLong(2, user.id());
            insert.setLong(3, 999_999);
            insert.setString(4, Instant.now().toString());
            assertThrows(SQLException.class, insert::executeUpdate);
        }
    }

    @Test
    void analyticsReportCompletedVolumeAndRejectInactiveService() throws Exception {
        AppUser customer = createGuest("Analytics Customer");
        AppUser admin = createPrivilegedUser("analytics-admin@example.com", "Queue Admin", UserRole.ADMIN);
        long inactiveServiceId = createService("Closed Desk", "X", 10, false);
        assertThrows(IllegalArgumentException.class, () -> queueService.join(customer, inactiveServiceId));

        QueueToken token = queueService.join(customer, serviceId);
        AppUser staff = createPrivilegedUser("analytics-staff@example.com", "Queue Staff", UserRole.STAFF);
        queueService.callNext(staff, serviceId, "Counter 1").orElseThrow();
        queueService.complete(staff, token.id());

        AnalyticsSummary summary = new AnalyticsService(databaseManager, new QueueRepository(databaseManager)).load(admin);
        assertTrue(summary.averageWaitMinutes().stream().anyMatch(metric -> metric.label().equals("General Consultation")));
        assertTrue(summary.dailyThroughput().stream().anyMatch(metric -> metric.value() > 0));
        assertTrue(summary.serviceVolume().stream().anyMatch(metric ->
                metric.label().equals("General Consultation") && metric.value() == 1));
    }

    private AppUser createGuest(String name) throws Exception {
        return databaseManager.inWriteTransaction(connection -> userRepository.createGuest(connection, name, 25));
    }

    private AppUser createPrivilegedUser(String email, String name, UserRole role) throws Exception {
        String now = Instant.now().toString();
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO accounts_user(password,email,name,role,is_active,is_staff,is_superuser,created_at,updated_at) "
                             + "VALUES(?,?,?,?,1,0,0,?,?)")) {
            statement.setString(1, "!");
            statement.setString(2, email);
            statement.setString(3, name);
            statement.setString(4, role.name());
            statement.setString(5, now);
            statement.setString(6, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                return userRepository.findById(keys.getLong(1)).orElseThrow();
            }
        }
    }

    private long createService(String name, String prefix, int averageMinutes, boolean active) throws Exception {
        String now = Instant.now().toString();
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO services_service(name,prefix,description,average_service_time,is_active,created_at,updated_at) "
                             + "VALUES(?,?,?, ?,?,?,?)", PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setString(2, prefix);
            statement.setString(3, "Service description");
            statement.setInt(4, averageMinutes);
            statement.setInt(5, active ? 1 : 0);
            statement.setString(6, now);
            statement.setString(7, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }
}