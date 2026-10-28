package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import dev.kat.lotcjournal.parsing.DialogueTextNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.kat.lotcjournal.storage.PairedDatabaseFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class PairedDatabaseStoreTest {
    @TempDir Path directory;
    private Path archive() { return directory.resolve("archive.sqlite"); }
    private Path characters() { return directory.resolve("characters.sqlite"); }

    @Test void archiveAndCharactersArePhysicallySeparatedAndBoundToOneRollbackJournalPair() throws Exception {
        var store = seed(archive(), characters()); var repository = new SqliteIdentityRepository(archive(), characters());
        assertTrue(Files.isRegularFile(archive())); assertTrue(Files.isRegularFile(characters()));
        assertEquals(9, scalar(archive(), "PRAGMA user_version")); assertEquals(4, scalar(characters(), "PRAGMA user_version"));
        assertEquals("delete", text(archive(), "PRAGMA journal_mode")); assertEquals("delete", text(characters(), "PRAGMA journal_mode"));
        assertEquals(text(archive(), "SELECT pair_uuid FROM storage_pair"), text(characters(), "SELECT pair_uuid FROM storage_pair"));
        assertEquals(text(archive(), "SELECT generation FROM storage_pair"), text(characters(), "SELECT generation FROM storage_pair"));
        assertTrue(tables(archive()).containsAll(Set.of("transcript_lines", "capture_sources", "message_favorites", "live_events")));
        assertFalse(tables(characters()).contains("transcript_lines")); assertFalse(tables(characters()).contains("capture_sources"));
        for (String table : IdentitySchema.ORDER) { assertFalse(tables(archive()).contains(table)); assertTrue(tables(characters()).contains(table)); }
        assertEquals(3, store.transcript(null, null, 0, 20).total()); assertEquals(PERSONA_SECOND, repository.activePersona());
        assertEquals(SECOND, repository.resolveIdentity(FIRST));
    }

    @Test void nativeVersionEightIsCopiedToTheNewPairWithAllOriginalRecordsAndOriginalBytesPreserved() throws Exception {
        Path old = directory.resolve("journal.sqlite"), oldCharacters = directory.resolve("journal.sqlite.characters.sqlite");
        var original = seed(old, oldCharacters); var oldIdentities = new SqliteIdentityRepository(old, oldCharacters);
        var entries = original.transcript(null, null, 0, 20); var sources = original.sources();
        var identities = oldIdentities.identities(null); var personas = oldIdentities.personas(); var merges = oldIdentities.merges();
        var progress = original.progress(SOURCE); var files = original.batchFiles(BATCH);
        convertToNativeEight(old, oldCharacters); byte[] originalBytes = Files.readAllBytes(old);
        assertEquals(8, scalar(old, "PRAGMA user_version")); assertTrue(tables(old).contains("memory_assignments"));
        var migrated = open(archive(), characters()); var repository = new SqliteIdentityRepository(archive(), characters());
        assertArrayEquals(originalBytes, Files.readAllBytes(old), "The old native single-file database must remain an unchanged recovery source");
        assertEquals(entries, migrated.transcript(null, null, 0, 20)); assertEquals(sources, migrated.sources());
        assertEquals(identities, repository.identities(null)); assertEquals(personas, repository.personas()); assertEquals(merges, repository.merges());
        assertEquals(PERSONA_SECOND, repository.activePersona()); assertEquals(SECOND, repository.resolveIdentity(FIRST));
        assertEquals(new MemoryAssignment(ref(1), PERSONA_FIRST, FIRST, AttributionRole.REFERENCED), repository.assignment(ref(1), PERSONA_FIRST));
        assertEquals(new MemoryAssignment(ref(2), PERSONA_SECOND, SECOND, AttributionRole.SPEAKER), repository.assignment(ref(2), PERSONA_SECOND));
        assertEquals(progress, migrated.progress(SOURCE)); assertEquals(files, migrated.batchFiles(BATCH));
        assertTrue(migrated.load().compactLayout()); assertTrue(migrated.livePaused()); assertTrue(migrated.watchSettings().enabled());
        assertEquals(1, scalar(archive(), "SELECT count(*) FROM live_events"));
        assertEquals(2, scalar(characters(), "SELECT count(*) FROM identity_decisions"));
        open(archive(), characters()); assertArrayEquals(originalBytes, Files.readAllBytes(old));
        assertEquals(1, migrated.undoBatch(BATCH)); assertNull(repository.assignment(ref(2), PERSONA_SECOND));
        assertNotNull(repository.assignment(ref(1), PERSONA_FIRST)); assertEquals(progress, migrated.progress(SOURCE));
    }

    @Test void nativeVersionEightBootstrapIncludesCommittedWalPagesWithoutChangingTheOriginalMainOrWal() throws Exception {
        Path old = directory.resolve("journal.sqlite"), oldCharacters = directory.resolve("journal.sqlite.characters.sqlite");
        seed(old, oldCharacters); convertToNativeEight(old, oldCharacters);
        // Keep the old-profile connection alive so its committed WAL pages cannot be hidden by last-close checkpointing.
        try (var oldConnection = DriverManager.getConnection("jdbc:sqlite:" + old); var sql = oldConnection.createStatement()) {
            try (var mode = sql.executeQuery("PRAGMA journal_mode=WAL")) { assertTrue(mode.next()); assertEquals("wal", mode.getString(1)); }
            sql.execute("PRAGMA wal_autocheckpoint=0");
            oldConnection.setAutoCommit(false);
            sql.executeUpdate("UPDATE transcript_lines SET text='A latest committed WAL observation.' WHERE source_id='" + SOURCE + "' AND source_line=1");
            sql.executeUpdate("UPDATE character_identities SET notes='Latest committed WAL note' WHERE id='" + FIRST + "'");
            sql.executeUpdate("UPDATE journal_settings SET value='false' WHERE key='compact_layout'"); oldConnection.commit();
            Path wal = old.resolveSibling(old.getFileName() + "-wal"); assertTrue(Files.size(wal) > 0);
            byte[] originalMain = Files.readAllBytes(old), originalWal = Files.readAllBytes(wal);
            var migrated = open(archive(), characters()); var identities = new SqliteIdentityRepository(archive(), characters());
            assertEquals("A latest committed WAL observation.", migrated.transcript(SOURCE, null, 0, 20).entries().getFirst().text());
            assertEquals("Latest committed WAL note", identities.identities("Synthetic First").getFirst().notes());
            assertFalse(migrated.load().compactLayout());
            assertArrayEquals(originalMain, Files.readAllBytes(old)); assertArrayEquals(originalWal, Files.readAllBytes(wal));
            assertEquals(PERSONA_SECOND, identities.activePersona()); assertNotNull(identities.assignment(ref(2), PERSONA_SECOND));
            assertEquals(Set.of(ref(1), ref(2)), migrated.transcript(SOURCE, null, 0, 20).favorites());
        }
    }

    @Test void missingOrMismatchedCharacterMemberFailsClosedWithoutCreatingAnEmptyReplacement() throws Exception {
        seed(archive(), characters()); byte[] originalArchive = Files.readAllBytes(archive());
        Path saved = directory.resolve("saved-characters.sqlite"); Files.move(characters(), saved);
        assertThrows(Exception.class, () -> open(archive(), characters()));
        assertFalse(Files.exists(characters())); assertArrayEquals(originalArchive, Files.readAllBytes(archive()));
        Files.move(saved, characters());
        Path otherArchive = directory.resolve("other.sqlite"), otherCharacters = directory.resolve("other-characters.sqlite");
        open(otherArchive, otherCharacters); byte[] mismatched = Files.readAllBytes(otherCharacters);
        Files.move(characters(), saved); Files.copy(otherCharacters, characters());
        assertThrows(Exception.class, () -> open(archive(), characters()));
        assertArrayEquals(originalArchive, Files.readAllBytes(archive())); assertArrayEquals(mismatched, Files.readAllBytes(characters()));
        Files.delete(characters()); Files.move(saved, characters());
        assertEquals(3, open(archive(), characters()).transcript(null, null, 0, 20).total());
    }

    @Test void unrelatedCharacterTargetIsNotAdoptedOrReplaced() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + characters()); var s = c.createStatement()) {
            s.execute("CREATE TABLE unrelated(value TEXT)"); s.execute("INSERT INTO unrelated VALUES('synthetic preserved')"); s.execute("PRAGMA user_version=1");
        }
        byte[] before = Files.readAllBytes(characters());
        assertThrows(Exception.class, () -> open(archive(), characters()));
        assertArrayEquals(before, Files.readAllBytes(characters()));
        assertEquals("synthetic preserved", text(characters(), "SELECT value FROM unrelated"));
    }

    @Test void pairedBackupRestoresCharacterDecisionsAndArchiveTogetherAndRejectsSwappedGenerations() throws Exception {
        var store = seed(archive(), characters()); var repository = new SqliteIdentityRepository(archive(), characters());
        var expected = store.transcript(null, null, 0, 20); var expectedIdentities = repository.identities(null);
        Path backup = store.createBackup(directory.resolve("backups/snapshot.sqlite"));
        Path companion = backup.resolveSibling(backup.getFileName() + ".characters.sqlite");
        assertTrue(Files.isRegularFile(companion)); assertTrue(Files.isRegularFile(backup.resolveSibling(backup.getFileName() + ".pair.json")));
        store.undoBatch(BATCH); repository.selectPersona(PERSONA_FIRST);
        var restored = new SqliteJournalStore(backup, new DialogueTextNormalizer()); restored.initialize();
        var restoredIdentities = new SqliteIdentityRepository(backup);
        assertEquals(expected, restored.transcript(null, null, 0, 20)); assertEquals(expectedIdentities, restoredIdentities.identities(null));
        assertEquals(PERSONA_SECOND, restoredIdentities.activePersona()); assertNotNull(restoredIdentities.assignment(ref(2), PERSONA_SECOND));
        assertEquals(SECOND, restoredIdentities.resolveIdentity(FIRST));
        Path later = store.createBackup(directory.resolve("backups/later.sqlite"));
        Path laterCharacters = later.resolveSibling(later.getFileName() + ".characters.sqlite");
        Path original = companion.resolveSibling("original-companion.sqlite"); Files.move(companion, original); Files.copy(laterCharacters, companion);
        byte[] beforeArchive = Files.readAllBytes(backup), beforeCharacters = Files.readAllBytes(companion);
        assertThrows(Exception.class, () -> new SqliteJournalStore(backup, new DialogueTextNormalizer()).initialize());
        assertArrayEquals(beforeArchive, Files.readAllBytes(backup)); assertArrayEquals(beforeCharacters, Files.readAllBytes(companion));
    }

    @Test void failedCrossFileUndoRollsBackCharacterAssignmentsArchiveRowsFavoritesAndOwnershipAsOneUnit() throws Exception {
        var store = seed(archive(), characters()); var repository = new SqliteIdentityRepository(archive(), characters());
        var before = store.transcript(null, null, 0, 20); var progress = store.progress(SOURCE);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + archive()); var s = c.createStatement()) {
            s.execute("CREATE TRIGGER synthetic_split_undo_failure BEFORE DELETE ON transcript_lines BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        }
        try {
            assertThrows(SQLException.class, () -> store.undoBatch(BATCH));
            assertEquals(before, store.transcript(null, null, 0, 20)); assertEquals(progress, store.progress(SOURCE));
            assertNotNull(repository.assignment(ref(2), PERSONA_SECOND)); assertEquals("COMPLETE", store.batchSummary(BATCH).status());
            assertEquals(1, scalar(archive(), "SELECT count(*) FROM observation_owners")); assertEquals(0, store.removedRecords(SOURCE));
        } finally {
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + archive()); var s = c.createStatement()) { s.execute("DROP TRIGGER synthetic_split_undo_failure"); }
        }
        assertEquals(1, store.undoBatch(BATCH)); assertNull(repository.assignment(ref(2), PERSONA_SECOND));
        assertEquals(Set.of(ref(1)), store.transcript(SOURCE, null, 0, 20).favorites());
        assertEquals(2, scalar(characters(), "SELECT count(*) FROM identity_decisions"));
    }
}
