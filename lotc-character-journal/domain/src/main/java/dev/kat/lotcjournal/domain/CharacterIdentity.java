package dev.kat.lotcjournal.domain;

import java.util.Set;
import java.util.UUID;

/** User-maintained archive identity; account text is an observation, not verified ownership. */
public record CharacterIdentity(String id, String name, String notes, Set<String> aliases, String accountObservation) {
    public CharacterIdentity {
        if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid identity");
        if (name == null || name.isBlank() || name.length() > 96 || notes == null || notes.length() > 16384
            || accountObservation == null || accountObservation.length() > 128) throw new IllegalArgumentException("Invalid identity fields");
        aliases = Set.copyOf(aliases);
        if (aliases.size() > 200 || aliases.stream().anyMatch(a -> a.isBlank() || a.length() > 96))
            throw new IllegalArgumentException("Invalid aliases");
    }
}
