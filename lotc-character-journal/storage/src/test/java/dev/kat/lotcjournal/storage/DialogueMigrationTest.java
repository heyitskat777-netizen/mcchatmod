package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import dev.kat.lotcjournal.parsing.DialogueTextNormalizer;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DialogueMigrationTest {
    private static final String ID = "e".repeat(64), POLICY = "0".repeat(64);
    @TempDir Path directory;
    private Path path() { return directory.resolve("journal.sqlite"); }
    private SqliteJournalStore store() { return new SqliteJournalStore(path(), new DialogueTextNormalizer()); }
    private TranscriptLine line(long number) { return new TranscriptLine(ID, number, "01:23:45", "Synthetic Speaker waits " + number + ".", Channel.UNKNOWN, null); }
    private BatchPlan start(SqliteJournalStore store) throws Exception {
        String id = UUID.randomUUID().toString();
        var plan = new BatchPlan(id, directory.toString(), POLICY, Instant.now().toString(), List.of(
            new BatchFile(id, 0, directory.resolve("synthetic.log").toString(), "synthetic.log", ID, "PENDING", null, 0, 0, 0, 0, 0)));
        store.createBatch(plan); store.finishBatch(id, "RUNNING"); store.updateFile(id, 0, "RUNNING", ID, null); return plan;
    }
    private void finish(SqliteJournalStore store, BatchPlan plan) throws Exception {
        store.updateFile(plan.id(), 0, "COMPLETE", ID, null); store.finishBatch(plan.id(), "COMPLETE");
    }
    private long scalar(Path path, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var query = connection.createStatement(); var row = query.executeQuery(sql)) {
            return row.getLong(1);
        }
    }
    @Test void schemaThreeCleanupPreservesOwnershipUndoProvenanceSettingsAndSourceBytes() throws Exception {
        var original = store(); original.initialize(); original.save(new JournalSettings(true));
        original.saveWatchSettings(new WatchSettings(directory.toString(), true, Set.of("Synthetic Speaker")));
        Path source = Files.writeString(directory.resolve("synthetic.log"), "synthetic original file bytes\n");
        original.beginSource(new ImportSource(ID, "synthetic.log", "plain", 6));
        original.appendBatch(ID, 0, 1, List.of(line(1)), 0, 0, false);
        var first = start(original);
        original.appendBatchOwned(first.id(), 0, ID, 1, 5, List.of(line(2), line(3), line(4), line(5)), 0, 0, false); finish(original, first);
        var undone = start(original); original.appendBatchOwned(undone.id(), 0, ID, 5, 6, List.of(line(6)), 0, 0, true);
        finish(original, undone); original.undoBatch(undone.id());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path())) {
            LegacyJournalFixtures.transcript(connection, 3);
            try (var statement = connection.createStatement()) {
                statement.execute("UPDATE transcript_lines SET text='[Quiet] Synthetic Speaker waits 2.' WHERE source_line=2");
                statement.execute("UPDATE transcript_lines SET text='[Replay Mod] recording',channel='SYSTEM' WHERE source_line=3");
                statement.execute("UPDATE transcript_lines SET text='' WHERE source_line=4");
                statement.execute("UPDATE transcript_lines SET text='[Quiet][Quiet][Quiet][Quiet][PM] PRIVATE_SENTINEL' WHERE source_line=5");
            }
        }
        var migrated = store(); migrated.initialize();
        assertEquals(9, scalar(path(), "PRAGMA user_version"));
        assertEquals(0, scalar(path(), "SELECT count(*) FROM pragma_table_info('transcript_lines') WHERE name='raw'"));
        assertEquals(List.of(1L, 2L), migrated.transcript(ID, "", 0, 20).entries().stream().map(TranscriptLine::sourceLine).toList());
        assertEquals("Synthetic Speaker waits 2.", migrated.transcript(ID, "", 0, 20).entries().getLast().text());
        var progress = migrated.progress(ID);
        assertEquals(6, progress.processedLines()); assertTrue(progress.complete());
        assertEquals(3, progress.importedLines()); assertEquals(1, progress.excludedLines()); assertEquals(2, progress.ignoredLines());
        var contribution = migrated.batchFiles(first.id()).getFirst();
        assertEquals(1, contribution.added()); assertEquals(1, contribution.excluded()); assertEquals(2, contribution.ignored());
        assertEquals(1, migrated.removedRecords(ID)); assertEquals("UNDONE", migrated.batchSummary(undone.id()).status());
        assertEquals(POLICY, migrated.sources().getFirst().policyFingerprint());
        assertTrue(migrated.load().compactLayout()); assertTrue(migrated.watchSettings().enabled());
        assertEquals("synthetic original file bytes\n", Files.readString(source));
        List<Path> backups;
        try (var files = Files.list(directory.resolve("backups"))) { backups = files.toList(); }
        assertEquals(1, backups.size()); assertEquals(3, scalar(backups.getFirst(), "PRAGMA user_version"));
        assertEquals(1, scalar(backups.getFirst(), "SELECT count(*) FROM pragma_table_info('transcript_lines') WHERE name='raw'"));
        assertEquals(5, scalar(backups.getFirst(), "SELECT count(*) FROM transcript_lines"));
        assertEquals(1, migrated.undoBatch(first.id())); assertEquals(1, migrated.transcript(ID, "", 0, 20).total());
        var restarted = store(); restarted.initialize(); assertEquals(2, restarted.removedRecords(ID));
        assertEquals(1, restarted.transcript(ID, "", 0, 20).total());
    }
    @Test void cleanupFailureRollsBackEarlierRowsSchemaAndAccounting() throws Exception {
        var original = store(); original.initialize(); original.beginSource(new ImportSource(ID, "synthetic.log", "plain", 2));
        original.appendBatch(ID, 0, 2, List.of(line(1), line(2)), 0, 0, true);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path())) {
            LegacyJournalFixtures.transcript(connection, 3);
            try (var statement = connection.createStatement()) { statement.execute("UPDATE transcript_lines SET text='[Quiet] '||text"); }
        }
        var count = new AtomicInteger(); var cleaner = new DialogueTextNormalizer();
        var failing = new SqliteJournalStore(path(), (text, channel) -> {
            if (count.incrementAndGet() == 2) throw new IllegalArgumentException("Synthetic cleanup failure");
            return cleaner.clean(text, channel);
        });
        assertThrows(IllegalArgumentException.class, failing::initialize);
        assertEquals(3, scalar(path(), "PRAGMA user_version"));
        assertEquals(2, scalar(path(), "SELECT count(*) FROM transcript_lines WHERE text LIKE '[Quiet] %'"));
        assertEquals(1, scalar(path(), "SELECT count(*) FROM pragma_table_info('transcript_lines') WHERE name='raw'"));
        assertEquals(2, scalar(path(), "SELECT imported_lines FROM capture_sources"));
        var recovered = store(); recovered.initialize(); assertEquals(2, recovered.transcript(null, "", 0, 20).total());
    }
    @Test void cleanupAcrossSeveralKeysetChunksDoesNotSkipRowsWhenDiscardingNoise() throws Exception {
        var original = store(); original.initialize(); original.beginSource(new ImportSource(ID, "synthetic.log", "plain", 250));
        var lines = new ArrayList<TranscriptLine>(); for (int index = 1; index <= 250; index++) lines.add(line(index));
        original.appendBatch(ID, 0, 250, lines, 0, 0, true);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path())) {
            LegacyJournalFixtures.transcript(connection, 3);
            try (var statement = connection.createStatement()) {
                statement.execute("UPDATE transcript_lines SET text=CASE WHEN source_line%2=0 THEN '[Replay Mod] noise' ELSE '[Quiet] '||text END");
            }
        }
        var migrated = store(); migrated.initialize();
        var page = migrated.transcript(null, "", 0, 200);
        assertEquals(125, page.total()); assertTrue(page.entries().stream().allMatch(line -> line.sourceLine() % 2 == 1));
        assertEquals(249, page.entries().getLast().sourceLine()); assertEquals(125, migrated.progress(ID).ignoredLines());
    }
    @Test void freshSchemaAndRepositoryRejectRedundantDecorationsAndNonDialogue() throws Exception {
        var store = store(); store.initialize(); store.beginSource(new ImportSource(ID, "synthetic.log", "plain", 1));
        assertEquals(0, scalar(path(), "SELECT count(*) FROM pragma_table_info('transcript_lines') WHERE name='raw'"));
        assertThrows(IllegalArgumentException.class, () -> store.appendBatch(ID, 0, 1,
            List.of(new TranscriptLine(ID, 1, null, "[Quiet] Synthetic Speaker waits.", Channel.UNKNOWN, null)), 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new TranscriptLine(ID, 1, null, "plugin notice", Channel.SYSTEM, null));
        assertEquals(0, store.progress(ID).processedLines());
    }
}
