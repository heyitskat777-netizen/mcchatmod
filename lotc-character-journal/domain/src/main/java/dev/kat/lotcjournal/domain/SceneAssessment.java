package dev.kat.lotcjournal.domain;
import java.util.List;
public record SceneAssessment(boolean eligible, boolean highConfidence, int participantCount, List<String> reasons) {
    public SceneAssessment { reasons=List.copyOf(reasons);if(participantCount<0||highConfidence&&!eligible)throw new IllegalArgumentException("Invalid scene assessment"); }
    public static SceneAssessment unassessed(){return new SceneAssessment(false,false,0,List.of("Shared grouping confidence is not exposed in this view"));}
}
