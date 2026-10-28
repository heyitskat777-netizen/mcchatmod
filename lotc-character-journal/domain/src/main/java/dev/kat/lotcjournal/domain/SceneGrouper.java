package dev.kat.lotcjournal.domain;
import java.util.*;
/** Conservative deterministic source/session windows; these are suggestions, not proof of one conversation. */
public final class SceneGrouper {
    public static final int MAX_MESSAGES = 5000;
    private SceneGrouper() {}
    public static List<List<SceneObservation>> group(List<SceneObservation> observations, SceneSettings settings) {
        Objects.requireNonNull(settings); var result=new ArrayList<List<SceneObservation>>(); var current=new ArrayList<SceneObservation>();
        var seen=new HashSet<MessageReference>(); SceneObservation previous=null;
        for(var observation:observations) {
            if(!seen.add(MessageReference.of(observation.message()))) throw new IllegalArgumentException("Duplicate scene coordinate");
            boolean boundary=previous!=null && (!previous.message().sourceId().equals(observation.message().sourceId())
                || observation.message().sourceLine()<=previous.message().sourceLine()
                || !previous.boundaryKey().equals(observation.boundaryKey())
                || previous.timelineSeconds()==null || observation.timelineSeconds()==null
                || observation.timelineSeconds()<previous.timelineSeconds()
                || exceedsGap(previous.timelineSeconds(),observation.timelineSeconds(),settings.inactivitySeconds())
                || current.size()>=MAX_MESSAGES);
            if(boundary) {result.add(List.copyOf(current));current.clear();}
            current.add(observation); previous=observation;
        }
        if(!current.isEmpty())result.add(List.copyOf(current)); return List.copyOf(result);
    }
    private static boolean exceedsGap(long previous,long next,int threshold) {
        try {return Math.subtractExact(next,previous)>threshold;} catch(ArithmeticException overflow) {return true;}
    }
    /** Only a common source/session and monotonic complete clock supports duration. */
    public static Long elapsed(List<SceneObservation> observations) {
        if(observations.isEmpty())return null;
        SceneObservation first=observations.getFirst(), previous=first;
        if(first.timelineSeconds()==null)return null;
        for(var value:observations) {
            if(value.timelineSeconds()==null || !value.message().sourceId().equals(first.message().sourceId())
                || !value.boundaryKey().equals(first.boundaryKey()) || value.timelineSeconds()<previous.timelineSeconds())return null;
            previous=value;
        }
        try {return Math.subtractExact(previous.timelineSeconds(),first.timelineSeconds());} catch(ArithmeticException overflow) {return null;}
    }
    public static Long active(List<SceneObservation> observations) {
        if(elapsed(observations)==null)return null;
        long seconds=0;for(int i=1;i<observations.size();i++)seconds+=Math.min(SceneSettings.ACTIVE_GAP_CAP_SECONDS,
            observations.get(i).timelineSeconds()-observations.get(i-1).timelineSeconds());return seconds;
    }
}
