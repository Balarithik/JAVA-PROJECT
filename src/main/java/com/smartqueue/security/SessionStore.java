package com.smartqueue.security;

import com.smartqueue.config.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

public final class SessionStore {
    private final Path sessionPath;

    public SessionStore() {
        this(AppConfig.sessionPath());
    }

    public SessionStore(Path sessionPath) {
        this.sessionPath = sessionPath.toAbsolutePath().normalize();
    }

    public String readToken() throws IOException {
        if (!Files.exists(sessionPath)) {
            return null;
        }
        String token = Files.readString(sessionPath).trim();
        return token.isEmpty() ? null : token;
    }

    public void saveToken(String token) throws IOException {
        Files.createDirectories(sessionPath.getParent());
        Files.writeString(sessionPath, token);
        try {
            Files.setPosixFilePermissions(sessionPath, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
        }
    }

    public void clear() throws IOException {
        Files.deleteIfExists(sessionPath);
    }
}