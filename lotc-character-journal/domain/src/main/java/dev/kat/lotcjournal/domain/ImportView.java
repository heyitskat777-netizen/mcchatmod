package dev.kat.lotcjournal.domain;

/** Immutable worker progress for rendering. No database or file work is needed to read it. */
public record ImportView(State state, String message, ImportProgress progress) {
    public enum State { IDLE, VALIDATING, IMPORTING, COMPLETE, CANCELLED, FAILED, CLOSED }
}
