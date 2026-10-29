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

class CharacterDiscoveryTest {
    private static final String SOURCE = "c".repeat(64), LIVE = "d".repeat(64);
    @TempDir Path directory;
    private Path archive() { return directory.resolve("archive.sqlite"); }
    private Path characters() { return directory.resolve("characters.sqlite"); }
    private SqliteJournalStore store() throws Exception { return PairedDatabaseFixtures.open(archive(), characters()); }
    private SqliteIdentityRepository repository() { return new SqliteIdentityRepository(archive(), characters()); }
    private static String id() { return UUID.randomUUID().toString(); }
    private static TranscriptLine line(long coordinate, String text, Channel channel) { return new TranscriptLine(SOURCE, coordinate, "12:34:56", text, channel, null); }
    private static MessageReference ref(long coordinate) { return new MessageReference(SOURCE, coordinate); }
    private static CharacterIdentity identity(String id, String name, Set<String> aliases) { return new CharacterIdentity(id, name, "Manual note", aliases, ""); }
    private static List<Long> coordinates(TranscriptPage page) { return page.entries().stream().map(TranscriptLine::sourceLine).toList(); }
    private void append(SqliteJournalStore store, List<TranscriptLine> lines) throws Exception {
        store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", lines.size()));
        store.appendBatch(SOURCE, 0, lines.size(), lines, 0, 0, true);
    }

    @Test void roleplayActorsCreateOneProvisionalCharacterAndRetroactiveTagsWithoutPersonaOwnership() throws Exception {
        var store = store(); var repository = repository();
        append(store, List.of(line(1, "A note mentions Synthetic Visitor.", Channel.UNKNOWN),
            line(2, "Synthetic Visitor says: hello.", Channel.ROLEPLAY), line(3, "Synthetic Visitor smiles: softly.", Channel.ROLEPLAY),
            line(4, "Synthetic Visitor says: hello.", Channel.ROLEPLAY), line(5, "Unknown Stranger waves.", Channel.UNKNOWN)));
        var discovered = repository.identities(""); assertEquals(1, discovered.size());
        var identity = discovered.getFirst(); assertEquals("Synthetic Visitor", identity.name()); assertEquals("", identity.accountObservation());
        assertTrue(repository.personas().isEmpty()); assertNull(repository.activePersona());
        assertEquals(List.of(2L, 4L), coordinates(repository.characterTranscript(identity.id(), "", null, CharacterEvidenceKind.SPEAKER, false, 0, 20)));
        assertEquals(List.of(3L), coordinates(repository.characterTranscript(identity.id(), "", null, CharacterEvidenceKind.ACTION, false, 0, 20)));
        assertEquals(List.of(1L), coordinates(repository.characterTranscript(identity.id(), "", null, CharacterEvidenceKind.MENTION, false, 0, 20)));
        assertEquals(4, repository.characterTranscript(identity.id(), "", null, null, false, 0, 20).total());
        var before = store.transcript(null, null, 0, 20); assertEquals(5, before.total());
        assertTrue(before.entries().stream().allMatch(entry -> entry.observedSpeaker() == null), "Derived tags never rewrite source speaker observations");
        store.appendBatch(SOURCE, 5, 5, List.of(), 0, 0, true); store();
        assertEquals(discovered, repository.identities("")); assertEquals(before, store.transcript(null, null, 0, 20));
        assertEquals(0, PairedDatabaseFixtures.scalar(characters(), "SELECT count(*) FROM memory_assignments"));
    }

    @Test void liveReplayUsesTheSameObservedNameMappingButDistinctEventsRemainDistinctEvidence() throws Exception {
        var store = store(); var repository = repository();
        append(store, List.of(line(1, "Synthetic Visitor says: historical hello.", Channel.ROLEPLAY)));
        var character = repository.identities("").getFirst();
        var session = new LiveSession(LIVE, Instant.parse("2026-10-27T12:00:00Z"), "UTC", "0".repeat(64)); store.beginLiveSession(session);
        var first = new LiveEvent(LIVE, 1, session.startedAt(), 0, LiveEvent.Kind.CHAT, "Synthetic Visitor says: hello.", Channel.ROLEPLAY, null);
        assertTrue(store.appendLiveEvent(session, first)); assertFalse(store.appendLiveEvent(session, first));
        assertTrue(store.appendLiveEvent(session, new LiveEvent(LIVE, 2, session.startedAt(), 0, LiveEvent.Kind.GAME, first.text(), Channel.ROLEPLAY, null)));
        assertEquals(List.of(character), repository.identities(""));
        assertEquals(3, repository.characterTranscript(character.id(), "", null, CharacterEvidenceKind.SPEAKER, false, 0, 20).total());
        assertTrue(repository.personas().isEmpty()); assertEquals(3, store.transcript(null, null, 0, 20).total());
    }

