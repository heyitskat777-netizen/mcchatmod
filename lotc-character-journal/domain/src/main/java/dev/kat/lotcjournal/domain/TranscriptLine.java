package dev.kat.lotcjournal.domain;

import java.util.Objects;

/** Original observation only. Clock has no inferred date/timezone; null means unknown. */
public record TranscriptLine(String sourceId, long sourceLine, String clock, String raw,
                             String text, Channel channel, String observedSpeaker) {
    public TranscriptLine {
        ImportSource.requireId(sourceId);
        if (sourceLine < 1) throw new IllegalArgumentException("Invalid source line coordinate");
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(channel, "channel");
        if (channel == Channel.PRIVATE_MESSAGE || channel == Channel.OOC)
            throw new IllegalArgumentException("Excluded channel cannot be stored");
        if (raw.length() > 65_536 || text.length() > 65_536)
            throw new IllegalArgumentException("Transcript line is too long");
    }
}
