package com.smartqueue.model;

public record AppUser(
        long id,
        String email,
        String name,
        Integer age,
        String phone,
        UserRole role,
        boolean active,
        String passwordHash
) {
}