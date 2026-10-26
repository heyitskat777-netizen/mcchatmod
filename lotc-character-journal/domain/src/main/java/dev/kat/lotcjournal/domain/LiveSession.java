package dev.kat.lotcjournal.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** Stable live namespace; its ID is deliberately not a historical file-content digest. */
public record LiveSession(String id, Instant startedAt, String zoneId, String policyFingerprint) {
    public LiveSession {
        ImportSource.requireId(id);
        ImportSource.requireId(policyFingerprint);
        Objects.requireNonNull(startedAt);
        ZoneId.of(Objects.requireNonNull(zoneId));
    }
}
