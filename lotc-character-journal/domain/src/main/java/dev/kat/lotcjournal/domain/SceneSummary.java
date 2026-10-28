package dev.kat.lotcjournal.domain;
import java.util.Objects;
public record SceneSummary(String id, String title, String location, SceneState state, long messageCount, long revision) {
    public SceneSummary {
        SceneParticipant.requireUuid(id); Objects.requireNonNull(title); Objects.requireNonNull(location); Objects.requireNonNull(state);
        if(title.isBlank() || title.length()>160 || location.length()>240 || messageCount<1 || revision<1) throw new IllegalArgumentException("Invalid scene summary");
    }
}
