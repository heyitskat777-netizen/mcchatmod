package dev.kat.lotcjournal.domain;
/** A shared literal name/alias is review evidence, never proof that the identities are the same. */
public record IdentityReviewPair(String firstIdentity,String secondIdentity) {
    public IdentityReviewPair {SceneParticipant.requireUuid(firstIdentity);SceneParticipant.requireUuid(secondIdentity);if(firstIdentity.equals(secondIdentity))throw new IllegalArgumentException("Self pair");}
}
