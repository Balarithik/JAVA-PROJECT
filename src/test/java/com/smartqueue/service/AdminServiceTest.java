package com.smartqueue.service;

import com.smartqueue.database.DatabaseInitializer;
import com.smartqueue.database.DatabaseManager;
import com.smartqueue.model.AppUser;
import com.smartqueue.model.UserRole;
import com.smartqueue.repository.QueueRepository;
import com.smartqueue.repository.ServiceRepository;
import com.smartqueue.repository.UserRepository;
import com.smartqueue.security.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AdminServiceTest {
    @TempDir
    Path temporaryDirectory;

    private AdminService adminService;
    private UserRepository userRepository;
    private AppUser administrator;
    private AppUser staff;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(temporaryDirectory.resolve("admin-test.db"));
        new DatabaseInitializer(databaseManager).initialize();
        userRepository = new UserRepository(databaseManager);
        ServiceRepository serviceRepository = new ServiceRepository(databaseManager);
        QueueRepository queueRepository = new QueueRepository(databaseManager);
        AuthService authService = new AuthService(userRepository);
        adminService = new AdminService(authService, serviceRepository,
                new AnalyticsService(databaseManager, queueRepository), userRepository, queueRepository);
        administrator = userRepository.createAccount("Admin", "admin@example.com",
                PasswordHasher.hash("admin-password-123".toCharArray()), UserRole.ADMIN);
        staff = userRepository.createAccount("Staff", "staff@example.com",
                PasswordHasher.hash("staff-password-123".toCharArray()), UserRole.STAFF);
    }

    @Test
    void administratorCanCreateStaffAndService() throws Exception {
        AppUser createdStaff = adminService.createStaff(administrator, "New Staff", "new.staff@example.com",
                "staff-password-456".toCharArray());
        var service = adminService.createService(administrator, "Student Affairs", "S",
                "Student support services", 8);

        assertEquals(UserRole.STAFF, createdStaff.role());
        assertTrue(createdStaff.active());
        assertEquals("Student Affairs", service.name());
        assertEquals("S", service.prefix());
    }

    @Test
    void staffCannotAccessAdministratorManagement() {
        assertThrows(SecurityException.class, () -> adminService.createService(staff,
                "Restricted Service", "R", "Not allowed", 5));
        assertThrows(SecurityException.class, () -> adminService.createStaff(staff,
                "Another Staff", "another@example.com", "password-123".toCharArray()));
    }

    @Test
    void staffWithAssignedActiveTokenCannotBeDeactivated() throws Exception {
        QueueRepository queueRepository = new QueueRepository(new DatabaseManager(temporaryDirectory.resolve("admin-test.db")));
        QueueService queueService = new QueueService(new DatabaseManager(temporaryDirectory.resolve("admin-test.db")),
                queueRepository, new ServiceRepository(new DatabaseManager(temporaryDirectory.resolve("admin-test.db"))),
                userRepository);
        var service = new ServiceRepository(new DatabaseManager(temporaryDirectory.resolve("admin-test.db")))
                .create("General Help", "G", "General help desk", 5);
        AppUser customer = userRepository.createAccount("Customer", "customer@example.com",
                PasswordHasher.hash("customer-password-12".toCharArray()), UserRole.USER);
        queueService.join(customer, service.id());
        queueService.callNext(staff, service.id(), "Counter 1");

        assertThrows(IllegalStateException.class, () -> adminService.setStaffActive(administrator, staff.id(), false));
    }

    @Test
    void serviceConfigurationIsValidatedBeforeWriting() {
        assertThrows(IllegalArgumentException.class, () -> adminService.createService(administrator,
                "Bad Prefix", "123", "Invalid prefix", 5));
        assertThrows(IllegalArgumentException.class, () -> adminService.createService(administrator,
                "Bad Duration", "B", "Invalid duration", 0));
    }
}
