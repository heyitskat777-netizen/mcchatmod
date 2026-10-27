package dev.kat.lotcjournal.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One consistent snapshot of original evidence, automatic suggestions and explicit review. */
public record MessageTagReview(TranscriptLine message, List<CharacterTag> automaticTags,
                               List<CharacterTag> effectiveTags, boolean overridden, long revision,
                               Map<String,String> identityNames) {
    public MessageTagReview {
        Objects.requireNonNull(message);
        automaticTags = List.copyOf(automaticTags); effectiveTags = List.copyOf(effectiveTags);
        identityNames = Map.copyOf(identityNames);
        if (revision < 0 || (overridden && revision == 0)) throw new IllegalArgumentException("Invalid review revision");
        if (java.util.Set.copyOf(automaticTags).size() != automaticTags.size()
            || java.util.Set.copyOf(effectiveTags).size() != effectiveTags.size()) throw new IllegalArgumentException("Duplicate tags");
    }
    public MessageTagReview(TranscriptLine message,List<CharacterTag> automaticTags,List<CharacterTag> effectiveTags,boolean overridden,long revision) {
        this(message,automaticTags,effectiveTags,overridden,revision,Map.of());
    }
}
