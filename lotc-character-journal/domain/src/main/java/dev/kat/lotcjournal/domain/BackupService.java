package dev.kat.lotcjournal.domain;

import java.nio.file.Path;

public interface BackupService {
    Path createBackup(Path destination) throws Exception;
}

