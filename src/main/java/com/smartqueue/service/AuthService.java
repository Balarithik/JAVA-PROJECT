package com.smartqueue.service;

import com.smartqueue.database.DatabaseManager;
import com.smartqueue.model.AppUser;
import com.smartqueue.repository.UserRepository;
import com.smartqueue.security.PasswordHasher;

import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

public final class AuthService {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final DatabaseManager databaseManager;
    private final UserRepository userRepository;

    public AuthService(DatabaseManager databaseManager, UserRepository userRepository) {
        this.databaseManager = databaseManager;
        this.userRepository = userRepository;
    }

    public AppUser register(String name, String email, String phone, String password) throws SQLException {
        String cleanedName = name == null ? "" : name.trim();
        String cleanedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (cleanedName.length() < 2) {
            throw new IllegalArgumentException("Name must be at least 2 characters long.");
        }
        if (!EMAIL_PATTERN.matcher(cleanedEmail).matches()) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters long.");
        }

        return databaseManager.inWriteTransaction(connection -> {
            if (userRepository.findByEmail(connection, cleanedEmail).isPresent()) {
                throw new IllegalArgumentException("An account with this email already exists.");
            }
            return userRepository.create(connection, cleanedEmail, cleanedName, phone, PasswordHasher.hash(password));
        });
    }

    public AppUser login(String email, String password) throws SQLException {
        if (email == null || email.isBlank() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Both email and password are required.");
        }
        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("No account found with this email."));
        if (!user.active()) {
            throw new IllegalArgumentException("This user account has been disabled.");
        }
        if (!PasswordHasher.verify(password, user.passwordHash())) {
            throw new IllegalArgumentException("Incorrect password. Please try again.");
        }
        return user;
    }

    public AppUser updateProfile(long userId, String name, String phone) throws SQLException {
        if (name == null || name.trim().length() < 2) {
            throw new IllegalArgumentException("Name must be at least 2 characters long.");
        }
        return userRepository.updateProfile(userId, name, phone);
    }
}