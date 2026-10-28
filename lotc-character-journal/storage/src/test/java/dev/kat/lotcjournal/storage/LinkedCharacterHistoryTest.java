package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LinkedCharacterHistoryTest {
    private static final String SOURCE = "a".repeat(64), OTHER_SOURCE = "b".repeat(64);
    private static final String FIRST = "40000000-0000-0000-0000-000000000001", SECOND = "40000000-0000-0000-0000-000000000002",
        THIRD = "40000000-0000-0000-0000-000000000003", OUTSIDE = "40000000-0000-0000-0000-000000000004";
    private static final String PERSONA_FIRST = "50000000-0000-0000-0000-000000000001", PERSONA_SECOND = "50000000-0000-0000-0000-000000000002";
    @TempDir Path directory;
    private Path archive() { return directory.resolve("archive.sqlite"); }
    private Path characters() { return directory.resolve("characters.sqlite"); }
    private SqliteIdentityRepository repository() { return new SqliteIdentityRepository(archive(), characters()); }
    private static MessageReference ref(long coordinate) { return new MessageReference(SOURCE, coordinate); }
    private static List<Long> coordinates(TranscriptPage page) { return page.entries().stream().map(TranscriptLine::sourceLine).toList(); }
    private static TranscriptLine line(long coordinate, String text, Channel channel) { return new TranscriptLine(SOURCE, coordinate, "12:34:56", text, channel, null); }
    private SqliteJournalStore fixture() throws Exception {
        var store = PairedDatabaseFixtures.open(archive(), characters()); var repository = repository();
        var ids = List.of(FIRST, SECOND, THIRD, OUTSIDE); var names = List.of("Synthetic First", "Synthetic Second", "Synthetic Third", "Synthetic Fourth");
        for (int index = 0; index < ids.size(); index++) repository.saveIdentity(new CharacterIdentity(ids.get(index), names.get(index), "Manual note", Set.of("Shared Alias"), ""));
        store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 10));
        store.appendBatch(SOURCE, 0, 10, List.of(
            line(1, "Synthetic First says: one.", Channel.ROLEPLAY), line(2, "Synthetic Second says: two.", Channel.ROLEPLAY),
            line(3, "Synthetic Third waves.", Channel.ROLEPLAY), line(4, "Synthetic First says: hello Synthetic Second.", Channel.ROLEPLAY),
            line(5, "A note mentions Synthetic Third.", Channel.UNKNOWN), line(6, "Synthetic Second says: repeated.", Channel.ROLEPLAY),
            line(7, "Synthetic Second says: repeated.", Channel.ROLEPLAY), line(8, "Synthetic Fourth says: outside.", Channel.ROLEPLAY)), 2, 0, true);
        for (long coordinate : List.of(2L, 4L, 7L)) store.setFavorite(ref(coordinate), true);
        repository.createPersona(new Persona(PERSONA_FIRST, FIRST, "First owning persona")); repository.createPersona(new Persona(PERSONA_SECOND, SECOND, "Second owning persona"));
        repository.selectPersona(PERSONA_FIRST);
        repository.assign(new MemoryAssignment(ref(1), PERSONA_FIRST, FIRST, AttributionRole.SPEAKER));
        repository.assign(new MemoryAssignment(ref(4), PERSONA_FIRST, null, AttributionRole.REFERENCED));
        repository.assign(new MemoryAssignment(ref(2), PERSONA_SECOND, SECOND, AttributionRole.SPEAKER));
        return store;
    }
    private static List<String> link(SqliteIdentityRepository repository) throws Exception {
        return List.of(repository.mergeIdentity(FIRST, SECOND), repository.mergeIdentity(SECOND, THIRD));
    }
    private static TranscriptPage history(SqliteIdentityRepository repository, String identity) throws Exception {
        return repository.characterTranscript(identity, "", null, null, false, 0, 20);
    }

    @Test void everyMemberSeesOneTransitiveHistoryWithDistinctRepeatedMessagesAndIntersectingFilters() throws Exception {
        var store = fixture(); var repository = repository();
        assertEquals(List.of(1L, 4L), coordinates(history(repository, FIRST)), "A shared alias is not a confirmed link");
        var sourceBefore = store.transcript(null, null, 0, 20); link(repository);
        var expected = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        for (String member : List.of(FIRST, SECOND, THIRD)) assertEquals(expected, coordinates(history(repository, member)));
        assertEquals(List.of(8L), coordinates(history(repository, OUTSIDE)));
        assertEquals(List.of(1L, 2L, 4L, 6L, 7L), coordinates(repository.characterTranscript(FIRST, "", null, CharacterEvidenceKind.SPEAKER, false, 0, 20)));
        assertEquals(List.of(3L), coordinates(repository.characterTranscript(SECOND, "", null, CharacterEvidenceKind.ACTION, false, 0, 20)));
        assertEquals(List.of(4L, 5L), coordinates(repository.characterTranscript(THIRD, "", null, CharacterEvidenceKind.MENTION, false, 0, 20)));
        assertEquals(6, repository.characterTranscript(FIRST, "", Channel.ROLEPLAY, null, false, 0, 20).total());
        assertEquals(List.of(5L), coordinates(repository.characterTranscript(FIRST, "", Channel.UNKNOWN, null, false, 0, 20)));
        var favorite = repository.characterTranscript(SECOND, "", null, null, true, 1, 1);
        assertEquals(3, favorite.total()); assertEquals(List.of(4L), coordinates(favorite)); assertEquals(Set.of(ref(4)), favorite.favorites());
        var repeated = repository.characterTranscript(THIRD, "repeated.", Channel.ROLEPLAY, CharacterEvidenceKind.SPEAKER, false, 1, 1);
        assertEquals(2, repeated.total()); assertEquals(List.of(7L), coordinates(repeated));
        assertEquals(0, repository.characterTranscript(FIRST, "' OR 1=1 --", null, null, false, 0, 20).total());
        assertEquals(sourceBefore, store.transcript(null, null, 0, 20));
    }

    @Test void effectiveReviewedSetsApplyBeforeGroupUnionAndAutomaticEvidenceRemainsReversible() throws Exception {
        var store = fixture(); var repository = repository(); link(repository); var sourceBefore = store.transcript(null, null, 0, 20);
        var original = repository.reviewTags(ref(1));
        repository.saveReviewedTags(ref(1), 0, List.of(new CharacterTag(OUTSIDE, CharacterEvidenceKind.SPEAKER)));
        repository.saveReviewedTags(ref(2), 0, List.of());
        repository.saveReviewedTags(ref(4), 0, List.of(new CharacterTag(FIRST, CharacterEvidenceKind.SPEAKER), new CharacterTag(THIRD, CharacterEvidenceKind.MENTION)));
        repository.saveReviewedTags(ref(8), 0, List.of(new CharacterTag(FIRST, CharacterEvidenceKind.MENTION)));
        assertEquals(List.of(3L, 4L, 5L, 6L, 7L, 8L), coordinates(history(repository, SECOND)));
        assertEquals(List.of(1L), coordinates(history(repository, OUTSIDE)));
        assertEquals(2, repository.characterTranscript(THIRD, "", null, null, true, 0, 20).total());
        assertEquals(original.automaticTags(), repository.reviewTags(ref(1)).automaticTags());
        assertEquals(original.message(), repository.reviewTags(ref(1)).message());
        repository.restoreAutomaticTags(ref(1), 1); repository.restoreAutomaticTags(ref(2), 1); repository.restoreAutomaticTags(ref(8), 1);
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), coordinates(history(repository, FIRST)));
        assertEquals(List.of(8L), coordinates(history(repository, OUTSIDE)));
        assertEquals(sourceBefore, store.transcript(null, null, 0, 20));
    }

    @Test void undoingOneConfirmedLinkShrinksOnlyItsCurrentConnectedComponent() throws Exception {
        fixture(); var repository = repository(); var links = link(repository);
        repository.undoMerge(links.getFirst()); repository.undoMerge(links.getFirst());
        assertEquals(List.of(1L, 4L), coordinates(history(repository, FIRST)));
        assertEquals(List.of(2L, 3L, 4L, 5L, 6L, 7L), coordinates(history(repository, SECOND)));
        assertEquals(history(repository, SECOND), history(repository, THIRD));
        repository.undoMerge(links.getLast());
        assertEquals(List.of(2L, 4L, 6L, 7L), coordinates(history(repository, SECOND)));
        assertEquals(List.of(3L, 5L), coordinates(history(repository, THIRD)));
        assertEquals(List.of(1L, 4L), coordinates(history(repository, FIRST)));
        assertEquals(FIRST, repository.resolveIdentity(FIRST)); assertEquals(SECOND, repository.resolveIdentity(SECOND));
    }

    @Test void denialPreventsGroupExpansionAndConfirmedHistoryNeverGrantsPersonaMemories() throws Exception {
        var store = fixture(); var repository = repository();
        var firstMemory = repository.memoryTranscript(PERSONA_FIRST, "", null, false, 0, 20);
        var secondMemory = repository.memoryTranscript(PERSONA_SECOND, "", null, false, 0, 20);
        var before = store.transcript(null, null, 0, 20); repository.denyIdentityLink(FIRST, SECOND);
        repository.mergeIdentity(FIRST, THIRD); repository.mergeIdentity(OUTSIDE, SECOND);
        var left = history(repository, THIRD); var right = history(repository, SECOND);
        assertThrows(IllegalArgumentException.class, () -> repository.mergeIdentity(THIRD, SECOND));
        assertThrows(IllegalArgumentException.class, () -> repository.mergeIdentity(SECOND, THIRD));
        assertEquals(left, history(repository, FIRST)); assertEquals(right, history(repository, OUTSIDE));
        assertEquals(firstMemory, repository.memoryTranscript(PERSONA_FIRST, "", null, false, 0, 20));
        assertEquals(secondMemory, repository.memoryTranscript(PERSONA_SECOND, "", null, false, 0, 20));
        assertEquals(List.of(1L, 4L), coordinates(firstMemory)); assertEquals(List.of(2L), coordinates(secondMemory));
        assertEquals(PERSONA_FIRST, repository.activePersona()); assertEquals(before, store.transcript(null, null, 0, 20));
        assertNull(repository.assignment(ref(3), PERSONA_FIRST)); assertNull(repository.assignment(ref(8), PERSONA_SECOND));
    }

    @Test void groupHistoryPersistsThroughRestartAndPairedBackupWhileLaterUndoStaysIndependent() throws Exception {
        var store = fixture(); var repository = repository(); var links = link(repository); var expected = history(repository, FIRST);
        var tagsBefore = repository.reviewTags(ref(4));
        PairedDatabaseFixtures.open(archive(), characters()); assertEquals(expected, history(repository, SECOND));
        Path backup = store.createBackup(directory.resolve("backups/linked.sqlite"));
        repository.undoMerge(links.getFirst()); assertEquals(List.of(1L, 4L), coordinates(history(repository, FIRST)));
        var restored = new SqliteJournalStore(backup, new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()); restored.initialize();
        var restoredRepository = new SqliteIdentityRepository(backup);
        for (String member : List.of(FIRST, SECOND, THIRD)) assertEquals(expected, history(restoredRepository, member));
        assertEquals(tagsBefore, restoredRepository.reviewTags(ref(4)));
        assertEquals(List.of(1L, 4L), coordinates(restoredRepository.memoryTranscript(PERSONA_FIRST, "", null, false, 0, 20)));
    }

    @Test void sameLineNumberAndSameTextInDifferentSourcesAreNeverCollapsedByGroupDeduplication() throws Exception {
        var store = fixture(); var repository = repository(); link(repository);
        store.beginSource(new ImportSource(OTHER_SOURCE, "synthetic-copy.log", "plain", 1));
        store.appendBatch(OTHER_SOURCE, 0, 1, List.of(new TranscriptLine(OTHER_SOURCE, 1, "12:34:56", "Synthetic First says: one.", Channel.ROLEPLAY, null)), 0, 0, true);
        var history = history(repository, FIRST); assertEquals(8, history.total());
        assertEquals(2, history.entries().stream().filter(line -> line.sourceLine() == 1).count());
        assertEquals(8, history.entries().stream().map(MessageReference::of).distinct().count());
        assertEquals(2, history.entries().stream().filter(line -> line.text().equals("Synthetic First says: one.")).count());
        assertEquals(9, store.transcript(null, null, 0, 20).total());
    }
}
