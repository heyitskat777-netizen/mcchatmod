package dev.kat.lotcjournal.domain;

public enum Channel {
    ROLEPLAY, OOC, PRIVATE_MESSAGE, SYSTEM, UNKNOWN;
    public boolean eligibleForAnalysis() { return this == ROLEPLAY; }
}

