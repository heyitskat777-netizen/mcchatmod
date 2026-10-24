package dev.kat.lotcjournal.domain;

public record JournalSettings(boolean compactLayout) {
    public static JournalSettings defaults() { return new JournalSettings(false); }
}

