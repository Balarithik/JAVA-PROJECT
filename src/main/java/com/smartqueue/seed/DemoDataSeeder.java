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
            for (SeedService service : defaultServices()) {
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

    public static List<SeedService> defaultServices() {
        return List.of(
                new SeedService("Hospital Registration", "H", 5,
                        "Patient check-in, registration, and appointment confirmation services."),
                new SeedService("Bank Teller", "B", 4,
                        "Deposits, withdrawals, account inquiries, and teller assistance."),
                new SeedService("Insurance Desk", "I", 6,
                        "Claims processing, policy updates, and insurance support services."),
                new SeedService("General Consultation", "A", 5,
                        "General walk-in consultation, advice, and inquiry services."),
                new SeedService("Customer Service", "C", 6,
                        "General customer care, grievance resolution, and account upgrades.")
        );
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