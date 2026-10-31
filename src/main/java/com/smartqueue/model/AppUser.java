package com.smartqueue.model;

public record AppUser(
        long id,
        String email,
        String name,
        String phone,
        UserRole role,
        boolean active,
        String passwordHash
) {
}