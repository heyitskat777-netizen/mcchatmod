package dev.kat.lotcjournal.domain;
import java.util.*;
/** Grouping confidence only. It never confirms character identity or grants persona knowledge. */
public final class SceneConfidence {
    public static final int MIN_MESSAGES=10, MIN_PARTICIPANTS=2, MAX_CLEAR_PARTICIPANTS=8, CLEAR_GAP_SECONDS=300;
    private SceneConfidence() {}
    public static SceneAssessment assess(List<SceneObservation> observations,List<Set<String>> actorsByMessage) {
        if(observations.size()!=actorsByMessage.size())throw new IllegalArgumentException("Scene evidence count mismatch");
        var actors=new HashSet<String>();var reasons=new ArrayList<String>();boolean unresolved=false,multiple=false;
        for(var ids:actorsByMessage){Objects.requireNonNull(ids);for(String id:ids){SceneParticipant.requireUuid(id);actors.add(id);}unresolved|=ids.isEmpty();multiple|=ids.size()>1;}
        if(observations.size()<MIN_MESSAGES)reasons.add("Fewer than 10 roleplay messages");
        if(actors.size()<MIN_PARTICIPANTS)reasons.add("Fewer than 2 distinct observed participants");
        boolean eligible=observations.size()>=MIN_MESSAGES&&actors.size()>=MIN_PARTICIPANTS;
        if(unresolved)reasons.add("Some messages have an unresolved actor");
        if(multiple)reasons.add("Some messages have several possible actors");
        if(SceneGrouper.elapsed(observations)==null)reasons.add("Timing or session context is uncertain");
        else for(int i=1;i<observations.size();i++)if(observations.get(i).timelineSeconds()-observations.get(i-1).timelineSeconds()>CLEAR_GAP_SECONDS){reasons.add("A dialogue gap exceeds 5 minutes");break;}
        if(actors.size()>MAX_CLEAR_PARTICIPANTS)reasons.add("Crowded group may contain interleaved conversations");
        return new SceneAssessment(eligible,eligible&&reasons.isEmpty(),actors.size(),reasons);
    }
}
