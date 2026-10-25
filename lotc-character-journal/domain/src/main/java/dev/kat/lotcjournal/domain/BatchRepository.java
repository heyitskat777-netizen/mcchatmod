package dev.kat.lotcjournal.domain;

import java.util.List;

public interface BatchRepository {
    void createBatch(BatchPlan plan) throws Exception;
    BatchPlan batchPlan(String batchId) throws Exception;
    BatchSummary batchSummary(String batchId) throws Exception;
    void updateFile(String batchId, int ordinal, String status, String sourceId, String errorCode) throws Exception;
    void finishBatch(String batchId, String status) throws Exception;
    List<BatchSummary> batches(int limit) throws Exception;
    List<BatchFile> batchFiles(String batchId) throws Exception;
    long undoBatch(String batchId) throws Exception;
    default long unknownRecords(String batchId) throws Exception {
        throw new UnsupportedOperationException("Batch unknown-record counts are unavailable");
    }
    WatchSettings watchSettings() throws Exception;
    void saveWatchSettings(WatchSettings settings) throws Exception;
    boolean watchAttempted(String path, String fingerprint) throws Exception;
    void recordWatchAttempt(String path, String fingerprint, String status) throws Exception;
}