    @Test void editingAliasesRetroactivelyReplacesOnlyDerivedLinksAndPreservesManualKnowledgeAndFavorites() throws Exception {
        var store = store(); var repository = repository();
        append(store, List.of(line(1, "A note for Old Alias.", Channel.UNKNOWN), line(2, "A note for New Alias.", Channel.UNKNOWN),
            line(3, "A note for Literal %_\\'.", Channel.UNKNOWN)));
        String identity = id(), persona = id(); repository.saveIdentity(identity(identity, "Manual Character", Set.of("Old Alias")));
        assertEquals(List.of(1L), coordinates(repository.characterTranscript(identity, "", null, CharacterEvidenceKind.MENTION, false, 0, 20)));
        repository.createPersona(new Persona(persona, identity, "Synthetic persona"));
        var assignment = new MemoryAssignment(ref(1), persona, identity, AttributionRole.REFERENCED); repository.assign(assignment); store.setFavorite(ref(1), true);
        var before = store.transcript(null, null, 0, 20);
        repository.saveIdentity(identity(identity, "Manual Character", Set.of("New Alias", "Literal %_\\'")));
        assertEquals(List.of(2L, 3L), coordinates(repository.characterTranscript(identity, "", null, CharacterEvidenceKind.MENTION, false, 0, 20)));
        assertEquals(List.of(3L), coordinates(repository.characterTranscript(identity, "%_\\'", Channel.UNKNOWN, CharacterEvidenceKind.MENTION, false, 0, 20)));
        assertEquals(assignment, repository.assignment(ref(1), persona)); assertEquals(1, repository.memoryTranscript(persona, "", null, true, 0, 20).total());
        repository.saveIdentity(identity(identity, "Manual Character", Set.of()));
        assertEquals(0, repository.characterTranscript(identity, "", null, null, false, 0, 20).total());
        assertEquals(before, store.transcript(null, null, 0, 20)); assertEquals(assignment, repository.assignment(ref(1), persona));
    }

