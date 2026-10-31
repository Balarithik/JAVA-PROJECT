package com.smartqueue.model;

public record AppUser(
        long id,
        String name,
        Integer age,
        UserRole role,
        boolean active
) {
}