package dev.kat.lotcjournal.domain;

import java.util.List;

/** Atomic cursor/observation port; implementations own persistence and conflict detection. */
public interface CaptureRepository {
    ImportProgress beginSource(ImportSource source) throws Exception;
    ImportProgress progress(String sourceId) throws Exception;
    ImportProgress appendBatch(String sourceId, long expectedProcessed, long nextProcessed,
        List<TranscriptLine> entries, long excludedDelta, long ignoredDelta, boolean complete) throws Exception;
    List<ImportSource> sources() throws Exception;
    TranscriptPage transcript(String sourceId, String query, int offset, int limit) throws Exception;
}
