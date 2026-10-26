package dev.kat.lotcjournal.domain;

import java.util.Objects;

/** Historical IDs hash complete validated decompressed bytes; live IDs belong to the explicit session namespace. */
public record ImportSource(String id, String label, String format, long totalLines, String policyFingerprint) {
    /** Unconfigured low-level callers still have an explicit stable classification policy. */
    public ImportSource(String id, String label, String format, long totalLines) {
        this(id, label, format, totalLines, "0".repeat(64));
    }

    public ImportSource {
        requireId(id);
        requireId(policyFingerprint);
        Objects.requireNonNull(label, "label");
        if (label.isBlank() || label.length() > 255) throw new IllegalArgumentException("Invalid source label");
        if (!"plain".equals(format) && !"gzip".equals(format)) throw new IllegalArgumentException("Invalid source format");
        if (totalLines < 0) throw new IllegalArgumentException("Negative source line count");
    }

    static void requireId(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid source digest");
    }
}
