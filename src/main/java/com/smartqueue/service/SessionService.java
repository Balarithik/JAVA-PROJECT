package com.smartqueue.service;

import com.smartqueue.database.DatabaseManager;
import com.smartqueue.model.AppUser;
import com.smartqueue.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

public final class SessionService {
    private static final Duration SESSION_LIFETIME = Duration.ofDays(7);
    private final DatabaseManager databaseManager;
    private final UserRepository userRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionService(DatabaseManager databaseManager, UserRepository userRepository) {
        this.databaseManager = databaseManager;
        this.userRepository = userRepository;
    }

    public String createSession(AppUser user) throws SQLException {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String tokenHash = hash(token);
        Instant now = Instant.now();
        databaseManager.inWriteTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO smartqueue_session(user_id,token_hash,created_at,expires_at) VALUES(?,?,?,?)")) {
                statement.setLong(1, user.id());
                statement.setString(2, tokenHash);
                statement.setString(3, now.toString());
                statement.setString(4, now.plus(SESSION_LIFETIME).toString());
                statement.executeUpdate();
            }
            return null;
        });
        return token;
    }

    public Optional<AppUser> restore(String token) throws SQLException {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT user_id FROM smartqueue_session WHERE token_hash=? AND revoked_at IS NULL AND expires_at>?")) {
            statement.setString(1, hash(token));
            statement.setString(2, Instant.now().toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return userRepository.findById(connection, result.getLong("user_id"))
                        .filter(AppUser::active);
            }
        }
    }

    public void revoke(String token) throws SQLException {
        if (token == null || token.isBlank()) {
            return;
        }
        databaseManager.inWriteTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE smartqueue_session SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL")) {
                statement.setString(1, Instant.now().toString());
                statement.setString(2, hash(token));
                statement.executeUpdate();
            }
            return null;
        });
    }

    private String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }
}