package dev.kat.lotcjournal.domain;

import java.util.List;

public record TranscriptPage(List<TranscriptLine> entries, long total, int offset, int limit) {
    public TranscriptPage {
        entries = List.copyOf(entries);
        if (total < 0 || offset < 0 || limit < 1 || limit > 200 || entries.size() > limit || entries.size() > total)
            throw new IllegalArgumentException("Invalid transcript page");
    }
}
