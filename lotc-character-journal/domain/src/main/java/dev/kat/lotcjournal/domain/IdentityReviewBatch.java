package dev.kat.lotcjournal.domain;
import java.util.List;
import java.util.Map;
public record IdentityReviewBatch(List<IdentityReviewPair> pairs,boolean hasMore,Map<IdentityReviewPair,String> matchingNames) {
    public IdentityReviewBatch(List<IdentityReviewPair> pairs,boolean hasMore){this(pairs,hasMore,Map.of());}
    public IdentityReviewBatch {pairs=List.copyOf(pairs);matchingNames=Map.copyOf(matchingNames);}
}
