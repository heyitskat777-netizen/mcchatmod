package dev.kat.lotcjournal.domain;
import java.util.List;
public record SceneDetail(SceneSummary summary, List<SceneParticipant> participants, SceneStatistics statistics) {
    public SceneDetail { participants=List.copyOf(participants); if(summary.messageCount()!=statistics.messages()) throw new IllegalArgumentException("Scene count mismatch"); }
}
