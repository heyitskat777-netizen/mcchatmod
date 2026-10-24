package dev.kat.lotcjournal.application;

import dev.kat.lotcjournal.domain.JournalSettings;

public record JournalSnapshot(Status status, JournalSettings settings, String message) {
    public enum Status { STARTING, READY, FAILED, CLOSED }
}

