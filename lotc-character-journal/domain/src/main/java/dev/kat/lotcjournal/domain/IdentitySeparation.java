package dev.kat.lotcjournal.domain;

import java.util.UUID;

/** An explicit reversible decision that two observed identities must remain separate. */
public record IdentitySeparation(String id, String firstIdentity, String secondIdentity, boolean active) {
    public IdentitySeparation {
        for (String value : new String[] {id, firstIdentity, secondIdentity})
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Invalid separation reference");
        if (firstIdentity.equals(secondIdentity)) throw new IllegalArgumentException("An identity cannot be separate from itself");
    }
}
