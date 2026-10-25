package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import dev.kat.lotcjournal.parsing.DialogueTextNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RoleplayPolicyMigrationTest {
    private static final String SOURCE = "d".repeat(64), POLICY = "a".repeat(64);
    private static final String NOTICE = "You are now Roleplaying as Synthetic Persona.";
    @TempDir Path directory;
    private Path path() { return directory.resolve("journal.sqlite"); }

    /** The previous cleaned schema retained these newly recognized notices as UNKNOWN. */
    private SqliteJournalStore oldPolicyStore() throws Exception {
        var store = new SqliteJournalStore(path(), (text, channel) -> new DialogueCleanup(text, DialogueCleanup.Disposition.DIALOGUE));
        store.initialize();
        return store;
    }
    private void markVersionFour() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path()); var sql = connection.createStatement()) {
            sql.execute("PRAGMA user_version=4");
        }
    }
    private TranscriptLine line(long coordinate, String text, Channel channel) {
        return new TranscriptLine(SOURCE, coordinate, "23:59:59", text, channel, null);
    }
    private BatchPlan start(SqliteJournalStore store) throws Exception {
        String id = UUID.randomUUID().toString();
        var plan = new BatchPlan(id, directory.toString(), POLICY, Instant.now().toString(), Set.of("Synthetic Speaker"), List.of(
            new BatchFile(id, 0, directory.resolve("synthetic.log").toString(), "synthetic.log", SOURCE, "PENDING", null, 0, 0, 0, 0, 0)));
        store.createBatch(plan); store.finishBatch(id, "RUNNING"); store.updateFile(id, 0, "RUNNING", SOURCE, null);
        return plan;
    }
    private void finish(SqliteJournalStore store, BatchPlan plan) throws Exception {
        store.updateFile(plan.id(), 0, "COMPLETE", SOURCE, null); store.finishBatch(plan.id(), "COMPLETE");
    }
    private long scalar(Path file, String query) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var sql = connection.createStatement(); var row = sql.executeQuery(query)) {
            return row.getLong(1);
        }
    }

    @Test void versionFourRefreshCleansNewNoiseWithoutRawColumnOrInferredRoleplay() throws Exception {
        Path originalFile = Files.writeString(directory.resolve("synthetic.log"), "synthetic unchanged source bytes\n");
        var original = oldPolicyStore();
        original.save(new JournalSettings(true));
        original.saveWatchSettings(new WatchSettings(directory.toString(), true, Set.of("Synthetic Speaker")));
        original.recordWatchAttempt(originalFile.toString(), "b".repeat(64), "FAILED");
        original.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 8, POLICY));
        original.appendBatch(SOURCE, 0, 1, List.of(line(1, "Synthetic Speaker waits quietly.", Channel.UNKNOWN)), 0, 0, false);
        var kept = start(original);
        original.appendBatchOwned(kept.id(), 0, SOURCE, 1, 7, List.of(
            line(2, NOTICE, Channel.UNKNOWN), line(3, "The key did not fit!", Channel.UNKNOWN),
            line(4, "Synthetic Speaker reads Applicable regions: synthetic_region aloud.", Channel.UNKNOWN),
            line(5, "The key did not fit!", Channel.ROLEPLAY), line(6, "[a distant movement] Synthetic Speaker waits.", Channel.UNKNOWN),
            line(7, "Synthetic Persona is a Persona of ExampleAccount.", Channel.UNKNOWN)), 0, 0, false);
        finish(original, kept);
        var undone = start(original);
        original.appendBatchOwned(undone.id(), 0, SOURCE, 7, 8, List.of(line(8, "Synthetic undone observation.", Channel.UNKNOWN)), 0, 0, true);
        finish(original, undone); original.undoBatch(undone.id());
        markVersionFour();
        assertEquals(0, scalar(path(), "SELECT count(*) FROM pragma_table_info('transcript_lines') WHERE name='raw'"));

        var migrated = new SqliteJournalStore(path(), new DialogueTextNormalizer()); migrated.initialize();
        assertEquals(5, scalar(path(), "PRAGMA user_version"));
        assertEquals(0, scalar(path(), "SELECT count(*) FROM pragma_table_info('transcript_lines') WHERE name='raw'"));
        var entries = migrated.transcript(SOURCE, null, 0, 20).entries();
        assertEquals(List.of(1L, 4L, 5L, 6L), entries.stream().map(TranscriptLine::sourceLine).toList());
        assertEquals(Channel.UNKNOWN, entries.get(1).channel(), "Lost IC wrappers cannot be reconstructed from a speaker-shaped payload");
        assertNull(entries.get(1).observedSpeaker());
        assertEquals(Channel.ROLEPLAY, entries.get(2).channel(), "Explicit old channel evidence must preserve real dialogue resembling a notice");
        assertEquals("The key did not fit!", entries.get(2).text());
        assertEquals("23:59:59", entries.get(1).clock());
        var progress = migrated.progress(SOURCE);
        assertEquals(8, progress.processedLines()); assertEquals(5, progress.importedLines());
        assertEquals(3, progress.ignoredLines()); assertEquals(0, progress.excludedLines()); assertTrue(progress.complete());
        assertEquals(SOURCE, migrated.sources().getFirst().id()); assertEquals(POLICY, migrated.sources().getFirst().policyFingerprint());
        var contribution = migrated.batchFiles(kept.id()).getFirst();
        assertEquals(3, contribution.added()); assertEquals(3, contribution.ignored()); assertEquals(1, contribution.beforeLine()); assertEquals(7, contribution.afterLine());
        assertEquals(1, migrated.removedRecords(SOURCE)); assertEquals("UNDONE", migrated.batchSummary(undone.id()).status());
        assertTrue(migrated.load().compactLayout()); assertTrue(migrated.watchSettings().enabled());
        assertTrue(migrated.watchAttempted(originalFile.toString(), "b".repeat(64)));
        assertEquals("synthetic unchanged source bytes\n", Files.readString(originalFile));
        List<Path> backups;
        try (var files = Files.list(directory.resolve("backups"))) { backups = files.toList(); }
        assertEquals(1, backups.size()); assertEquals(4, scalar(backups.getFirst(), "PRAGMA user_version"));
        assertEquals(7, scalar(backups.getFirst(), "SELECT count(*) FROM transcript_lines"));
        assertEquals(0, scalar(backups.getFirst(), "SELECT count(*) FROM pragma_table_info('transcript_lines') WHERE name='raw'"));
        migrated.initialize();
        try (var files = Files.list(directory.resolve("backups"))) { assertEquals(1, files.count(), "Policy refresh is one-time"); }
        assertEquals(3, migrated.undoBatch(kept.id())); assertEquals(1, migrated.transcript(SOURCE, null, 0, 20).total());
        var restarted = new SqliteJournalStore(path(), new DialogueTextNormalizer()); restarted.initialize();
        assertEquals(4, restarted.removedRecords(SOURCE)); assertEquals(progress, restarted.progress(SOURCE));
    }

    @Test void versionFourCleanupFailureRollsBackNoiseRemovalAndKeepsOriginalSchemaAndBytes() throws Exception {
        var original = oldPolicyStore(); original.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 2, POLICY));
        original.appendBatch(SOURCE, 0, 2, List.of(line(1, NOTICE, Channel.UNKNOWN), line(2, "Synthetic Speaker waits.", Channel.UNKNOWN)), 0, 0, true);
        markVersionFour(); byte[] before = Files.readAllBytes(path());
        var calls = new AtomicInteger(); var normalizer = new DialogueTextNormalizer();
        var failing = new SqliteJournalStore(path(), (text, channel) -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("Synthetic policy refresh failure");
            return normalizer.clean(text, channel);
        });
        assertThrows(IllegalStateException.class, failing::initialize);
        assertArrayEquals(before, Files.readAllBytes(path())); assertEquals(4, scalar(path(), "PRAGMA user_version"));
        assertEquals(2, scalar(path(), "SELECT count(*) FROM transcript_lines"));
        assertEquals(2, scalar(path(), "SELECT imported_lines FROM capture_sources"));
        assertEquals(0, scalar(path(), "SELECT ignored_lines FROM capture_sources"));
        var recovered = new SqliteJournalStore(path(), normalizer); recovered.initialize();
        assertEquals(1, recovered.transcript(SOURCE, null, 0, 20).total());
    }

    @Test void currentVersionStillRejectsNoncanonicalPayloadWithoutRewritingIt() throws Exception {
        var malformed = oldPolicyStore(); malformed.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 1, POLICY));
        malformed.appendBatch(SOURCE, 0, 1, List.of(line(1, NOTICE, Channel.UNKNOWN)), 0, 0, true);
        byte[] original = Files.readAllBytes(path());
        assertThrows(java.sql.SQLException.class, () -> new SqliteJournalStore(path(), new DialogueTextNormalizer()).initialize());
        assertArrayEquals(original, Files.readAllBytes(path()));
        assertEquals(5, scalar(path(), "PRAGMA user_version"));
        assertFalse(Files.exists(directory.resolve("backups")), "Only an actual old-version upgrade creates a migration snapshot");
    }
}
