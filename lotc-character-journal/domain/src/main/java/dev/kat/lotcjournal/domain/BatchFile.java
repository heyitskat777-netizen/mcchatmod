package dev.kat.lotcjournal.domain;

import java.util.Set;
import java.util.UUID;

/** Durable file metadata and cumulative contribution, never dialogue payload. */
public record BatchFile(String batchId, int ordinal, String path, String label, String sourceId,
                        String status, String errorCode, long beforeLine, long afterLine,
                        long added, long excluded, long ignored) {
    public static final Set<String> STATUSES = Set.of("PENDING", "RUNNING", "COMPLETE", "DUPLICATE",
        "FAILED", "SKIPPED", "CANCELLED", "INTERRUPTED", "UNDONE");
    public BatchFile {
        requireBatchId(batchId);
        requireText(path, 4096, "file path");
        requireText(label, 255, "file label");
        if (ordinal < 0 || ordinal >= 10_000) throw new IllegalArgumentException("Invalid file ordinal");
        if (sourceId != null) ImportSource.requireId(sourceId);
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("Invalid file status");
        if (errorCode != null && !errorCode.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("Only a bounded diagnostic code may be persisted");
        if (beforeLine < 0 || afterLine < beforeLine || added < 0 || excluded < 0 || ignored < 0
            || Math.addExact(Math.addExact(added, excluded), ignored) != afterLine - beforeLine)
            throw new IllegalArgumentException("Invalid file contribution accounting");
    }
    static void requireBatchId(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid batch identity");
    }
    static void requireText(String value, int limit, String name) {
        if (value == null || value.isBlank() || value.length() > limit) throw new IllegalArgumentException("Invalid " + name);
    }
}
