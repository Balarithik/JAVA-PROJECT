package com.smartqueue.seed;

import com.smartqueue.database.DatabaseInitializer;
import com.smartqueue.database.DatabaseManager;
import com.smartqueue.model.UserRole;
import com.smartqueue.repository.UserRepository;
import com.smartqueue.security.PasswordHasher;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

public final class DemoDataSeeder {
    private final DatabaseManager databaseManager;
    private final UserRepository userRepository;

    public DemoDataSeeder(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.userRepository = new UserRepository(databaseManager);
    }

    public void seed() throws Exception {
        List<SeedAccount> accounts = List.of(
                new SeedAccount("user@smartqueue.com", "Alex Customer", "+1 555 0192", UserRole.USER, false, false),
                new SeedAccount("staff@smartqueue.com", "Sarah Counter-Staff", "+1 555 0148", UserRole.STAFF, false, false),
                new SeedAccount("admin@smartqueue.com", "David System-Admin", "+1 555 0100", UserRole.ADMIN, true, true)
        ).stream().map(account -> account.withHash(PasswordHasher.hash("Password123!"))).toList();

        databaseManager.inWriteTransaction(connection -> {
            List<SeedService> services = List.of(
                    new SeedService("General Consultation", "A", 5, "General walk-in consultation, advice, and inquiry services."),
                    new SeedService("Billing & Payments", "B", 4, "Account settlement, fee processing, invoicing, and payment queries."),
                    new SeedService("Customer Service", "C", 6, "General customer care, grievance resolution, and account upgrades."),
                    new SeedService("Document Verification", "D", 8, "Identity verification, document clearance, and certificate validation."),
                    new SeedService("Technical Support", "T", 10, "Hardware diagnostics, technical debugging, and system configuration.")
            );
            for (SeedService service : services) {
                try (PreparedStatement exists = connection.prepareStatement(
                        "SELECT 1 FROM services_service WHERE name=?")) {
                    exists.setString(1, service.name());
                    try (ResultSet result = exists.executeQuery()) {
                        if (result.next()) {
                            continue;
                        }
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO services_service(name,prefix,description,average_service_time,is_active,created_at,updated_at) "
                                + "VALUES(?,?,?,?,1,?,?)")) {
                    String now = Instant.now().toString();
                    insert.setString(1, service.name());
                    insert.setString(2, service.prefix());
                    insert.setString(3, service.description());
                    insert.setInt(4, service.averageMinutes());
                    insert.setString(5, now);
                    insert.setString(6, now);
                    insert.executeUpdate();
                }
            }

            for (SeedAccount account : accounts) {
                if (userRepository.findByEmail(connection, account.email()).isPresent()) {
                    continue;
                }
                String now = Instant.now().toString();
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO accounts_user(password,email,name,phone,role,is_active,is_staff,is_superuser,created_at,updated_at) "
                                + "VALUES(?,?,?,?,?,1,?,?,?,?)")) {
                    insert.setString(1, account.passwordHash());
                    insert.setString(2, account.email());
                    insert.setString(3, account.name());
                    insert.setString(4, account.phone());
                    insert.setString(5, account.role().name());
                    insert.setInt(6, account.staffFlag() ? 1 : 0);
                    insert.setInt(7, account.superuserFlag() ? 1 : 0);
                    insert.setString(8, now);
                    insert.setString(9, now);
                    insert.executeUpdate();
                }
            }
            return null;
        });
    }

    public static void main(String[] args) throws Exception {
        DatabaseManager databaseManager = new DatabaseManager();
        new DatabaseInitializer(databaseManager).initialize();
        new DemoDataSeeder(databaseManager).seed();
        System.out.println("Development seed data is ready. Existing users and services were left unchanged.");
    }

    private record SeedService(String name, String prefix, int averageMinutes, String description) {
    }

    private record SeedAccount(String email, String name, String phone, UserRole role,
                               boolean staffFlag, boolean superuserFlag, String passwordHash) {
        private SeedAccount(String email, String name, String phone, UserRole role,
                            boolean staffFlag, boolean superuserFlag) {
            this(email, name, phone, role, staffFlag, superuserFlag, "");
        }

        private SeedAccount withHash(String hash) {
            return new SeedAccount(email, name, phone, role, staffFlag, superuserFlag, hash);
        }
    }
}