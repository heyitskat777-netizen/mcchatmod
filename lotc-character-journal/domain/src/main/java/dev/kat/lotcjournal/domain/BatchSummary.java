package dev.kat.lotcjournal.domain;

import java.time.Instant;
import java.util.Set;

public record BatchSummary(String id, String root, String status, String createdAt, String updatedAt, int fileCount) {
    public static final Set<String> STATUSES = Set.of("PENDING", "RUNNING", "COMPLETE", "PARTIAL",
        "FAILED", "CANCELLED", "INTERRUPTED", "UNDONE");
    public BatchSummary {
        BatchFile.requireBatchId(id);
        BatchFile.requireText(root, 4096, "batch root");
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("Invalid batch status");
        Instant.parse(createdAt);
        Instant.parse(updatedAt);
        if (fileCount < 1 || fileCount > 10_000) throw new IllegalArgumentException("Invalid file count");
    }
}
