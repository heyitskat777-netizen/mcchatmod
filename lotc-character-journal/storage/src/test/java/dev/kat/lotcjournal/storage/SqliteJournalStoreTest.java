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
    private SqliteJournalStore store(String file) { return new SqliteJournalStore(directory.resolve(file), new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()); }

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
        Files.copy(backup.resolveSibling(backup.getFileName() + ".characters.sqlite"), restored.resolveSibling(restored.getFileName() + ".characters.sqlite"));
        Files.copy(backup.resolveSibling(backup.getFileName() + ".pair.json"), restored.resolveSibling(restored.getFileName() + ".pair.json"));
        var replacement = new SqliteJournalStore(restored, new dev.kat.lotcjournal.parsing.DialogueTextNormalizer());
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
        assertThrows(java.sql.SQLException.class, () -> new SqliteJournalStore(path, new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()).initialize());
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path);
             var s = c.createStatement(); var r = s.executeQuery("PRAGMA user_version")) {
            assertEquals(999, r.getInt(1));
        }
    }

    @Test void refusesAdoptingUnrelatedLegacyDatabase() throws Exception {
        Path path = directory.resolve("legacy.sqlite");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path);
             var s = c.createStatement()) { s.execute("CREATE TABLE messages (raw TEXT)"); }
        assertThrows(java.sql.SQLException.class, () -> new SqliteJournalStore(path, new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()).initialize());
    }

    @Test void corruptedDatabaseIsNotReplaced() throws Exception {
        Path path = directory.resolve("broken.sqlite");
        byte[] content = "not a database".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(path, content);
        assertThrows(java.sql.SQLException.class, () -> new SqliteJournalStore(path, new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()).initialize());
        assertArrayEquals(content, Files.readAllBytes(path));
    }

    @Test void unrelatedDatabaseClaimingCurrentVersionIsRejectedWithoutChanges() throws Exception {
        Path path = directory.resolve("unrelated-current.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unrelated (value TEXT)");
            statement.execute("INSERT INTO unrelated VALUES ('synthetic original')");
            statement.execute("PRAGMA user_version=1");
        }
        byte[] original = Files.readAllBytes(path);
        assertThrows(java.sql.SQLException.class, () -> new SqliteJournalStore(path, new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()).initialize());
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test void malformedCurrentSettingsSchemaIsRejectedWithoutChanges() throws Exception {
        Path path = directory.resolve("malformed-current.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE journal_settings (value TEXT)");
            statement.execute("INSERT INTO journal_settings VALUES ('synthetic original')");
            statement.execute("PRAGMA user_version=1");
        }
        byte[] original = Files.readAllBytes(path);
        assertThrows(java.sql.SQLException.class, () -> new SqliteJournalStore(path, new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()).initialize());
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test void malformedSavedBooleanFailsWithoutReplacingItWithDefaults() throws Exception {
        var original = store("invalid-setting.sqlite");
        original.initialize();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("invalid-setting.sqlite"));
             var statement = connection.createStatement()) {
            statement.execute("UPDATE journal_settings SET value='synthetic invalid' WHERE key='compact_layout'");
        }
        assertThrows(java.sql.SQLException.class, original::load);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("invalid-setting.sqlite"));
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT value FROM journal_settings WHERE key='compact_layout'")) {
            assertTrue(result.next());
            assertEquals("synthetic invalid", result.getString(1));
        }
    }

    @Test void existingEmptyBackupIsNotClaimedOrWritten() throws Exception {
        var original = store("journal.sqlite");
        original.initialize();
        Path existing = directory.resolve("reserved.sqlite");
        Files.createFile(existing);
        assertThrows(IllegalArgumentException.class, () -> original.createBackup(existing));
        assertTrue(Files.exists(existing));
        assertEquals(0, Files.size(existing));
    }

    @Test void failedBackupRemovesIncompleteDestinationAndPreservesSource() throws Exception {
        Path database = directory.resolve("broken-source.sqlite");
        byte[] bytes = "synthetic corrupt SQLite source".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(database, bytes);
        Path destination = directory.resolve("backups/incomplete.sqlite");
        var corrupt = new SqliteJournalStore(database, new dev.kat.lotcjournal.parsing.DialogueTextNormalizer());
        assertThrows(java.sql.SQLException.class, () -> corrupt.createBackup(destination));
        assertFalse(Files.exists(destination), "Failed backup must not be presented as a saved snapshot");
        assertArrayEquals(bytes, Files.readAllBytes(database));
    }

    @Test void concurrentBackupsHaveExactlyOneWinnerWithoutOverwriting() throws Exception {
        var original = store("journal.sqlite");
        original.initialize();
        original.save(new JournalSettings(true));
        Path target = directory.resolve("backups/concurrent.sqlite");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> attempt = () -> {
            ready.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                throw new AssertionError("Concurrent backup release timed out");
            try {
                original.createBackup(target);
                return true;
            } catch (IllegalArgumentException alreadyReserved) {
                return false;
            }
        };
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            release.countDown();
            int winners = (first.get(5, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)
                + (second.get(5, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, winners, "One request owns the destination; the other must refuse it");
        } finally {
            release.countDown();
        }
        var saved = new SqliteJournalStore(target, new dev.kat.lotcjournal.parsing.DialogueTextNormalizer());
        saved.initialize();
        assertTrue(saved.load().compactLayout());
        original.save(new JournalSettings(false));
        assertTrue(saved.load().compactLayout(), "The winning snapshot must remain independent");
    }
}

