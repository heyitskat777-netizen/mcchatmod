package dev.kat.lotcjournal.domain;
import java.util.List;
public record SceneDetail(SceneSummary summary, List<SceneParticipant> participants, SceneStatistics statistics, SceneAssessment assessment) {
    public SceneDetail(SceneSummary summary,List<SceneParticipant> participants,SceneStatistics statistics){this(summary,participants,statistics,SceneAssessment.unassessed());}
    public SceneDetail { participants=List.copyOf(participants); java.util.Objects.requireNonNull(assessment); if(summary.messageCount()!=statistics.messages()) throw new IllegalArgumentException("Scene count mismatch"); }
}
