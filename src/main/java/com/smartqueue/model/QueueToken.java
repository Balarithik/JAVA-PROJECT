package com.smartqueue.model;

import java.time.Instant;

public record QueueToken(
        long id,
        String tokenNumber,
        long userId,
        String userName,
        String userEmail,
        ServiceItem service,
        QueueStatus status,
        Instant joinedAt,
        Instant calledAt,
        Instant startedAt,
        Instant completedAt,
        String counterNumber,
        Long servedById,
        String servedByName,
        int peopleAhead,
        int estimatedWaitTime
) {
}