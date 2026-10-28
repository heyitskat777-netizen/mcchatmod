package dev.kat.lotcjournal.domain;
import java.util.List;
/** Consistent decision metadata; no dialogue or persona knowledge is copied into a review queue. */
public record IdentityReviewData(List<CharacterIdentity> identities,List<IdentityMerge> merges,List<IdentitySeparation> separations) {
    public IdentityReviewData {identities=List.copyOf(identities);merges=List.copyOf(merges);separations=List.copyOf(separations);}
}
