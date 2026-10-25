package dev.kat.lotcjournal.domain;

import java.util.Objects;

/** Clean dialogue observation with separate provenance. Clock has no inferred date/timezone; null means unknown. */
public record TranscriptLine(String sourceId, long sourceLine, String clock,
                             String text, Channel channel, String observedSpeaker) {
    public TranscriptLine {
        ImportSource.requireId(sourceId);
        if (sourceLine < 1) throw new IllegalArgumentException("Invalid source line coordinate");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(channel, "channel");
        if (channel == Channel.PRIVATE_MESSAGE || channel == Channel.OOC || channel == Channel.SYSTEM)
            throw new IllegalArgumentException("Excluded channel cannot be stored");
        if (text.isBlank() || text.length() > 65_536)
            throw new IllegalArgumentException("Dialogue must be nonblank and at most 65536 characters");
    }
}
