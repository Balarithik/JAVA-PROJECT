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
import java.util.Locale;
import java.util.Optional;

public final class UserRepository {
    private final DatabaseManager databaseManager;

    public UserRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public Optional<AppUser> findByEmail(String email) throws SQLException {
        try (Connection connection = databaseManager.openConnection()) {
            return findByEmail(connection, email);
        }
    }

    public Optional<AppUser> findByEmail(Connection connection, String email) throws SQLException {
        String sql = "SELECT id, email, name, phone, role, is_active, password FROM accounts_user WHERE lower(email) = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email.trim().toLowerCase(Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    public Optional<AppUser> findById(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,email,name,phone,role,is_active,password FROM accounts_user WHERE id=?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    public AppUser create(Connection connection, String email, String name, String phone, String hash)
            throws SQLException {
        String now = Instant.now().toString();
        String sql = "INSERT INTO accounts_user(password,email,name,phone,role,is_active,is_staff,is_superuser,created_at,updated_at) "
                + "VALUES(?,?,?,?, 'USER',1,0,0,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, hash);
            statement.setString(2, email.trim().toLowerCase(Locale.ROOT));
            statement.setString(3, name.trim());
            statement.setString(4, phone == null ? "" : phone.trim());
            statement.setString(5, now);
            statement.setString(6, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The database did not return the new user id.");
                }
                return new AppUser(keys.getLong(1), email.trim().toLowerCase(Locale.ROOT), name.trim(),
                        phone == null ? "" : phone.trim(), UserRole.USER, true, hash);
            }
        }
    }

    public AppUser updateProfile(long userId, String name, String phone) throws SQLException {
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE accounts_user SET name=?, phone=?, updated_at=? WHERE id=? AND is_active=1")) {
            statement.setString(1, name.trim());
            statement.setString(2, phone == null ? "" : phone.trim());
            statement.setString(3, Instant.now().toString());
            statement.setLong(4, userId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("The user profile could not be updated.");
            }
        }
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id,email,name,phone,role,is_active,password FROM accounts_user WHERE id=?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("The user profile could not be loaded.");
                }
                return map(result);
            }
        }
    }

    private AppUser map(ResultSet result) throws SQLException {
        return new AppUser(result.getLong("id"), result.getString("email"), result.getString("name"),
                result.getString("phone"), UserRole.valueOf(result.getString("role")),
                result.getInt("is_active") == 1, result.getString("password"));
    }
}