package dev.kat.lotcjournal.domain;
import java.util.Objects;
public record SceneSummary(String id, String title, String location, SceneState state, long messageCount, long revision, SceneOrigin origin) {
    public SceneSummary(String id,String title,String location,SceneState state,long messageCount,long revision){this(id,title,location,state,messageCount,revision,SceneOrigin.MANUAL);}
    public SceneSummary {
        SceneParticipant.requireUuid(id); Objects.requireNonNull(title); Objects.requireNonNull(location); Objects.requireNonNull(state); Objects.requireNonNull(origin);
        if(title.isBlank() || title.length()>160 || location.length()>240 || messageCount<1 || revision<1) throw new IllegalArgumentException("Invalid scene summary");
    }
}
