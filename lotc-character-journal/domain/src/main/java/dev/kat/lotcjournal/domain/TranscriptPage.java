package dev.kat.lotcjournal.domain;

import java.util.List;
import java.util.Set;

public record TranscriptPage(List<TranscriptLine> entries, long total, int offset, int limit, Set<MessageReference> favorites) {
    public TranscriptPage(List<TranscriptLine> entries, long total, int offset, int limit) {
        this(entries, total, offset, limit, Set.of());
    }
    public TranscriptPage {
        entries = List.copyOf(entries);
        favorites = Set.copyOf(favorites);
        var references = entries.stream().map(MessageReference::of).collect(java.util.stream.Collectors.toSet());
        if (!references.containsAll(favorites)) throw new IllegalArgumentException("Favorites outside page");
        if (total < 0 || offset < 0 || limit < 1 || limit > 200 || entries.size() > limit || entries.size() > total)
            throw new IllegalArgumentException("Invalid transcript page");
    }
}
