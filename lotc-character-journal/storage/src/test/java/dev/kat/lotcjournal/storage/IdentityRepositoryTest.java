package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import dev.kat.lotcjournal.parsing.DialogueTextNormalizer;
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

class IdentityRepositoryTest {
    private static final String SOURCE = "e".repeat(64), POLICY = "0".repeat(64);
    @TempDir Path directory;
    private Path path() { return directory.resolve("journal.sqlite"); }
    private Path charactersPath() { return directory.resolve("journal.sqlite.characters.sqlite"); }
    private SqliteJournalStore archive() throws Exception {
        var archive = new SqliteJournalStore(path(), new DialogueTextNormalizer()); archive.initialize(); return archive;
    }
    private SqliteIdentityRepository identities() { return new SqliteIdentityRepository(path()); }
    private static String id() { return UUID.randomUUID().toString(); }
    private static CharacterIdentity identity(String id, String name) { return new CharacterIdentity(id, name, "Synthetic note", Set.of("Shared Alias"), "SyntheticAccount"); }
    private static MessageReference ref(long line) { return new MessageReference(SOURCE, line); }
    private static TranscriptLine line(long coordinate, Channel channel) {
        return new TranscriptLine(SOURCE, coordinate, "12:34:56", "Synthetic Visitor repeats 100%_\\.", channel, null);
    }
    private static void messages(SqliteJournalStore archive) throws Exception {
        archive.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 3, POLICY));
        archive.appendBatch(SOURCE, 0, 3, List.of(line(1, Channel.ROLEPLAY), line(2, Channel.UNKNOWN), line(3, Channel.ROLEPLAY)), 0, 0, true);
    }
    private static Persona persona(SqliteIdentityRepository repository, CharacterIdentity identity, String label) throws Exception {
        repository.saveIdentity(identity); var persona = new Persona(id(), identity.id(), label); repository.createPersona(persona); return persona;
    }
    private long scalar(String query) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + charactersPath()); var sql = connection.createStatement(); var row = sql.executeQuery(query)) { return row.getLong(1); }
    }
    private void execute(String command) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + charactersPath()); var sql = connection.createStatement()) { sql.execute(command); }
    }

    @Test void identicalNamesAndAliasesDoNotMergeAndManualCorrectionsReplaceOnlyTheChosenIdentity() throws Exception {
        archive(); var repository = identities();
        var first = identity(id(), "Synthetic Name"); var second = identity(id(), "Synthetic Name");
        repository.saveIdentity(first); repository.saveIdentity(second);
        assertEquals(2, repository.identities("Shared Alias").size());
        assertEquals(first.id(), repository.resolveIdentity(first.id())); assertEquals(second.id(), repository.resolveIdentity(second.id()));
        var changed = new CharacterIdentity(first.id(), "Renamed Synthetic", "Updated note", Set.of("Literal %_\\ Alias"), "OtherSyntheticAccount");
        repository.saveIdentity(changed);
        assertEquals(List.of(second), repository.identities("Shared Alias"));
        assertEquals(List.of(changed), repository.identities("%_\\"));
        assertTrue(repository.identities("' OR 1=1 --").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> repository.identities("x".repeat(257)));
        assertEquals(2, repository.identities(null).size()); assertNull(repository.activePersona());
        archive(); assertEquals(List.of(changed), identities().identities("Renamed Synthetic"));
    }

    @Test void personaMemoryIsExplicitAndSourceChannelQueryAndFavoritesNeverLeakOtherPersonaRows() throws Exception {
        var archive = archive(); messages(archive); var repository = identities();
        var first = persona(repository, identity(id(), "Synthetic First"), "First persona");
        var second = persona(repository, identity(id(), "Synthetic Second"), "Second persona");
        assertEquals(0, repository.memoryTranscript(first.id(), null, null, false, 0, 20).total());
        repository.assign(new MemoryAssignment(ref(1), first.id(), first.identityId(), AttributionRole.SPEAKER));
        repository.assign(new MemoryAssignment(ref(2), second.id(), second.identityId(), AttributionRole.REFERENCED));
        repository.assign(new MemoryAssignment(ref(3), first.id(), null, AttributionRole.REFERENCED));
        archive.setFavorite(ref(2), true); archive.setFavorite(ref(3), true);
        var firstPage = repository.memoryTranscript(first.id(), "%_\\", Channel.ROLEPLAY, true, 0, 1);
        assertEquals(1, firstPage.total()); assertEquals(List.of(line(3, Channel.ROLEPLAY)), firstPage.entries());
        assertEquals(Set.of(ref(3)), firstPage.favorites());
        assertNull(repository.assignment(ref(3), first.id()).identityId(), "Explicit unassigned must not guess a name");
        assertEquals(0, repository.memoryTranscript(first.id(), null, Channel.UNKNOWN, false, 0, 20).total());
        assertEquals(1, repository.memoryTranscript(second.id(), null, Channel.UNKNOWN, true, 0, 20).total());
        assertEquals(List.of(line(2, Channel.UNKNOWN)), repository.memoryTranscript(second.id(), null, null, false, 0, 20).entries());
        assertEquals(2, repository.memoryTranscript(first.id(), null, null, false, 1, 1).total());
        assertEquals(ref(3), MessageReference.of(repository.memoryTranscript(first.id(), null, null, false, 1, 1).entries().getFirst()));
        assertTrue(repository.memoryTranscript(first.id(), "' OR 1=1 --", null, false, 0, 20).entries().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> repository.memoryTranscript(null, null, null, false, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> repository.memoryTranscript(first.id(), null, null, false, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> repository.memoryTranscript(first.id(), null, null, false, 0, 201));
        assertEquals(3, archive.transcript(null, null, 0, 20).total());
    }

    @Test void assignmentCorrectionAndClearAreIdempotentReversibleDecisionsWithoutPayloadMutation() throws Exception {
        var archive = archive(); messages(archive); var repository = identities();
        var first = identity(id(), "Synthetic First"); var second = identity(id(), "Synthetic Second");
        var persona = persona(repository, first, "Synthetic persona"); repository.saveIdentity(second);
        archive.setFavorite(ref(1), true); var before = archive.transcript(null, null, 0, 20);
        var original = new MemoryAssignment(ref(1), persona.id(), first.id(), AttributionRole.SPEAKER);
        repository.assign(original); repository.assign(original); assertEquals(1, scalar("SELECT count(*) FROM identity_decisions"));
        var correction = new MemoryAssignment(ref(1), persona.id(), second.id(), AttributionRole.REFERENCED);
        repository.assign(correction); assertEquals(correction, repository.assignment(ref(1), persona.id()));
        assertEquals(2, scalar("SELECT count(*) FROM identity_decisions"));
        repository.clearAssignment(ref(1), persona.id()); repository.clearAssignment(ref(1), persona.id());
        assertNull(repository.assignment(ref(1), persona.id())); assertEquals(3, scalar("SELECT count(*) FROM identity_decisions"));
        assertEquals(before, archive.transcript(null, null, 0, 20));
        assertEquals(0, scalar("SELECT count(*) FROM pragma_table_info('identity_decisions') WHERE name IN ('text','raw','dialogue')"));
        archive(); assertNull(identities().assignment(ref(1), persona.id()));
        assertEquals(before, archive.transcript(null, null, 0, 20));
    }

    @Test void explicitMergeOverlayCanBeUndoneOutOfOrderWithoutRewritingMessagesOrAssignments() throws Exception {
        var archive = archive(); messages(archive); var repository = identities();
        var first = identity(id(), "Synthetic First"); var second = identity(id(), "Synthetic Second"); var third = identity(id(), "Synthetic Third");
        var persona = persona(repository, first, "Synthetic persona"); repository.saveIdentity(second); repository.saveIdentity(third);
        var original = new MemoryAssignment(ref(1), persona.id(), first.id(), AttributionRole.SPEAKER); repository.assign(original);
        archive.setFavorite(ref(1), true); var before = archive.transcript(null, null, 0, 20);
        String firstMerge = repository.mergeIdentity(first.id(), second.id()); String secondMerge = repository.mergeIdentity(second.id(), third.id());
        assertEquals(third.id(), repository.resolveIdentity(first.id())); assertEquals(third.id(), repository.resolveIdentity(second.id()));
        assertThrows(IllegalArgumentException.class, () -> repository.mergeIdentity(first.id(), third.id()));
        assertThrows(IllegalArgumentException.class, () -> repository.mergeIdentity(third.id(), first.id()));
        assertThrows(IllegalArgumentException.class, () -> repository.mergeIdentity(third.id(), third.id()));
        repository.undoMerge(firstMerge); repository.undoMerge(firstMerge);
        assertEquals(first.id(), repository.resolveIdentity(first.id())); assertEquals(third.id(), repository.resolveIdentity(second.id()));
        repository.undoMerge(secondMerge);
        assertEquals(second.id(), repository.resolveIdentity(second.id())); assertEquals(original, repository.assignment(ref(1), persona.id()));
        assertEquals(3, repository.identities(null).size()); assertEquals(before, archive.transcript(null, null, 0, 20));
        archive(); assertEquals(first.id(), identities().resolveIdentity(first.id()));
    }

    @Test void nonexistentReferencesFailWithoutChangingPersonaSelectionOrCreatingAuditDecisions() throws Exception {
        var archive = archive(); messages(archive); var repository = identities();
        var persona = persona(repository, identity(id(), "Synthetic First"), "Synthetic persona"); repository.selectPersona(persona.id());
        assertThrows(SQLException.class, () -> repository.selectPersona(id())); assertEquals(persona.id(), repository.activePersona());
        assertThrows(SQLException.class, () -> repository.assign(new MemoryAssignment(ref(99), persona.id(), persona.identityId(), AttributionRole.SPEAKER)));
        assertThrows(SQLException.class, () -> repository.assign(new MemoryAssignment(ref(1), id(), persona.identityId(), AttributionRole.SPEAKER)));
        assertThrows(SQLException.class, () -> repository.assign(new MemoryAssignment(ref(1), persona.id(), id(), AttributionRole.SPEAKER)));
        assertThrows(SQLException.class, () -> repository.clearAssignment(ref(99), persona.id()));
        assertThrows(SQLException.class, () -> repository.memoryTranscript(id(), null, null, false, 0, 20));
        assertEquals(0, scalar("SELECT count(*) FROM memory_assignments")); assertEquals(0, scalar("SELECT count(*) FROM identity_decisions"));
        archive(); assertEquals(persona.id(), identities().activePersona());
        identities().selectPersona(null); archive(); assertNull(identities().activePersona());
    }

    @Test void auditFailureRollsBackAssignmentAndKeepsExistingFavoriteAndEvidence() throws Exception {
        var archive = archive(); messages(archive); var repository = identities();
        var persona = persona(repository, identity(id(), "Synthetic First"), "Synthetic persona");
        archive.setFavorite(ref(1), true); var before = archive.transcript(null, null, 0, 20);
        execute("CREATE TRIGGER synthetic_identity_audit_failure BEFORE INSERT ON identity_decisions BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        try {
            assertThrows(SQLException.class, () -> repository.assign(new MemoryAssignment(ref(1), persona.id(), persona.identityId(), AttributionRole.SPEAKER)));
            assertNull(repository.assignment(ref(1), persona.id())); assertEquals(0, scalar("SELECT count(*) FROM identity_decisions"));
            assertEquals(before, archive.transcript(null, null, 0, 20));
        } finally { execute("DROP TRIGGER synthetic_identity_audit_failure"); }
        repository.assign(new MemoryAssignment(ref(1), persona.id(), persona.identityId(), AttributionRole.SPEAKER));
        assertEquals(1, repository.memoryTranscript(persona.id(), null, null, true, 0, 20).total());
    }

    @Test void undoRemovesOnlyOwnedMemoryAssignmentsAndRetainsPayloadFreeAuditCoordinates() throws Exception {
        var archive = archive(); archive.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 2, POLICY));
        archive.appendBatch(SOURCE, 0, 1, List.of(line(1, Channel.UNKNOWN)), 0, 0, false);
        String batch = id();
        archive.createBatch(new BatchPlan(batch, directory.toString(), POLICY, Instant.now().toString(), Set.of(), List.of(
            new BatchFile(batch, 0, directory.resolve("synthetic.log").toString(), "synthetic.log", SOURCE, "PENDING", null, 0, 0, 0, 0, 0))));
        archive.finishBatch(batch, "RUNNING"); archive.updateFile(batch, 0, "RUNNING", SOURCE, null);
        archive.appendBatchOwned(batch, 0, SOURCE, 1, 2, List.of(line(2, Channel.ROLEPLAY)), 0, 0, true);
        archive.updateFile(batch, 0, "COMPLETE", SOURCE, null); archive.finishBatch(batch, "COMPLETE");
        var repository = identities(); var persona = persona(repository, identity(id(), "Synthetic First"), "Synthetic persona");
        for (long coordinate : List.of(1L, 2L)) {
            repository.assign(new MemoryAssignment(ref(coordinate), persona.id(), persona.identityId(), AttributionRole.SPEAKER));
            archive.setFavorite(ref(coordinate), true);
        }
        assertEquals(1, archive.undoBatch(batch));
        assertNull(repository.assignment(ref(2), persona.id())); assertNotNull(repository.assignment(ref(1), persona.id()));
        assertEquals(List.of(line(1, Channel.UNKNOWN)), repository.memoryTranscript(persona.id(), null, null, false, 0, 20).entries());
        assertEquals(Set.of(ref(1)), archive.transcript(null, null, 0, 20).favorites());
        assertEquals(2, scalar("SELECT count(*) FROM identity_decisions"));
        assertEquals(1, scalar("SELECT count(*) FROM identity_decisions WHERE source_line=2"));
        assertThrows(SQLException.class, () -> repository.assign(new MemoryAssignment(ref(2), persona.id(), persona.identityId(), AttributionRole.SPEAKER)));
        assertEquals(2, archive.progress(SOURCE).processedLines()); assertEquals(1, archive.removedRecords(SOURCE));
    }
}
