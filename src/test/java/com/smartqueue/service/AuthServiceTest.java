package com.smartqueue.service;

import com.smartqueue.database.DatabaseInitializer;
import com.smartqueue.database.DatabaseManager;
import com.smartqueue.model.AppUser;
import com.smartqueue.model.UserRole;
import com.smartqueue.repository.UserRepository;
import com.smartqueue.security.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {
    @TempDir
    Path temporaryDirectory;

    private AuthService authService;
    private UserRepository userRepository;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(temporaryDirectory.resolve("smartqueue-test.db"));
        new DatabaseInitializer(databaseManager).initialize();
        userRepository = new UserRepository(databaseManager);
        authService = new AuthService(userRepository);
    }

    @Test
    void customerCanRegisterAndAuthenticateWithNormalizedEmail() throws Exception {
        AppUser registered = authService.registerCustomer("Test Customer", "  CUSTOMER@EXAMPLE.COM ",
                "correct-horse-12".toCharArray());

        assertEquals(UserRole.USER, registered.role());
        assertTrue(registered.active());
        assertEquals(Optional.of(registered), authService.authenticate("customer@example.com",
                "correct-horse-12".toCharArray()));
    }

    @Test
    void invalidPasswordDoesNotAuthenticate() throws Exception {
        authService.registerCustomer("Test Customer", "customer@example.com", "correct-horse-12".toCharArray());

        assertTrue(authService.authenticate("customer@example.com", "incorrect-password".toCharArray()).isEmpty());
        assertTrue(authService.authenticate("unknown@example.com", "correct-horse-12".toCharArray()).isEmpty());
    }

    @Test
    void duplicateEmailAndWeakPasswordAreRejected() throws Exception {
        authService.registerCustomer("Test Customer", "customer@example.com", "correct-horse-12".toCharArray());

        assertThrows(SQLException.class, () -> authService.registerCustomer("Second Customer",
                "CUSTOMER@example.com", "another-password".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> authService.registerCustomer("Second Customer",
                "second@example.com", "short".toCharArray()));
    }

    @Test
    void passwordHashesUseDifferentSaltsAndVerifySafely() {
        char[] password = "correct-horse-12".toCharArray();
        String first = PasswordHasher.hash(password);
        String second = PasswordHasher.hash(password);

        assertNotEquals(first, second);
        assertTrue(PasswordHasher.verify(password, first));
        assertFalse(PasswordHasher.verify("wrong-password".toCharArray(), first));
        assertFalse(PasswordHasher.verify(password, "not-a-password-hash"));
    }
}
