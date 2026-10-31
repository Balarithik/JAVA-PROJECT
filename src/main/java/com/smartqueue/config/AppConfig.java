package com.smartqueue.config;

import java.nio.file.Path;

public final class AppConfig {
    private static final String APP_DIRECTORY = "SmartQueue";

    private AppConfig() {
    }

    public static Path dataDirectory() {
        String override = System.getenv("SMARTQUEUE_DATA_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }

        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, APP_DIRECTORY);
        }
        return Path.of(System.getProperty("user.home"), ".smartqueue");
    }

    public static Path databasePath() {
        return dataDirectory().resolve("smartqueue.db");
    }

    public static Path sessionPath() {
        return dataDirectory().resolve("session.token");
    }
}