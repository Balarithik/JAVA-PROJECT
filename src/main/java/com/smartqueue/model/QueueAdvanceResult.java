package com.smartqueue.model;

import java.util.Optional;

public record QueueAdvanceResult(Optional<QueueToken> completedToken, Optional<QueueToken> nextToken) {
}
