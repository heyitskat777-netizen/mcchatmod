package dev.kat.lotcjournal.domain;

/** A temporary store reservation conflict. Retry the same idempotent operation, not its caller's file writes. */
public final class StorageBusyException extends Exception {
    public StorageBusyException() { super("Storage is temporarily busy"); }
    public StorageBusyException(Throwable cause) { super("Storage is temporarily busy", cause); }
}
