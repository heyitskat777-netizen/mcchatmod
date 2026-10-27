package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.BatchFile;
import dev.kat.lotcjournal.domain.BatchPlan;
import dev.kat.lotcjournal.domain.Channel;
import dev.kat.lotcjournal.domain.ImportSource;
import dev.kat.lotcjournal.domain.JournalSettings;
import dev.kat.lotcjournal.domain.MessageReference;
import dev.kat.lotcjournal.domain.TranscriptLine;
import dev.kat.lotcjournal.parsing.DialogueTextNormalizer;
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

class FavoritesStoreTest {
    private static final String SOURCE = "a".repeat(64), OTHER = "b".repeat(64), POLICY = "0".repeat(64);
    @TempDir Path directory;

    private Path path() { return directory.resolve("journal.sqlite"); }
    private SqliteJournalStore open(Path file) throws Exception {
        var store = new SqliteJournalStore(file, new DialogueTextNormalizer());
        store.initialize();
        return store;
    }
    private SqliteJournalStore store() throws Exception { return open(path()); }
    private static MessageReference ref(String source, long line) { return new MessageReference(source, line); }
    private static TranscriptLine line(String source, long coordinate, String text, Channel channel) {
        return new TranscriptLine(source, coordinate, "23:59:58", text, channel, "Synthetic Visitor");
    }
    private static void source(SqliteJournalStore store, String id, List<TranscriptLine> entries) throws Exception {
        store.beginSource(new ImportSource(id, "synthetic-" + id.charAt(0) + ".log", "plain", entries.size(), POLICY));
        store.appendBatch(id, 0, entries.size(), entries, 0, 0, true);
    }
    private BatchPlan startBatch(SqliteJournalStore store) throws Exception {
        String id = UUID.randomUUID().toString();
        var plan = new BatchPlan(id, directory.toString(), POLICY, Instant.now().toString(), Set.of(), List.of(
            new BatchFile(id, 0, directory.resolve("synthetic.log").toString(), "synthetic.log", SOURCE,
                "PENDING", null, 0, 0, 0, 0, 0)));
        store.createBatch(plan);
        store.finishBatch(id, "RUNNING");
        store.updateFile(id, 0, "RUNNING", SOURCE, null);
        return plan;
    }
    private static void finishBatch(SqliteJournalStore store, BatchPlan batch) throws Exception {
        store.updateFile(batch.id(), 0, "COMPLETE", SOURCE, null);
        store.finishBatch(batch.id(), "COMPLETE");
    }
    private static long scalar(Path file, String query) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement(); var row = statement.executeQuery(query)) {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }
    private static void sql(Path file, String command) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement()) { statement.execute(command); }
    }

    @Test void favoritesUseCoordinatesNotWordingAndPersistIdempotentDesiredState() throws Exception {
        var store = store();
        var first = line(SOURCE, 1, "Synthetic Visitor waves.", Channel.ROLEPLAY);
        var repeated = line(SOURCE, 2, first.text(), Channel.ROLEPLAY);
        var otherSource = line(OTHER, 1, first.text(), Channel.ROLEPLAY);
        source(store, SOURCE, List.of(first, repeated)); source(store, OTHER, List.of(otherSource));
        var progress = store.progress(SOURCE); var sources = store.sources();
        store.setFavorite(ref(SOURCE, 1), true);
        store.setFavorite(ref(SOURCE, 1), true);
        store.setFavorite(ref(OTHER, 1), true);
        var page = store.transcript(null, null, 0, 20);
        assertEquals(List.of(first, repeated, otherSource), page.entries());
        assertEquals(Set.of(ref(SOURCE, 1), ref(OTHER, 1)), page.favorites());
        assertEquals(2, scalar(path(), "SELECT count(*) FROM message_favorites"));
        assertEquals(progress, store.progress(SOURCE)); assertEquals(sources, store.sources());

        var restarted = open(path());
        assertEquals(page, restarted.transcript(null, null, 0, 20));
        restarted.setFavorite(ref(SOURCE, 1), false);
        restarted.setFavorite(ref(SOURCE, 1), false);
        restarted.setFavorite(ref(SOURCE, 2), false);
        var secondRestart = open(path());
        assertEquals(Set.of(ref(OTHER, 1)), secondRestart.transcript(null, null, 0, 20).favorites());
        assertEquals(List.of(first, repeated, otherSource), secondRestart.transcript(null, null, 0, 20).entries());
    }

    @Test void missingCoordinatesFailForBothStatesWithoutCreatingSourcesOrMessages() throws Exception {
        var store = store();
        source(store, SOURCE, List.of(line(SOURCE, 1, "Synthetic Visitor waits.", Channel.UNKNOWN)));
        for (var missing : List.of(ref(SOURCE, 2), ref(OTHER, 1))) {
            assertThrows(SQLException.class, () -> store.setFavorite(missing, true));
            assertThrows(SQLException.class, () -> store.setFavorite(missing, false));
        }
        assertEquals(1, store.sources().size());
        assertEquals(1, store.transcript(null, null, 0, 20).total());
        assertEquals(0, scalar(path(), "SELECT count(*) FROM message_favorites"));
        assertEquals(1, store.progress(SOURCE).processedLines());
    }

    @Test void favoriteFiltersIntersectBeforeCountingAndPagingAndSearchIsLiteral() throws Exception {
        var store = store();
        String literal = "Synthetic Visitor marks 100%_\\ on a map.";
        source(store, SOURCE, List.of(line(SOURCE, 1, literal, Channel.ROLEPLAY),
            line(SOURCE, 2, literal, Channel.UNKNOWN), line(SOURCE, 3, literal, Channel.ROLEPLAY),
            line(SOURCE, 4, "Synthetic Visitor points elsewhere.", Channel.ROLEPLAY),
            line(SOURCE, 5, literal, Channel.ROLEPLAY)));
        source(store, OTHER, List.of(line(OTHER, 1, literal, Channel.ROLEPLAY)));
        for (long coordinate : List.of(1L, 2L, 3L, 4L)) store.setFavorite(ref(SOURCE, coordinate), true);
        store.setFavorite(ref(OTHER, 1), true);
        var page = store.transcript(SOURCE, "%_\\", Channel.ROLEPLAY, true, 1, 1);
        assertEquals(2, page.total()); assertEquals(1, page.offset()); assertEquals(1, page.limit());
        assertEquals(3, page.entries().getFirst().sourceLine());
        assertEquals(Set.of(ref(SOURCE, 3)), page.favorites(), "Only displayed rows contribute favorite state");
        assertEquals(3, store.transcript(SOURCE, "%_\\", Channel.ROLEPLAY, false, 0, 20).total());
        assertEquals(1, store.transcript(SOURCE, "%_\\", Channel.UNKNOWN, true, 0, 20).total());
        assertEquals(3, store.transcript(null, "%_\\", Channel.ROLEPLAY, true, 0, 20).total());
        assertEquals(0, store.transcript(null, "' OR 1=1 --", null, true, 0, 20).total());
        var pastEnd = store.transcript(SOURCE, "%_\\", Channel.ROLEPLAY, true, 99, 20);
        assertEquals(2, pastEnd.total()); assertTrue(pastEnd.entries().isEmpty()); assertTrue(pastEnd.favorites().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.transcript(null, "", null, true, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> store.transcript(null, "", null, true, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> store.transcript(null, "", null, true, 0, 201));
        assertThrows(IllegalArgumentException.class, () -> store.transcript(null, "x".repeat(257), null, true, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> store.transcript(null, "", Channel.PRIVATE_MESSAGE, true, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> store.transcript(null, "", Channel.OOC, true, 0, 20));
    }

    @Test void favoriteBackupRestoresTheCapturedStateIndependentlyOfLaterEdits() throws Exception {
        var store = store();
        source(store, SOURCE, List.of(line(SOURCE, 1, "Synthetic Visitor nods.", Channel.ROLEPLAY),
            line(SOURCE, 2, "Synthetic Visitor bows.", Channel.UNKNOWN)));
        store.setFavorite(ref(SOURCE, 1), true);
        var expected = store.transcript(null, null, 0, 20);
        var progress = store.progress(SOURCE);
        Path backup = store.createBackup(directory.resolve("backups/favorited.sqlite"));
        store.setFavorite(ref(SOURCE, 1), false); store.setFavorite(ref(SOURCE, 2), true);
        var restored = open(backup);
        assertEquals(expected, restored.transcript(null, null, 0, 20));
        assertEquals(progress, restored.progress(SOURCE));
        restored.setFavorite(ref(SOURCE, 1), false);
        assertEquals(Set.of(ref(SOURCE, 2)), store.transcript(null, null, 0, 20).favorites());
        assertEquals(9, scalar(backup, "PRAGMA user_version"));
    }

    @Test void undoCascadesOnlyItsOwnedFavoritesAndRemovedCoordinatesCannotBeFavoritedAgain() throws Exception {
        var store = store();
        store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 3, POLICY));
        store.appendBatch(SOURCE, 0, 1, List.of(line(SOURCE, 1, "Synthetic Visitor waits.", Channel.UNKNOWN)), 0, 0, false);
        var first = startBatch(store);
        store.appendBatchOwned(first.id(), 0, SOURCE, 1, 2,
            List.of(line(SOURCE, 2, "Synthetic Visitor waits.", Channel.ROLEPLAY)), 0, 0, false);
        finishBatch(store, first);
        var second = startBatch(store);
        store.appendBatchOwned(second.id(), 0, SOURCE, 2, 3,
            List.of(line(SOURCE, 3, "Synthetic Visitor waits.", Channel.ROLEPLAY)), 0, 0, true);
        finishBatch(store, second);
        for (long coordinate : List.of(1L, 2L, 3L)) store.setFavorite(ref(SOURCE, coordinate), true);
        var progress = store.progress(SOURCE);
        assertEquals(1, store.undoBatch(first.id()));
        assertEquals(0, store.undoBatch(first.id()));
        var restarted = open(path());
        assertEquals(Set.of(ref(SOURCE, 1), ref(SOURCE, 3)), restarted.transcript(null, null, 0, 20).favorites());
        assertEquals(2, restarted.transcript(null, null, null, true, 0, 20).total());
        assertEquals(progress, restarted.progress(SOURCE)); assertEquals(1, restarted.removedRecords(SOURCE));
        assertThrows(SQLException.class, () -> restarted.setFavorite(ref(SOURCE, 2), true));
        assertThrows(SQLException.class, () -> restarted.setFavorite(ref(SOURCE, 2), false));
        assertEquals(2, scalar(path(), "SELECT count(*) FROM message_favorites"));
        assertEquals(0, scalar(path(), "SELECT count(*) FROM message_favorites f LEFT JOIN transcript_lines t ON t.source_id=f.source_id AND t.source_line=f.source_line WHERE t.source_id IS NULL"));
    }

    @Test void failedUndoRollsBackFavoriteRemovalTogetherWithObservationsAndOwnership() throws Exception {
        var store = store();
        store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 1, POLICY));
        var batch = startBatch(store);
        store.appendBatchOwned(batch.id(), 0, SOURCE, 0, 1,
            List.of(line(SOURCE, 1, "Synthetic Visitor waves.", Channel.ROLEPLAY)), 0, 0, true);
        finishBatch(store, batch); store.setFavorite(ref(SOURCE, 1), true);
        sql(path(), "CREATE TRIGGER synthetic_favorite_undo_failure BEFORE DELETE ON message_favorites BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        try {
            assertThrows(SQLException.class, () -> store.undoBatch(batch.id()));
            assertEquals(Set.of(ref(SOURCE, 1)), store.transcript(null, null, 0, 20).favorites());
            assertEquals(1, store.transcript(null, null, 0, 20).total());
            assertEquals("COMPLETE", store.batchSummary(batch.id()).status());
            assertEquals(0, store.removedRecords(SOURCE));
            assertEquals(1, scalar(path(), "SELECT count(*) FROM observation_owners"));
        } finally { sql(path(), "DROP TRIGGER synthetic_favorite_undo_failure"); }
        assertEquals(1, store.undoBatch(batch.id()));
        assertEquals(0, scalar(path(), "SELECT count(*) FROM message_favorites"));
    }

    @Test void realVersionFiveShapeMigratesAdditivelyWithVerifiedPreFavoritesBackup() throws Exception {
        var old = store(); old.save(new JournalSettings(true));
        old.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 2, POLICY));
        old.appendBatch(SOURCE, 0, 1, List.of(line(SOURCE, 1, "Synthetic Visitor waits.", Channel.UNKNOWN)), 0, 0, false);
        var batch = startBatch(old);
        old.appendBatchOwned(batch.id(), 0, SOURCE, 1, 2,
            List.of(line(SOURCE, 2, "The key did not fit!", Channel.ROLEPLAY)), 0, 0, true);
        finishBatch(old, batch);
        var entries = old.transcript(null, null, 0, 20).entries();
        var progress = old.progress(SOURCE); var sources = old.sources(); var contribution = old.batchFiles(batch.id());
        // Reconstruct the complete genuine v5 schema, not a v6 table set with a changed version number.
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+path())) {LegacyJournalFixtures.removeLaterSchemas(connection);}
        sql(path(), "DROP TABLE message_favorites"); sql(path(), "PRAGMA user_version=5");
        var migrated = open(path());
        assertEquals(9, scalar(path(), "PRAGMA user_version"));
        assertEquals(entries, migrated.transcript(null, null, 0, 20).entries());
        assertTrue(migrated.transcript(null, null, 0, 20).favorites().isEmpty());
        assertEquals(progress, migrated.progress(SOURCE)); assertEquals(sources, migrated.sources());
        assertEquals(contribution, migrated.batchFiles(batch.id())); assertTrue(migrated.load().compactLayout());
        List<Path> backups;
        try (var files = Files.list(directory.resolve("backups"))) { backups = files.toList(); }
        assertEquals(1, backups.size());
        Path backup = backups.getFirst();
        assertEquals(5, scalar(backup, "PRAGMA user_version"));
        assertEquals(0, scalar(backup, "SELECT count(*) FROM sqlite_master WHERE name='message_favorites'"));
        assertEquals(2, scalar(backup, "SELECT count(*) FROM transcript_lines"));
        assertEquals(1, scalar(backup, "SELECT count(*) FROM observation_owners"));
        assertEquals(0, scalar(backup, "SELECT count(*) FROM pragma_table_info('transcript_lines') WHERE name='raw'"));
        migrated.setFavorite(ref(SOURCE, 2), true);
        var restarted = open(path()); restarted.initialize();
        assertEquals(Set.of(ref(SOURCE, 2)), restarted.transcript(null, null, 0, 20).favorites());
        try (var files = Files.list(directory.resolve("backups"))) { assertEquals(1, files.count(), "Schema upgrade runs once"); }
        assertEquals(1, restarted.undoBatch(batch.id()));
        assertTrue(restarted.transcript(null, null, 0, 20).favorites().isEmpty());
        assertEquals(entries.getFirst(), restarted.transcript(null, null, 0, 20).entries().getFirst());
        assertEquals(progress, restarted.progress(SOURCE));
    }
}
