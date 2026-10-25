package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.Channel;
import dev.kat.lotcjournal.domain.ImportSource;
import dev.kat.lotcjournal.domain.JournalSettings;
import dev.kat.lotcjournal.domain.TranscriptLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CaptureRepositoryTest {
    private static final String ID = "a".repeat(64);
    @TempDir Path directory;

    private SqliteJournalStore create(String name) throws Exception {
        var store = new SqliteJournalStore(directory.resolve(name));
        store.initialize();
        return store;
    }

    private static TranscriptLine line(long coordinate, String text) {
        return new TranscriptLine(ID, coordinate, "23:59:59", "synthetic raw " + text,
            text, Channel.ROLEPLAY, "Synthetic Speaker");
    }

    private Path versionOne(String name) throws Exception {
        Path path = directory.resolve(name);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE journal_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.execute("INSERT INTO journal_settings VALUES ('compact_layout','true')");
            statement.execute("PRAGMA user_version=1");
        }
        return path;
    }

    @Test void migrationBacksUpOwnedVersionOneBeforeCreatingCaptureTables() throws Exception {
        Path path = versionOne("owned.sqlite");
        var store = new SqliteJournalStore(path);
        store.initialize();
        assertTrue(store.load().compactLayout());
        assertEquals(List.of(), store.sources());
        List<Path> backups;
        try (var files = Files.list(directory.resolve("backups"))) { backups = files.toList(); }
        assertEquals(1, backups.size());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + backups.getFirst());
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA user_version")) { assertEquals(1, rows.getInt(1)); }
            try (var rows = statement.executeQuery("SELECT value FROM journal_settings WHERE key='compact_layout'")) {
                assertEquals("true", rows.getString(1));
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='capture_sources'")) {
                assertEquals(0, rows.getInt(1));
            }
        }
        store.initialize();
        try (var files = Files.list(directory.resolve("backups"))) { assertEquals(1, files.count(), "No repeated migration backup"); }
        Path restored = directory.resolve("restored-v1.sqlite");
        Files.copy(backups.getFirst(), restored);
        var replacement = new SqliteJournalStore(restored);
        replacement.initialize();
        assertTrue(replacement.load().compactLayout());
    }

    @Test void backupFailureAbortsMigrationWithoutChangingOriginalBytes() throws Exception {
        Path path = versionOne("owned.sqlite");
        byte[] original = Files.readAllBytes(path);
        Files.writeString(directory.resolve("backups"), "synthetic obstruction");
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> new SqliteJournalStore(path).initialize());
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test void foreignVersionTwoIsRejectedWithoutChangingBytesOrCreatingBackups() throws Exception {
        Path path = directory.resolve("foreign.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE foreign_data(value TEXT)");
            statement.execute("INSERT INTO foreign_data VALUES ('synthetic preserved')");
            statement.execute("PRAGMA user_version=2");
        }
        byte[] original = Files.readAllBytes(path);
        assertThrows(SQLException.class, () -> new SqliteJournalStore(path).initialize());
        assertArrayEquals(original, Files.readAllBytes(path));
        assertFalse(Files.exists(directory.resolve("backups")));
    }

    @Test void corruptedCaptureCountersAreRejectedWithoutRewritingDatabase() throws Exception {
        var store = create("inconsistent.sqlite");
        store.beginSource(new ImportSource(ID, "synthetic.log", "plain", 1));
        Path path = directory.resolve("inconsistent.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.execute("UPDATE capture_sources SET imported_lines=1");
        }
        byte[] original = Files.readAllBytes(path);
        assertThrows(SQLException.class, store::initialize);
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test void concurrentCheckpointWritersHaveOneWinnerWithoutDuplicatingObservation() throws Exception {
        var store = create("concurrent.sqlite");
        store.beginSource(new ImportSource(ID, "synthetic.log", "plain", 1));
        var ready = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> append = () -> {
            ready.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Release timed out");
            try {
                store.appendBatch(ID, 0, 1, List.of(line(1, "once")), 0, 0, true);
                return true;
            } catch (SQLException conflict) { return false; }
        };
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = workers.submit(append);
            var second = workers.submit(append);
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            release.countDown();
            assertEquals(1, (first.get(5, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)
                + (second.get(5, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0));
        } finally { release.countDown(); }
        store.initialize();
        assertEquals(1, store.progress(ID).importedLines());
        assertEquals(1, store.transcript(ID, null, 0, 20).total());
    }

    @Test void checkpointsSurviveRestartAndSameTextAtDifferentCoordinatesIsPreserved() throws Exception {
        var store = create("journal.sqlite");
        store.beginSource(new ImportSource(ID, "synthetic.log", "plain", 4));
        var first = store.appendBatch(ID, 0, 2, List.of(line(1, "same words")), 1, 0, false);
        assertEquals(2, first.processedLines());
        assertEquals(1, first.importedLines());
        var reopened = new SqliteJournalStore(directory.resolve("journal.sqlite"));
        reopened.initialize();
        assertEquals(first, reopened.progress(ID));
        reopened.appendBatch(ID, 2, 4, List.of(line(3, "same words")), 0, 1, true);
        var page = reopened.transcript(ID, "same words", 0, 20);
        assertEquals(2, page.total());
        assertEquals(List.of(1L, 3L), page.entries().stream().map(TranscriptLine::sourceLine).toList());
        assertEquals("23:59:59", page.entries().getFirst().clock());
        assertEquals("synthetic raw same words", page.entries().getFirst().raw());
        assertTrue(reopened.progress(ID).complete());
    }

    @Test void staleBatchCannotDoubleCountsAndPlainGzipDuplicatesReuseSource() throws Exception {
        var store = create("journal.sqlite");
        var original = new ImportSource(ID, "synthetic.log", "plain", 1);
        store.beginSource(original);
        var saved = store.appendBatch(ID, 0, 1, List.of(line(1, "once")), 0, 0, true);
        assertThrows(SQLException.class, () -> store.appendBatch(ID, 0, 1, List.of(line(1, "once")), 0, 0, true));
        assertEquals(saved, store.beginSource(new ImportSource(ID, "renamed.log.gz", "gzip", 1)));
        assertEquals(List.of(original), store.sources());
        assertEquals(saved, store.appendBatch(ID, 1, 1, List.of(), 0, 0, true));
        assertEquals(1, store.transcript(ID, null, 0, 20).total());
        assertThrows(SQLException.class, () -> store.beginSource(new ImportSource(ID, "different.log", "plain", 2)));
        assertEquals(saved, store.progress(ID));
    }

    @Test void partialResumeRejectsChangedPolicyAndCompletedSourceNeverReclassifies() throws Exception {
        var store = create("policy.sqlite");
        String originalPolicy = "b".repeat(64), changedPolicy = "c".repeat(64);
        var source = new ImportSource(ID, "synthetic.log", "plain", 2, originalPolicy);
        store.beginSource(source);
        var checkpoint = store.appendBatch(ID, 0, 1, List.of(line(1, "first")), 0, 0, false);
        var changed = new ImportSource(ID, "copy.log.gz", "gzip", 2, changedPolicy);
        assertThrows(SQLException.class, () -> store.beginSource(changed));
        assertEquals(checkpoint, store.progress(ID));
        assertEquals(List.of(source), store.sources());
        assertEquals(checkpoint, store.beginSource(source));
        var finished = store.appendBatch(ID, 1, 2, List.of(line(2, "second")), 0, 0, true);
        assertEquals(finished, store.beginSource(changed));
        assertEquals(List.of(source), store.sources(), "Completed source retains original classification policy");
        assertEquals(2, store.transcript(ID, null, 0, 20).total());
    }

    @Test void failedRowInsertRollsBackCursorCountersAndEarlierRowsInSameBatch() throws Exception {
        var store = create("journal.sqlite");
        store.beginSource(new ImportSource(ID, "synthetic.log", "plain", 2));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("journal.sqlite"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER synthetic_failure BEFORE INSERT ON transcript_lines WHEN NEW.source_line=2 BEGIN SELECT RAISE(ABORT,'synthetic disk failure'); END");
        }
        assertThrows(SQLException.class, () -> store.appendBatch(ID, 0, 2, List.of(line(1, "first"), line(2, "second")), 0, 0, true));
        assertEquals(0, store.progress(ID).processedLines());
        assertEquals(0, store.progress(ID).importedLines());
        assertEquals(0, store.transcript(ID, null, 0, 20).total());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("journal.sqlite"));
             var statement = connection.createStatement()) { statement.execute("DROP TRIGGER synthetic_failure"); }
        var reopened = new SqliteJournalStore(directory.resolve("journal.sqlite"));
        reopened.initialize();
        assertEquals(2, reopened.appendBatch(ID, 0, 2, List.of(line(1, "first"), line(2, "second")), 0, 0, true).importedLines());
    }

    @Test void invalidCoordinatesAndCountersCannotAdvanceCheckpoint() throws Exception {
        var store = create("journal.sqlite");
        store.beginSource(new ImportSource(ID, "synthetic.log", "plain", 2));
        assertThrows(IllegalArgumentException.class, () -> store.appendBatch(ID, 0, 2, List.of(line(2, "later"), line(1, "earlier")), 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> store.appendBatch(ID, 0, 2, List.of(line(1, "one")), 0, 0, false));
        assertThrows(SQLException.class, () -> store.appendBatch(ID, 0, 1, List.of(line(1, "one")), 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> line(0, "bad coordinate"));
        assertEquals(0, store.progress(ID).processedLines());
    }

    @Test void excludedMessagesNeverEnterTranscriptAndSqlAlsoRejectsThem() throws Exception {
        for (var channel : List.of(Channel.OOC, Channel.PRIVATE_MESSAGE))
            assertThrows(IllegalArgumentException.class, () -> new TranscriptLine(ID, 1, null, "synthetic excluded", "synthetic excluded", channel, null));
        var store = create("journal.sqlite");
        store.beginSource(new ImportSource(ID, "synthetic.log", "plain", 2));
        store.appendBatch(ID, 0, 2, List.of(), 2, 0, true);
        assertEquals(2, store.progress(ID).excludedLines());
        assertEquals(0, store.transcript(null, null, 0, 20).total());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("journal.sqlite"));
             var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO transcript_lines VALUES ('" + ID + "',1,NULL,'synthetic excluded','synthetic excluded','PRIVATE_MESSAGE',NULL)"));
        }
    }

    @Test void literalSearchPagingAndBackupRestorationRetainSourceProvenance() throws Exception {
        var store = create("journal.sqlite");
        store.beginSource(new ImportSource(ID, "synthetic.log", "plain", 3));
        store.appendBatch(ID, 0, 3, List.of(line(1, "literal 100%_\\"), line(2, "ordinary"), line(3, "ordinary")), 0, 0, true);
        assertEquals(1, store.transcript(null, "%_\\", 0, 20).total());
        assertEquals(0, store.transcript(null, "' OR 1=1 --", 0, 20).total());
        var page = store.transcript(null, "ordinary", 1, 1);
        assertEquals(2, page.total());
        assertEquals(3, page.entries().getFirst().sourceLine());
        assertEquals(0, store.transcript(null, "ordinary", 99, 20).entries().size());
        assertThrows(IllegalArgumentException.class, () -> store.transcript(null, "", -1, 20));
        assertThrows(IllegalArgumentException.class, () -> store.transcript(null, "", 0, 201));
        Path backup = store.createBackup(directory.resolve("backups/imported.sqlite"));
        var restored = new SqliteJournalStore(backup);
        restored.initialize();
        assertEquals(store.progress(ID), restored.progress(ID));
        assertEquals(store.sources(), restored.sources());
        assertEquals(3, restored.transcript(ID, null, 0, 20).total());
        store.save(new JournalSettings(true));
        assertFalse(restored.load().compactLayout());
    }
}
