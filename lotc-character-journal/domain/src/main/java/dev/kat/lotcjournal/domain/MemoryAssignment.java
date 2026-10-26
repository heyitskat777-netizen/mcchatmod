package dev.kat.lotcjournal.domain;

import java.util.Objects;
import java.util.UUID;
public record MemoryAssignment(MessageReference message, String personaId, String identityId, AttributionRole role) {
    public MemoryAssignment {
        Objects.requireNonNull(message); Objects.requireNonNull(role);
        if (personaId == null || !UUID.fromString(personaId).toString().equals(personaId)
            || identityId != null && !UUID.fromString(identityId).toString().equals(identityId))
            throw new IllegalArgumentException("Invalid memory assignment");
    }
}
