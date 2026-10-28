package dev.kat.lotcjournal.domain;
/** Counts are observations, not reconstructed exact emotes; time values are null if unproved. */
public record SceneStatistics(long messages, long speechObservations, long actionObservations, long mentionObservations,
                              Long elapsedSeconds, Long estimatedActiveSeconds) {
    public SceneStatistics {
        if(messages<1 || speechObservations<0 || actionObservations<0 || mentionObservations<0 || speechObservations>messages || actionObservations>messages || mentionObservations>messages
            || elapsedSeconds!=null && elapsedSeconds<0 || estimatedActiveSeconds!=null && estimatedActiveSeconds<0)
            throw new IllegalArgumentException("Invalid scene statistics");
    }
}
