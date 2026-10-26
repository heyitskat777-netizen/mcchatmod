package dev.kat.lotcjournal.domain;

import java.util.List;

/** Atomic cursor/observation port; implementations own persistence and conflict detection. */
public interface CaptureRepository {
    ImportProgress beginSource(ImportSource source) throws Exception;
    ImportProgress progress(String sourceId) throws Exception;
    ImportProgress appendBatch(String sourceId, long expectedProcessed, long nextProcessed,
        List<TranscriptLine> entries, long excludedDelta, long ignoredDelta, boolean complete) throws Exception;
    default ImportProgress appendBatchOwned(String batchId, int ordinal, String sourceId, long expectedProcessed, long nextProcessed,
        List<TranscriptLine> entries, long excludedDelta, long ignoredDelta, boolean complete) throws Exception {
        throw new UnsupportedOperationException("Atomic batch ownership is unavailable");
    }
    List<ImportSource> sources() throws Exception;
    TranscriptPage transcript(String sourceId, String query, int offset, int limit) throws Exception;
    default TranscriptPage transcript(String sourceId, String query, Channel channel, int offset, int limit) throws Exception {
        if (channel != null) throw new UnsupportedOperationException("Channel filtering is unavailable");
        return transcript(sourceId, query, offset, limit);
    }
    default TranscriptPage transcript(String sourceId, String query, Channel channel, boolean favoritesOnly, int offset, int limit) throws Exception {
        if (favoritesOnly) throw new UnsupportedOperationException("Favorites are unavailable");
        return transcript(sourceId, query, channel, offset, limit);
    }
    default void setFavorite(MessageReference message, boolean favorite) throws Exception {
        throw new UnsupportedOperationException("Favorites are unavailable");
    }
    default long removedRecords(String sourceId) throws Exception { return 0; }
}
