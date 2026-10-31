package com.smartqueue.repository;

import com.smartqueue.database.DatabaseManager;
import com.smartqueue.model.AppUser;
import com.smartqueue.model.UserRole;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class UserRepository {
    private final DatabaseManager databaseManager;

    public UserRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public Optional<AppUser> findById(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,name,age,role,is_active FROM accounts_user WHERE id=?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    public Optional<AppUser> findById(long userId) throws SQLException {
        try (Connection connection = databaseManager.openConnection()) {
            return findById(connection, userId);
        }
    }

    public AppUser createGuest(Connection connection, String name, int age) throws SQLException {
        String legacyEmail = "guest-" + UUID.randomUUID() + "@guest.invalid";
        String now = Instant.now().toString();
        String sql = "INSERT INTO accounts_user(password,email,name,age,phone,role,is_active,is_staff,is_superuser,created_at,updated_at) "
            + "VALUES('!',?,?,?,'','USER',1,0,0,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, legacyEmail);
            statement.setString(2, name.trim());
            statement.setInt(3, age);
            statement.setString(4, now);
            statement.setString(5, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The database did not return the guest id.");
                }
                return new AppUser(keys.getLong(1), name.trim(), age, UserRole.USER, true);
            }
        }
    }

    private AppUser map(ResultSet result) throws SQLException {
        int ageValue = result.getInt("age");
        Integer age = result.wasNull() ? null : ageValue;
        return new AppUser(result.getLong("id"), result.getString("name"), age,
                UserRole.valueOf(result.getString("role")), result.getInt("is_active") == 1);
    }
}