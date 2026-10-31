package com.smartqueue.model;

public record ServiceQueueSnapshot(
        ServiceItem service,
        int waitingCount,
        String currentlyServingToken,
        String currentCounter,
        int estimatedTotalQueueTime
) {
}