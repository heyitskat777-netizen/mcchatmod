package dev.kat.lotcjournal.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public record BatchPlan(String id, String root, String policyFingerprint, String createdAt,
                        Set<String> reviewedNames, List<BatchFile> files) {
    public BatchPlan(String id, String root, String policyFingerprint, String createdAt, List<BatchFile> files) {
        this(id, root, policyFingerprint, createdAt, Set.of(), files);
    }
    public BatchPlan {
        BatchFile.requireBatchId(id);
        BatchFile.requireText(root, 4096, "batch root");
        ImportSource.requireId(policyFingerprint);
        Instant.parse(createdAt);
        reviewedNames = checkedNames(reviewedNames);
        files = List.copyOf(files);
        if (files.isEmpty() || files.size() > 10_000) throw new IllegalArgumentException("Batch must contain 1–10000 files");
        for (int i = 0; i < files.size(); i++) {
            if (!files.get(i).batchId().equals(id) || files.get(i).ordinal() != i)
                throw new IllegalArgumentException("Batch files must have ordered contiguous ordinals");
        }
    }
    public static Set<String> checkedNames(Set<String> names) {
        var sorted = new TreeSet<String>(names);
        if (sorted.size() > 200) throw new IllegalArgumentException("Too many reviewed names");
        for (String name : sorted) BatchFile.requireText(name, 128, "reviewed name");
        return Collections.unmodifiableSet(sorted);
    }
}
