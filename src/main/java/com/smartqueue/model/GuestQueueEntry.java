package com.smartqueue.model;

public record GuestQueueEntry(AppUser guest, QueueToken token) {
}