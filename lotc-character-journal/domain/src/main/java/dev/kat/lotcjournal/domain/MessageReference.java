package dev.kat.lotcjournal.domain;

/** Stable observation coordinate; carries no copied dialogue or inferred identity. */
public record MessageReference(String sourceId, long sourceLine) {
    public MessageReference {
        if (sourceId == null || !sourceId.matches("[0-9a-f]{64}") || sourceLine < 1)
            throw new IllegalArgumentException("Invalid message reference");
    }
    public static MessageReference of(TranscriptLine line) {
        return new MessageReference(line.sourceId(), line.sourceLine());
    }
}
