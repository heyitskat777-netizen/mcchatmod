package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.JournalSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqliteJournalStoreTest {
    @TempDir Path directory;
    private SqliteJournalStore store(String file) { return new SqliteJournalStore(directory.resolve(file)); }

    @Test void restartPreservesSettingsAndInitializationIsIdempotent() throws Exception {
        var first = store("journal.sqlite");
        first.initialize();
        assertEquals(JournalSettings.defaults(), first.load());
        first.save(new JournalSettings(true));
        var reopened = store("journal.sqlite");
        reopened.initialize();
        reopened.initialize();
        assertTrue(reopened.load().compactLayout());
    }

    @Test void backupCanBeRestoredAndIsASnapshot() throws Exception {
        var original = store("journal.sqlite");
        original.initialize();
        original.save(new JournalSettings(true));
        Path backup = original.createBackup(directory.resolve("backups/saved.sqlite"));
        original.save(new JournalSettings(false));
        Path restored = directory.resolve("restored.sqlite");
        Files.copy(backup, restored);
        var replacement = new SqliteJournalStore(restored);
        replacement.initialize();
        assertTrue(replacement.load().compactLayout());
        assertFalse(original.load().compactLayout());
    }

    @Test void refusesOverwritingBackupOrSource() throws Exception {
        var original = store("journal.sqlite");
        original.initialize();
        assertThrows(IllegalArgumentException.class,
            () -> original.createBackup(directory.resolve("journal.sqlite")));
        Path saved = directory.resolve("copy.sqlite");
        original.createBackup(saved);
        assertThrows(IllegalArgumentException.class, () -> original.createBackup(saved));
    }

    @Test void newerDatabaseIsNotDowngraded() throws Exception {
        Path path = directory.resolve("future.sqlite");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path);
             var s = c.createStatement()) { s.execute("PRAGMA user_version=999"); }
        assertThrows(java.sql.SQLException.class, () -> new SqliteJournalStore(path).initialize());
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path);
             var s = c.createStatement(); var r = s.executeQuery("PRAGMA user_version")) {
            assertEquals(999, r.getInt(1));
        }
    }

    @Test void refusesAdoptingUnrelatedLegacyDatabase() throws Exception {
        Path path = directory.resolve("legacy.sqlite");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path);
             var s = c.createStatement()) { s.execute("CREATE TABLE messages (raw TEXT)"); }
        assertThrows(java.sql.SQLException.class, () -> new SqliteJournalStore(path).initialize());
    }

    @Test void corruptedDatabaseIsNotReplaced() throws Exception {
        Path path = directory.resolve("broken.sqlite");
        byte[] content = "not a database".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(path, content);
        assertThrows(java.sql.SQLException.class, () -> new SqliteJournalStore(path).initialize());
        assertArrayEquals(content, Files.readAllBytes(path));
    }
}

