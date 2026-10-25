package dev.kat.lotcjournal.domain;

public record ImportProgress(String sourceId, long processedLines, long importedLines,
                             long excludedLines, long ignoredLines, boolean complete) {
    public ImportProgress {
        ImportSource.requireId(sourceId);
        if (processedLines < 0 || importedLines < 0 || excludedLines < 0 || ignoredLines < 0
            || Math.addExact(Math.addExact(importedLines, excludedLines), ignoredLines) != processedLines)
            throw new IllegalArgumentException("Invalid import checkpoint counters");
    }
}
