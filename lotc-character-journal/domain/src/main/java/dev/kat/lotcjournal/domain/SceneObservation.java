package dev.kat.lotcjournal.domain;
import java.util.Objects;
/** timelineSeconds is an exact live instant or a source-local clock; null stays unknown.
 * boundaryKey separates explicit persona ownership/session context. It is never a guessed location. */
public record SceneObservation(TranscriptLine message, Long timelineSeconds, String boundaryKey) {
    public SceneObservation { Objects.requireNonNull(message); Objects.requireNonNull(boundaryKey); if(message.channel()!=Channel.ROLEPLAY) throw new IllegalArgumentException("Scenes require trusted RP"); }
}
