package dev.kat.lotcjournal.ingestion;

import dev.kat.lotcjournal.domain.ImportProgress;
import dev.kat.lotcjournal.domain.ImportSource;
import java.nio.file.Path;
import java.util.Objects;

/** Fully validated source metadata; payload bytes never escape the import worker. */
public record FilePreview(Path path, ImportSource source, long encodedBytes, long decodedBytes,
                          ImportProgress knownProgress) {
    public FilePreview {
        path = Objects.requireNonNull(path).toAbsolutePath().normalize();
        Objects.requireNonNull(source);
        if (encodedBytes < 0 || decodedBytes < 0) throw new IllegalArgumentException("Negative preview size");
    }
}