    @Test void metadataOnlyAndNoopSavesPersistWithoutTouchingEvidenceWhileNameAliasAndNewIdentityWritesStillReindexAtomically() throws Exception {
        var store = store(); var repository = repository();
        append(store, List.of(line(1, "Synthetic Visitor says: hello.", Channel.ROLEPLAY),
            line(2, "A note for Old Alias.", Channel.UNKNOWN), line(3, "A note for New Alias.", Channel.UNKNOWN)));
        String character = repository.identities("").getFirst().id(), persona = id(), newCharacter = id();
        var original = new CharacterIdentity(character, "Synthetic Visitor", "Original note", Set.of("Old Alias", "Stable Alias"), "OriginalSyntheticAccount");
        repository.saveIdentity(original); repository.createPersona(new Persona(persona, character, "Synthetic persona")); repository.selectPersona(persona);
        var assignment = new MemoryAssignment(ref(1), persona, character, AttributionRole.SPEAKER); repository.assign(assignment); store.setFavorite(ref(1), true);
        var override = repository.saveReviewedTags(ref(1), 0, List.of(new CharacterTag(character, CharacterEvidenceKind.SPEAKER)));
        var archiveBefore = store.transcript(null, null, 0, 20); var progressBefore = store.progress(SOURCE);
        var evidenceBefore = evidenceRows(); var memoryBefore = repository.memoryTranscript(persona, "", null, false, 0, 20);
        var metadata = new CharacterIdentity(character, original.name(), "Updated note", Set.of("Stable Alias", "Old Alias"), "UpdatedSyntheticAccount");
        var rejectedChanges = List.of(
            new CharacterIdentity(character, "SYNTHETIC VISITOR", "Discarded note", metadata.aliases(), "DiscardedSyntheticAccount"),
            new CharacterIdentity(character, metadata.name(), "Discarded note", Set.of("Old Alias", "Stable Alias", "New Alias"), "DiscardedSyntheticAccount"),
            new CharacterIdentity(character, metadata.name(), "Discarded note", Set.of("Stable Alias"), "DiscardedSyntheticAccount"),
            new CharacterIdentity(newCharacter, "Another Synthetic Character", "Discarded note", Set.of("Old Alias"), "DiscardedSyntheticAccount"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + characters()); var sql = connection.createStatement()) {
            for (String event : List.of("INSERT", "UPDATE", "DELETE")) sql.execute("CREATE TRIGGER synthetic_metadata_evidence_" + event.toLowerCase(java.util.Locale.ROOT)
                + " BEFORE " + event + " ON character_evidence BEGIN SELECT RAISE(ABORT,'synthetic reindex guard'); END");
        }
        try {
            repository.saveIdentity(metadata); assertEquals(List.of(metadata), repository.identities(""));
            repository.saveIdentity(metadata); assertEquals(List.of(metadata), repository.identities(""), "An unchanged save must not invoke an evidence rewrite either");
            for (var rejected : rejectedChanges) {
                assertThrows(SQLException.class, () -> repository.saveIdentity(rejected), "Actual name, alias and new-identity writes must still enter the guarded reindex path");
                assertEquals(List.of(metadata), repository.identities(""), "A failed reindex must roll back notes/account/name/aliases or a newly inserted identity together");
                assertEquals(evidenceBefore, evidenceRows()); assertEquals(override, repository.reviewTags(ref(1)));
            }
            assertEquals(archiveBefore, store.transcript(null, null, 0, 20)); assertEquals(progressBefore, store.progress(SOURCE));
            assertEquals(memoryBefore, repository.memoryTranscript(persona, "", null, false, 0, 20)); assertEquals(assignment, repository.assignment(ref(1), persona));
            assertEquals(persona, repository.activePersona());
            assertEquals(1, PairedDatabaseFixtures.scalar(characters(), "SELECT count(*) FROM character_discoveries"));
            assertEquals(1, PairedDatabaseFixtures.scalar(characters(), "SELECT count(*) FROM memory_assignments"));
        } finally {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + characters()); var sql = connection.createStatement()) {
                for (String event : List.of("insert", "update", "delete")) sql.execute("DROP TRIGGER synthetic_metadata_evidence_" + event);
            }
        }
        var aliasChanged = new CharacterIdentity(character, metadata.name(), metadata.notes(), Set.of("New Alias", "Stable Alias"), metadata.accountObservation());
        repository.saveIdentity(aliasChanged);
        assertEquals(List.of(3L), coordinates(repository.characterTranscript(character, "", null, CharacterEvidenceKind.MENTION, false, 0, 20)));
        var renamed = new CharacterIdentity(character, "Renamed Synthetic Visitor", metadata.notes(), aliasChanged.aliases(), metadata.accountObservation());
        repository.saveIdentity(renamed); assertEquals(List.of(renamed), repository.identities(""));
        assertEquals(List.of(1L), coordinates(repository.characterTranscript(character, "", null, CharacterEvidenceKind.SPEAKER, false, 0, 20)));
        var added = new CharacterIdentity(newCharacter, "Another Synthetic Character", "Accepted note", Set.of("Old Alias"), "AcceptedSyntheticAccount");
        repository.saveIdentity(added); assertEquals(List.of(2L), coordinates(repository.characterTranscript(newCharacter, "", null, CharacterEvidenceKind.MENTION, false, 0, 20)));
        store(); assertEquals(renamed, repository.identities("Renamed Synthetic Visitor").getFirst()); assertEquals(added, repository.identities("Another Synthetic Character").getFirst());
        assertEquals(archiveBefore, store.transcript(null, null, 0, 20)); assertEquals(assignment, repository.assignment(ref(1), persona));
        assertEquals(override.revision(), repository.reviewTags(ref(1)).revision()); assertEquals(override.effectiveTags(), repository.reviewTags(ref(1)).effectiveTags());
    }

    private List<List<String>> evidenceRows() throws Exception {
        var result = new java.util.ArrayList<List<String>>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + characters()); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT identity_id,source_id,source_line,kind,matched_name FROM character_evidence ORDER BY identity_id,source_id,source_line,kind")) {
            while (rows.next()) result.add(List.of(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5)));
        }
        return List.copyOf(result);
    }

    @Test void sharedNamesStayUnresolvedUntilAliasesAreDisambiguatedWithoutMergingOrCreatingAPersona() throws Exception {
        var store = store(); var repository = repository(); String first = id(), second = id();
        repository.saveIdentity(identity(first, "Manual First", Set.of("Shared Mask")));
        repository.saveIdentity(identity(second, "Manual Second", Set.of("Shared Mask")));
        append(store, List.of(line(1, "Shared Mask says: hello.", Channel.ROLEPLAY), line(2, "A note mentions Shared Mask.", Channel.UNKNOWN)));
        assertEquals(2, repository.identities("").size());
        for (String identity : List.of(first, second)) assertEquals(0, repository.characterTranscript(identity, "", null, null, false, 0, 20).total());
        repository.saveIdentity(identity(second, "Manual Second", Set.of()));
        assertEquals(1, repository.characterTranscript(first, "", null, CharacterEvidenceKind.SPEAKER, false, 0, 20).total());
        assertEquals(1, repository.characterTranscript(first, "", null, CharacterEvidenceKind.MENTION, false, 0, 20).total());
        assertEquals(first, repository.resolveIdentity(first)); assertEquals(second, repository.resolveIdentity(second)); assertTrue(repository.personas().isEmpty());
    }

    @Test void longestUnicodeNamesAndLiteralAliasesDoNotAttachShorterOrAdjacentNames() throws Exception {
        var store = store(); var repository = repository(); String shortName = id(), fullName = id(), ann = id(), anna = id(), unicode = id();
        repository.saveIdentity(identity(shortName, "Sir", Set.of())); repository.saveIdentity(identity(fullName, "Sir Vale", Set.of()));
        repository.saveIdentity(identity(ann, "Ann", Set.of())); repository.saveIdentity(identity(anna, "Anna", Set.of()));
        repository.saveIdentity(identity(unicode, "Élian", Set.of()));
        append(store, List.of(line(1, "A note for Sir Vale, Ann's E\u0301LIAN.", Channel.UNKNOWN),
            line(2, "A note for Anna and Annabelle.", Channel.UNKNOWN)));
        assertEquals(0, repository.characterTranscript(shortName, "", null, null, false, 0, 20).total());
        assertEquals(List.of(1L), coordinates(repository.characterTranscript(fullName, "", null, null, false, 0, 20)));
        assertEquals(List.of(1L), coordinates(repository.characterTranscript(ann, "", null, null, false, 0, 20)));
        assertEquals(List.of(2L), coordinates(repository.characterTranscript(anna, "", null, null, false, 0, 20)));
        assertEquals(List.of(1L), coordinates(repository.characterTranscript(unicode, "", null, null, false, 0, 20)));
    }

    @Test void discoveryFailureRollsBackCharacterAndArchiveWritesBeforeTheCheckpointCanAdvance() throws Exception {
        var store = store(); var repository = repository(); store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 1));
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + characters()); var s = c.createStatement()) {
            s.execute("CREATE TRIGGER synthetic_discovery_failure BEFORE INSERT ON character_evidence BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        }
        try {
            assertThrows(SQLException.class, () -> store.appendBatch(SOURCE, 0, 1, List.of(line(1, "Synthetic Visitor says: hello.", Channel.ROLEPLAY)), 0, 0, true));
            assertEquals(0, store.progress(SOURCE).processedLines()); assertEquals(0, store.transcript(null, null, 0, 20).total());
            assertTrue(repository.identities("").isEmpty()); assertEquals(0, PairedDatabaseFixtures.scalar(characters(), "SELECT count(*) FROM character_discoveries"));
        } finally {
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + characters()); var s = c.createStatement()) { s.execute("DROP TRIGGER synthetic_discovery_failure"); }
        }
        store.appendBatch(SOURCE, 0, 1, List.of(line(1, "Synthetic Visitor says: hello.", Channel.ROLEPLAY)), 0, 0, true);
        assertEquals(1, repository.identities("").size()); assertEquals(1, store.progress(SOURCE).processedLines());
    }

    @Test void undoRemovesExactDerivedCoordinatesButKeepsTheObservedCharacterAndDoesNotRestoreRemovedEvidence() throws Exception {
        var store = store(); var repository = repository(); String batch = id();
        store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 2));
        store.appendBatch(SOURCE, 0, 1, List.of(line(1, "Synthetic Visitor says: older hello.", Channel.ROLEPLAY)), 0, 0, false);
        store.createBatch(new BatchPlan(batch, directory.toString(), "0".repeat(64), Instant.now().toString(), Set.of(), List.of(
            new BatchFile(batch, 0, directory.resolve("synthetic.log").toString(), "synthetic.log", SOURCE, "PENDING", null, 0, 0, 0, 0, 0))));
        store.finishBatch(batch, "RUNNING"); store.updateFile(batch, 0, "RUNNING", SOURCE, null);
        store.appendBatchOwned(batch, 0, SOURCE, 1, 2, List.of(line(2, "Synthetic Visitor waves.", Channel.ROLEPLAY)), 0, 0, true);
        store.updateFile(batch, 0, "COMPLETE", SOURCE, null); store.finishBatch(batch, "COMPLETE"); store.setFavorite(ref(2), true);
        var character = repository.identities("").getFirst(); var progress = store.progress(SOURCE);
        assertEquals(1, store.undoBatch(batch)); store();
        assertEquals(List.of(character), repository.identities(""));
        assertEquals(List.of(1L), coordinates(repository.characterTranscript(character.id(), "", null, null, false, 0, 20)));
        assertTrue(store.transcript(null, null, 0, 20).favorites().isEmpty()); assertEquals(progress, store.progress(SOURCE));
        store.appendBatch(SOURCE, 2, 2, List.of(), 0, 0, true);
        assertEquals(1, repository.characterTranscript(character.id(), "", null, null, false, 0, 20).total());
    }
}
