package dev.kat.lotcjournal.domain;
import java.util.Objects;
import java.util.UUID;
/** Name is display metadata; durable references use identityId. */
public record SceneParticipant(String identityId, String name, EncounterRole role, String perspectivePersonaId) {
    public SceneParticipant {
        requireUuid(identityId); Objects.requireNonNull(name); Objects.requireNonNull(role);
        if(perspectivePersonaId!=null) requireUuid(perspectivePersonaId);
        if(role!=EncounterRole.UNCERTAIN && perspectivePersonaId==null)
            throw new IllegalArgumentException("Direct/overheard requires an explicit perspective persona");
    }
    public static void requireUuid(String id) {
        if(id==null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid UUID");
    }
}
