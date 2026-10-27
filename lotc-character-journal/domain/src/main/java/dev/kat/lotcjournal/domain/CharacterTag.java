package dev.kat.lotcjournal.domain;

import java.util.Objects;
import java.util.UUID;

/** A shared-archive identity interpretation, never a grant of persona knowledge. */
public record CharacterTag(String identityId, CharacterEvidenceKind kind) {
    public CharacterTag {
        if (identityId == null || !UUID.fromString(identityId).toString().equals(identityId))
            throw new IllegalArgumentException("Invalid tagged identity");
        Objects.requireNonNull(kind);
    }
}
