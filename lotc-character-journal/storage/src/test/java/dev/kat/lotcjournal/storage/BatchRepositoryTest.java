package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BatchRepositoryTest {
    private static final String SOURCE = "a".repeat(64), POLICY = "0".repeat(64);
    @TempDir Path directory;

    private SqliteJournalStore store() throws Exception {
        var store = new SqliteJournalStore(directory.resolve("journal.sqlite"), new dev.kat.lotcjournal.parsing.DialogueTextNormalizer());
        store.initialize();
        return store;
    }
    private BatchPlan plan(String sourceId) {
        String id = UUID.randomUUID().toString();
        return new BatchPlan(id, directory.toString(), POLICY, Instant.now().toString(), Set.of("Synthetic Name"),
            List.of(new BatchFile(id, 0, directory.resolve("synthetic.log").toString(), "synthetic.log", sourceId,
                "PENDING", null, 0, 0, 0, 0, 0)));
    }
    private static TranscriptLine line(long coordinate, Channel channel) {
        return new TranscriptLine(SOURCE, coordinate, "12:34:56", "synthetic text", channel, null);
    }
    private static void start(SqliteJournalStore store, BatchPlan plan) throws Exception {
        store.createBatch(plan);
        store.finishBatch(plan.id(), "RUNNING");
        store.updateFile(plan.id(), 0, "RUNNING", SOURCE, null);
    }
    private static void complete(SqliteJournalStore store, BatchPlan plan) throws Exception {
        store.updateFile(plan.id(), 0, "COMPLETE", SOURCE, null);
        store.finishBatch(plan.id(), "COMPLETE");
    }

    @Test void undoRemovesOnlyOwnedNewRowsAndNeverResetsCursorOrRestoresPayloads() throws Exception {
        var store = store();
        store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 4));
        store.appendBatch(SOURCE, 0, 1, List.of(line(1, Channel.UNKNOWN)), 0, 0, false);
        var first = plan(SOURCE); start(store, first);
        store.appendBatchOwned(first.id(), 0, SOURCE, 1, 3, List.of(line(2, Channel.ROLEPLAY)), 1, 0, false);
        complete(store, first);
        var second = plan(SOURCE); start(store, second);
        store.appendBatchOwned(second.id(), 0, SOURCE, 3, 4, List.of(line(4, Channel.ROLEPLAY)), 0, 0, true);
        complete(store, second);
        var before = store.progress(SOURCE);
        assertEquals(1, store.undoBatch(first.id()));
        assertEquals(0, store.undoBatch(first.id()));
        assertEquals(before, store.progress(SOURCE), "Undo preserves immutable cumulative observation checkpoint");
        assertEquals(1, store.removedRecords(SOURCE));
        assertEquals(List.of(1L, 4L), store.transcript(SOURCE, null, 0, 20).entries().stream().map(TranscriptLine::sourceLine).toList());
        assertEquals("UNDONE", store.batchSummary(first.id()).status());
        assertEquals(1, store.batchFiles(first.id()).getFirst().added());
        assertEquals(before, store.beginSource(new ImportSource(SOURCE, "again.log.gz", "gzip", 4)));
        assertEquals(2, store.transcript(SOURCE, null, 0, 20).total(), "Repeat import cannot silently recreate explicitly removed observations");
        var restarted = new SqliteJournalStore(directory.resolve("journal.sqlite"), new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()); restarted.initialize();
        assertEquals(1, restarted.removedRecords(null));
        assertEquals(2, restarted.transcript(null, null, 0, 20).total());
    }

    @Test void ownershipAndFileContributionRollBackWithCursorOnFailure() throws Exception {
        var store = store();
        store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 2));
        var plan = plan(SOURCE); start(store, plan);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("journal.sqlite")); var sql = connection.createStatement()) {
            sql.execute("CREATE TRIGGER synthetic_owner_failure BEFORE INSERT ON observation_owners BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        }
        assertThrows(SQLException.class, () -> store.appendBatchOwned(plan.id(), 0, SOURCE, 0, 2, List.of(line(1, Channel.UNKNOWN)), 1, 0, true));
        assertEquals(0, store.progress(SOURCE).processedLines());
        assertEquals(0, store.transcript(null, null, 0, 20).total());
        assertEquals(0, store.batchFiles(plan.id()).getFirst().afterLine());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("journal.sqlite")); var sql = connection.createStatement()) { sql.execute("DROP TRIGGER synthetic_owner_failure"); }
        store.appendBatchOwned(plan.id(), 0, SOURCE, 0, 2, List.of(line(1, Channel.UNKNOWN)), 1, 0, true);
        var file = store.batchFiles(plan.id()).getFirst();
        assertEquals(2, file.afterLine()); assertEquals(1, file.added()); assertEquals(1, file.excluded());
    }

    @Test void activeOrUndoneBatchCannotBeUndoneOrAppendedAgain() throws Exception {
        var store = store();
        store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 2));
        var plan = plan(SOURCE); start(store, plan);
        assertThrows(SQLException.class, () -> store.undoBatch(plan.id()));
        store.appendBatchOwned(plan.id(), 0, SOURCE, 0, 1, List.of(line(1, Channel.UNKNOWN)), 0, 0, false);
        store.updateFile(plan.id(), 0, "FAILED", SOURCE, "READ_FAILED"); store.finishBatch(plan.id(), "FAILED");
        assertEquals(1, store.undoBatch(plan.id()));
        assertThrows(SQLException.class, () -> store.finishBatch(plan.id(), "RUNNING"));
        assertThrows(SQLException.class, () -> store.appendBatchOwned(plan.id(), 0, SOURCE, 1, 2, List.of(line(2, Channel.UNKNOWN)), 0, 0, true));
        assertEquals(1, store.progress(SOURCE).processedLines());
    }

    @Test void previewIdentityPolicyAndStatusCannotBeBypassedInsideOwnedAppend() throws Exception {
        var store = store();
        store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 1));
        var other = plan("b".repeat(64)); store.createBatch(other); store.finishBatch(other.id(), "RUNNING");
        store.updateFile(other.id(), 0, "RUNNING", "b".repeat(64), null);
        assertThrows(SQLException.class, () -> store.appendBatchOwned(other.id(), 0, SOURCE, 0, 1, List.of(line(1, Channel.UNKNOWN)), 0, 0, true));
        var proposed = plan(SOURCE);
        var wrongPolicy = new BatchPlan(proposed.id(), proposed.root(), "c".repeat(64), proposed.createdAt(), proposed.reviewedNames(), proposed.files());
        start(store, wrongPolicy);
        assertThrows(SQLException.class, () -> store.appendBatchOwned(wrongPolicy.id(), 0, SOURCE, 0, 1, List.of(line(1, Channel.UNKNOWN)), 0, 0, true));
        assertEquals(0, store.progress(SOURCE).processedLines());
    }

    @Test void repeatedInitializationDoesNotInterruptCurrentWorkButRestartDoes() throws Exception {
        var store = store();
        var plan = plan(null); start(store, plan);
        store.initialize();
        assertEquals("RUNNING", store.batchSummary(plan.id()).status());
        var restarted = new SqliteJournalStore(directory.resolve("journal.sqlite"), new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()); restarted.initialize();
        assertEquals("INTERRUPTED", restarted.batchSummary(plan.id()).status());
        assertEquals("INTERRUPTED", restarted.batchFiles(plan.id()).getFirst().status());
        assertEquals(Set.of("Synthetic Name"), restarted.batchPlan(plan.id()).reviewedNames());
        assertEquals(plan.files().getFirst().path(), restarted.batchPlan(plan.id()).files().getFirst().path());
    }

    @Test void watcherIsOffByDefaultAndSavedIntentAndNamesSurviveRestart() throws Exception {
        var store = store();
        assertEquals(WatchSettings.defaults(), store.watchSettings());
        var settings = new WatchSettings(directory.toString(), true, Set.of("Synthetic Name", "Unicode λ"));
        store.saveWatchSettings(settings);
        var restarted = new SqliteJournalStore(directory.resolve("journal.sqlite"), new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()); restarted.initialize();
        assertEquals(settings, restarted.watchSettings());
        restarted.saveWatchSettings(new WatchSettings(directory.toString(), false, settings.reviewedNames()));
        assertFalse(restarted.watchSettings().enabled());
    }

    @Test void sourceAndChannelFiltersAreAppliedBeforeCountingAndPaging() throws Exception {
        var store = store();
        store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 3));
        store.appendBatch(SOURCE, 0, 3, List.of(line(1, Channel.UNKNOWN), line(2, Channel.ROLEPLAY), line(3, Channel.ROLEPLAY)), 0, 0, true);
        var page = store.transcript(SOURCE, "synthetic", Channel.ROLEPLAY, 1, 1);
        assertEquals(2, page.total()); assertEquals(3, page.entries().getFirst().sourceLine());
        assertEquals(1, store.transcript(null, null, Channel.UNKNOWN, 0, 20).total());
        assertThrows(IllegalArgumentException.class, () -> store.transcript(null, null, Channel.PRIVATE_MESSAGE, 0, 20));
    }

    @Test void migrationFromTwoPreservesExistingRowsAsUnownedAndCreatesVerifiedBackup() throws Exception {
        var original = store();
        original.save(new JournalSettings(true));
        original.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 1));
        original.appendBatch(SOURCE, 0, 1, List.of(line(1, Channel.UNKNOWN)), 0, 0, true);
        Path path = directory.resolve("journal.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement()) {
            for (String table : List.of("observation_owners", "removed_observations", "batch_files", "import_batches", "watch_settings", "watch_attempts")) sql.execute("DROP TABLE " + table);
            LegacyJournalFixtures.transcript(connection, 2);
        }
        var migrated = new SqliteJournalStore(path, new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()); migrated.initialize();
        assertTrue(migrated.load().compactLayout()); assertEquals(1, migrated.transcript(SOURCE, null, 0, 20).total());
        Path backup;
        try (var backups = Files.list(directory.resolve("backups"))) { var paths = backups.toList(); assertEquals(1, paths.size()); backup = paths.getFirst(); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + backup); var sql = connection.createStatement()) {
            try (var row = sql.executeQuery("PRAGMA user_version")) { assertEquals(2, row.getInt(1)); }
            try (var row = sql.executeQuery("SELECT count(*) FROM transcript_lines")) { assertEquals(1, row.getInt(1)); }
        }
        var duplicate = plan(SOURCE); start(migrated, duplicate);
        migrated.updateFile(duplicate.id(), 0, "DUPLICATE", SOURCE, null); migrated.finishBatch(duplicate.id(), "COMPLETE");
        assertEquals(0, migrated.undoBatch(duplicate.id()));
        assertEquals(1, migrated.transcript(SOURCE, null, 0, 20).total(), "Migration never retroactively assigns ownership");
    }

    @Test void batchPayloadAndUnsafeDiagnosticsCannotBeClaimedThroughMetadata() throws Exception {
        var plan = plan(SOURCE);
        assertThrows(IllegalArgumentException.class, () -> new BatchFile(plan.id(), 0, "/synthetic.log", "synthetic.log", SOURCE,
            "FAILED", "Exception: synthetic raw dialogue", 0, 0, 0, 0, 0));
        var store = store(); store.createBatch(plan);
        assertThrows(SQLException.class, () -> store.createBatch(plan));
        assertThrows(IllegalArgumentException.class, () -> store.batches(201));
        store.finishBatch(plan.id(), "RUNNING"); store.updateFile(plan.id(), 0, "FAILED", SOURCE, "READ_FAILED");
        assertThrows(SQLException.class, () -> store.finishBatch(plan.id(), "COMPLETE"), "Failed files must not be reported as an entirely successful batch");
        store.finishBatch(plan.id(), "FAILED");
    }

    @Test void failedWatchAttemptSurvivesRestartAndChangedStampPermitsAnotherAttempt() throws Exception {
        var store = store();
        String path = directory.resolve("synthetic.log").toString(), stamp = "b".repeat(64), changed = "c".repeat(64);
        assertFalse(store.watchAttempted(path, stamp));
        store.recordWatchAttempt(path, stamp, "FAILED");
        var restarted = new SqliteJournalStore(directory.resolve("journal.sqlite"), new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()); restarted.initialize();
        assertTrue(restarted.watchAttempted(path, stamp));
        assertFalse(restarted.watchAttempted(path, changed));
        restarted.recordWatchAttempt(path, changed, "ATTEMPTED");
        assertTrue(restarted.watchAttempted(path, changed));
        var again = new SqliteJournalStore(directory.resolve("journal.sqlite"), new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()); again.initialize();
        assertTrue(again.watchAttempted(path, changed), "Interrupted auto-attempt must require explicit retry or changed file");
        assertThrows(IllegalArgumentException.class, () -> again.recordWatchAttempt(path, changed, "Exception with synthetic raw text"));
    }

    @Test void undoRollbackPreservesAllRowsOwnershipAndStateUntilItCommits() throws Exception {
        var store = store(); store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 1));
        var plan = plan(SOURCE); start(store, plan);
        store.appendBatchOwned(plan.id(), 0, SOURCE, 0, 1, List.of(line(1, Channel.UNKNOWN)), 0, 0, true); complete(store, plan);
        assertEquals(1, store.unknownRecords(plan.id()));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("journal.sqlite")); var sql = connection.createStatement()) {
            sql.execute("CREATE TRIGGER synthetic_undo_failure BEFORE DELETE ON transcript_lines BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        }
        assertThrows(SQLException.class, () -> store.undoBatch(plan.id()));
        assertEquals("COMPLETE", store.batchSummary(plan.id()).status()); assertEquals(0, store.removedRecords(SOURCE));
        assertEquals(1, store.transcript(SOURCE, null, 0, 20).total()); assertEquals(1, store.unknownRecords(plan.id()));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("journal.sqlite")); var sql = connection.createStatement()) { sql.execute("DROP TRIGGER synthetic_undo_failure"); }
        assertEquals(1, store.undoBatch(plan.id())); assertEquals(0, store.unknownRecords(plan.id()));
        Path backup = store.createBackup(directory.resolve("backups/undone.sqlite"));
        var restored = new SqliteJournalStore(backup, new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()); restored.initialize();
        assertEquals(0, restored.transcript(SOURCE, null, 0, 20).total()); assertEquals(1, restored.removedRecords(SOURCE));
        assertEquals("UNDONE", restored.batchSummary(plan.id()).status());
    }

    @Test void concurrentUndoCannotRaceIntoARunningFileCommit() throws Exception {
        var store = store(); store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 1));
        var plan = plan(SOURCE); start(store, plan);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var append = workers.submit(() -> { release.await(); return store.appendBatchOwned(plan.id(), 0, SOURCE, 0, 1, List.of(line(1, Channel.UNKNOWN)), 0, 0, true); });
            var undo = workers.submit(() -> { release.await(); assertThrows(SQLException.class, () -> store.undoBatch(plan.id())); return true; });
            release.countDown();
            assertTrue(undo.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, append.get(5, java.util.concurrent.TimeUnit.SECONDS).importedLines());
        } finally { release.countDown(); }
        complete(store, plan); assertEquals(1, store.undoBatch(plan.id()));
    }
}
