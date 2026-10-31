package com.smartqueue.model;

public record ServiceItem(
        long id,
        String name,
        String prefix,
        String description,
        int averageServiceTime,
        boolean active,
        int activeTokensCount
) {
    @Override
    public String toString() {
        return prefix + " - " + name;
    }
}