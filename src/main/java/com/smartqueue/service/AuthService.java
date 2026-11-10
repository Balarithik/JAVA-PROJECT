package com.smartqueue.service;

import com.smartqueue.model.AppUser;
import com.smartqueue.model.UserRole;
import com.smartqueue.repository.UserRepository;
import com.smartqueue.security.PasswordHasher;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Arrays;
import java.util.Optional;

/** Application authentication and account-registration use cases. */
public final class AuthService {
    private final UserRepository userRepository;

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AppUser registerCustomer(String name, String email, char[] password) throws SQLException {
        String cleanedName = name == null ? "" : name.trim();
        String cleanedEmail = normalizeEmail(email);
        validateCredentials(cleanedName, cleanedEmail, password);
        if (cleanedName.length() < 2 || cleanedName.length() > 150) {
            throw new IllegalArgumentException("Name must contain between 2 and 150 characters.");
        }
        if (password.length < 8 || password.length > 128) {
            throw new IllegalArgumentException("Password must contain between 8 and 128 characters.");
        }
        try {
            return userRepository.createAccount(cleanedName, cleanedEmail, PasswordHasher.hash(password), UserRole.USER);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public Optional<AppUser> authenticate(String email, char[] password) throws SQLException {
        String cleanedEmail = normalizeEmail(email);
        if (cleanedEmail.isBlank() || password == null || password.length == 0) {
            return Optional.empty();
        }
        try {
            Optional<UserRepository.UserCredential> credential = userRepository.findCredentialByEmail(cleanedEmail);
            if (credential.isEmpty() || !credential.get().user().active()
                    || !PasswordHasher.verify(password, credential.get().passwordHash())) {
                return Optional.empty();
            }
            userRepository.recordLogin(credential.get().user().id());
            return Optional.of(credential.get().user());
        } finally {
            Arrays.fill(password, '\0');
        }
    }


    public AppUser createStaff(AppUser administrator, String name, String email, char[] password)
            throws SQLException {
        requireAdministrator(administrator);
        String cleanedName = name == null ? "" : name.trim();
        String cleanedEmail = normalizeEmail(email);
        validateCredentials(cleanedName, cleanedEmail, password);
        if (cleanedName.length() < 2 || cleanedName.length() > 150) {
            throw new IllegalArgumentException("Name must contain between 2 and 150 characters.");
        }
        if (password.length < 8 || password.length > 128) {
            throw new IllegalArgumentException("Password must contain between 8 and 128 characters.");
        }
        try {
            return userRepository.createAccount(cleanedName, cleanedEmail, PasswordHasher.hash(password), UserRole.STAFF);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void requireAdministrator(AppUser user) {
        if (user == null || !user.active() || user.role() != UserRole.ADMIN) {
            throw new SecurityException("Administrator access is required to manage staff accounts.");
        }
    }

    /** Creates the first administrator only when credentials are supplied through environment variables. */
    public void bootstrapAdministratorFromEnvironment() throws SQLException {
        String email = System.getenv("SMARTQUEUE_ADMIN_EMAIL");
        String password = System.getenv("SMARTQUEUE_ADMIN_PASSWORD");
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        String normalized = normalizeEmail(email);
        if (userRepository.findByEmail(normalized).isPresent()) {
            return;
        }
        if (password.length() < 12) {
            throw new IllegalArgumentException("SMARTQUEUE_ADMIN_PASSWORD must contain at least 12 characters.");
        }
        char[] secret = password.toCharArray();
        try {
            userRepository.createAccount("System Administrator", normalized,
                    PasswordHasher.hash(secret), UserRole.ADMIN);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private void validateCredentials(String name, String email, char[] password) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("Name is required.");
        }
        if (email.isBlank() || email.length() > 255 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        if (password == null) {
            throw new IllegalArgumentException("Password is required.");
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
