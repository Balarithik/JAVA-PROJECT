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

    public Optional<AppUser> findByEmail(String email) throws SQLException {
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id,name,age,role,is_active FROM accounts_user WHERE email=? COLLATE NOCASE")) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    public Optional<UserCredential> findCredentialByEmail(String email) throws SQLException {
        String sql = "SELECT id,name,age,role,is_active,password FROM accounts_user WHERE email=? COLLATE NOCASE";
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UserCredential(map(result), result.getString("password")));
            }
        }
    }

    public java.util.List<AppUser> listByRole(UserRole role) throws SQLException {
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id,name,age,role,is_active FROM accounts_user WHERE role=? ORDER BY name")) {
            statement.setString(1, role.name());
            try (ResultSet result = statement.executeQuery()) {
                java.util.List<AppUser> users = new java.util.ArrayList<>();
                while (result.next()) {
                    users.add(map(result));
                }
                return users;
            }
        }
    }

    public void setActive(long userId, boolean active) throws SQLException {
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE accounts_user SET is_active=?,updated_at=? WHERE id=?")) {
            statement.setInt(1, active ? 1 : 0);
            statement.setString(2, Instant.now().toString());
            statement.setLong(3, userId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("User account was not found.");
            }
        }
    }

    public Optional<AppUser> findFirstActiveAdmin() throws SQLException {
        String sql = "SELECT id,name,age,role,is_active FROM accounts_user "
                + "WHERE role='ADMIN' AND is_active=1 ORDER BY id LIMIT 1";
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? Optional.of(map(result)) : Optional.empty();
        }
    }

    public AppUser createAccount(String name, String email, String passwordHash, UserRole role) throws SQLException {
        String now = Instant.now().toString();
        boolean privileged = role == UserRole.STAFF || role == UserRole.ADMIN;
        String sql = "INSERT INTO accounts_user(password,email,name,age,phone,role,is_active,is_staff,is_superuser,created_at,updated_at) "
                + "VALUES(?,?,?,NULL,'',?,1,?,?,?,?)";
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, passwordHash);
            statement.setString(2, email);
            statement.setString(3, name);
            statement.setString(4, role.name());
            statement.setInt(5, privileged ? 1 : 0);
            statement.setInt(6, role == UserRole.ADMIN ? 1 : 0);
            statement.setString(7, now);
            statement.setString(8, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The database did not return the new account id.");
                }
                return findById(connection, keys.getLong(1))
                        .orElseThrow(() -> new SQLException("The newly created account could not be loaded."));
            }
        }
    }

    public void recordLogin(long userId) throws SQLException {
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE accounts_user SET last_login=?,updated_at=? WHERE id=? AND is_active=1")) {
            String now = Instant.now().toString();
            statement.setString(1, now);
            statement.setString(2, now);
            statement.setLong(3, userId);
            statement.executeUpdate();
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

    public record UserCredential(AppUser user, String passwordHash) {
    }
}
