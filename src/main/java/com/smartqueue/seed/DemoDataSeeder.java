package com.smartqueue.seed;

import com.smartqueue.database.DatabaseInitializer;
import com.smartqueue.database.DatabaseManager;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;

public final class DemoDataSeeder {
    private final DatabaseManager databaseManager;

    public DemoDataSeeder(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void seed() throws Exception {
        databaseManager.inWriteTransaction(connection -> {
            List<SeedService> services = List.of(
                    new SeedService("General Consultation", "A", 5, "General walk-in consultation, advice, and inquiry services."),
                    new SeedService("Billing & Payments", "B", 4, "Account settlement, fee processing, invoicing, and payment queries."),
                    new SeedService("Customer Service", "C", 6, "General customer care, grievance resolution, and account upgrades."),
                    new SeedService("Document Verification", "D", 8, "Identity verification, document clearance, and certificate validation."),
                    new SeedService("Technical Support", "T", 10, "Hardware diagnostics, technical debugging, and system configuration.")
            );
            for (SeedService service : services) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT OR IGNORE INTO services_service(name,prefix,description,average_service_time,is_active,created_at,updated_at) "
                                + "VALUES(?,?,?,?,1,?,?)")) {
                    String now = Instant.now().toString();
                    statement.setString(1, service.name());
                    statement.setString(2, service.prefix());
                    statement.setString(3, service.description());
                    statement.setInt(4, service.averageMinutes());
                    statement.setString(5, now);
                    statement.setString(6, now);
                    statement.executeUpdate();
                }
            }
            return null;
        });
    }

    public static void main(String[] args) throws Exception {
        DatabaseManager databaseManager = new DatabaseManager();
        new DatabaseInitializer(databaseManager).initialize();
        new DemoDataSeeder(databaseManager).seed();
        System.out.println("Development services are ready. No user accounts were created.");
    }

    private record SeedService(String name, String prefix, int averageMinutes, String description) {
    }
}