package dev.kat.lotcjournal.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageTagReviewTest {
    private static final String FIRST = "40000000-0000-0000-0000-000000000001", SECOND = "40000000-0000-0000-0000-000000000002";
    private static TranscriptLine message() { return new TranscriptLine("a".repeat(64), 7, null, "Synthetic dialogue.", Channel.ROLEPLAY, null); }

    @Test void asynchronousReviewSnapshotsDefensivelyCopyAutomaticEffectiveAndDisplayNameCollections() {
        var automaticTag = new CharacterTag(FIRST, CharacterEvidenceKind.SPEAKER); var reviewedTag = new CharacterTag(SECOND, CharacterEvidenceKind.ACTION);
        var automatic = new ArrayList<>(List.of(automaticTag)); var effective = new ArrayList<>(List.of(reviewedTag));
        var names = new HashMap<>(Map.of(FIRST, "Synthetic First", SECOND, "Synthetic Second"));
        var review = new MessageTagReview(message(), automatic, effective, true, 1, names);
        automatic.clear(); effective.clear(); names.clear();
        assertEquals(List.of(automaticTag), review.automaticTags()); assertEquals(List.of(reviewedTag), review.effectiveTags());
        assertEquals(Map.of(FIRST, "Synthetic First", SECOND, "Synthetic Second"), review.identityNames());
        assertEquals(7, review.message().sourceLine());
        assertThrows(UnsupportedOperationException.class, () -> review.automaticTags().clear());
        assertThrows(UnsupportedOperationException.class, () -> review.effectiveTags().clear());
        assertThrows(UnsupportedOperationException.class, () -> review.identityNames().clear());
    }

    @Test void duplicateTagsAndImpossibleRevisionStatesAreRejectedButAnAutomaticTombstoneIsValid() {
        var tag = new CharacterTag(FIRST, CharacterEvidenceKind.MENTION);
        assertThrows(IllegalArgumentException.class, () -> new MessageTagReview(message(), List.of(tag, tag), List.of(), false, 0));
        assertThrows(IllegalArgumentException.class, () -> new MessageTagReview(message(), List.of(), List.of(tag, tag), true, 1));
        assertThrows(IllegalArgumentException.class, () -> new MessageTagReview(message(), List.of(), List.of(), true, 0));
        assertThrows(IllegalArgumentException.class, () -> new MessageTagReview(message(), List.of(), List.of(), false, -1));
        var automatic = new MessageTagReview(message(), List.of(tag), List.of(tag), false, 2);
        assertFalse(automatic.overridden()); assertEquals(2, automatic.revision());
        assertThrows(IllegalArgumentException.class, () -> new CharacterTag("not-an-id", CharacterEvidenceKind.MENTION));
    }
}
