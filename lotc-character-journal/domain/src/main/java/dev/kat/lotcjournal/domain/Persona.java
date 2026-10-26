package dev.kat.lotcjournal.domain;

import java.util.UUID;
public record Persona(String id, String identityId, String label) {
    public Persona {
        if (id == null || !UUID.fromString(id).toString().equals(id) || identityId == null
            || !UUID.fromString(identityId).toString().equals(identityId) || label == null || label.isBlank() || label.length() > 96)
            throw new IllegalArgumentException("Invalid persona");
    }
}
