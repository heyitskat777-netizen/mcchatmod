package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class IdentityReviewQueueStoreTest {
    private static final String SOURCE = "a".repeat(64), PERSONA = "91000000-0000-0000-0000-000000000001";
    @TempDir Path directory;
    private Path archive() { return directory.resolve("archive.sqlite"); }
    private Path characters() { return directory.resolve("characters.sqlite"); }
    private SqliteIdentityRepository repository() { return new SqliteIdentityRepository(archive(), characters()); }
    private static String id(int number) { return "90000000-0000-0000-0000-" + String.format("%012d", number); }

    @Test void acceptedAndDeniedPairsAdvanceAcrossRestartAndPairedRestoreWithoutChangingPersonaKnowledgeOrArchive() throws Exception {
        var store = PairedDatabaseFixtures.open(archive(), characters()); var repository = repository();
        for (int index = 1; index <= 3; index++) repository.saveIdentity(new CharacterIdentity(id(index), "Synthetic Character " + index, "", Set.of("Synthetic Shared Alias"), ""));
        store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 2));
        store.appendBatch(SOURCE, 0, 2, List.of(new TranscriptLine(SOURCE, 1, "12:00:00", "Synthetic Character 1 says: hello.", Channel.ROLEPLAY, null),
            new TranscriptLine(SOURCE, 2, "12:00:01", "Synthetic Character 2 waves.", Channel.ROLEPLAY, null)), 0, 0, true);
        var reference = new MessageReference(SOURCE, 1); store.setFavorite(reference, true);
        repository.createPersona(new Persona(PERSONA, id(1), "Synthetic persona")); repository.assign(new MemoryAssignment(reference, PERSONA, id(1), AttributionRole.SPEAKER));
        var archiveBefore = store.transcript(null, null, 0, 200); var memoryBefore = repository.memoryTranscript(PERSONA, "", null, false, 0, 200);
        var first = IdentityReviewQueue.pending(repository.reviewData(), 1); assertEquals(List.of(new IdentityReviewPair(id(1), id(2))), first.pairs()); assertTrue(first.hasMore());
        String merge = repository.mergeIdentity(id(1), id(2));
        var next = IdentityReviewQueue.pending(repository.reviewData(), 1); assertEquals(List.of(new IdentityReviewPair(id(2), id(3))), next.pairs());
        String denial = repository.denyIdentityLink(id(2), id(3));
        assertTrue(IdentityReviewQueue.pending(repository.reviewData(), 1).pairs().isEmpty());
        PairedDatabaseFixtures.open(archive(), characters()); assertEquals(new IdentityReviewBatch(List.of(), false), IdentityReviewQueue.pending(repository().reviewData(), 1));
        var backup = store.createBackup(directory.resolve("backups/review-queue.sqlite"));
        repository().undoSeparation(denial); assertEquals(next, IdentityReviewQueue.pending(repository().reviewData(), 1));
        var restoredStore = new SqliteJournalStore(backup, new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()); restoredStore.initialize();
        var restored = new SqliteIdentityRepository(backup); assertTrue(IdentityReviewQueue.pending(restored.reviewData(), 100).pairs().isEmpty());
        assertEquals(archiveBefore, restoredStore.transcript(null, null, 0, 200)); assertEquals(memoryBefore, restored.memoryTranscript(PERSONA, "", null, false, 0, 200));
        assertEquals(new MemoryAssignment(reference, PERSONA, id(1), AttributionRole.SPEAKER), restored.assignment(reference, PERSONA));
        assertEquals(id(2), restored.resolveIdentity(id(1))); assertTrue(restored.merges().stream().anyMatch(value -> value.id().equals(merge) && value.active()));
        assertEquals(archiveBefore, store.transcript(null, null, 0, 200)); assertEquals(memoryBefore, repository().memoryTranscript(PERSONA, "", null, false, 0, 200));
    }

    @Test void queueSnapshotIncludesCandidatesBeyondTheBoundedCharacterBrowserWithoutPairingUnrelatedRecords() throws Exception {
        PairedDatabaseFixtures.open(archive(), characters());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + characters())) {
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("INSERT INTO character_identities VALUES (?,?,'','')")) {
                for (int index = 1; index <= 1002; index++) {
                    insert.setString(1, id(index)); insert.setString(2, (index <= 1000 ? "Synthetic Unique " : "Zzz Synthetic Beyond ") + index); insert.addBatch();
                }
                insert.executeBatch();
            }
            try (var insert = connection.prepareStatement("INSERT INTO character_aliases VALUES (?,?)")) {
                for (int index : List.of(1001, 1002)) { insert.setString(1, id(index)); insert.setString(2, "Beyond bounded browser"); insert.addBatch(); }
                insert.executeBatch();
            }
            connection.commit();
        }
        var browser = repository().identities(""); assertEquals(1000, browser.size());
        assertTrue(browser.stream().noneMatch(value -> value.id().equals(id(1001)) || value.id().equals(id(1002))));
        var data = repository().reviewData(); assertEquals(1002, data.identities().size());
        var candidates = IdentityReviewQueue.pending(data, 100); var expected = new IdentityReviewPair(id(1001), id(1002));
        assertEquals(List.of(expected), candidates.pairs()); assertFalse(candidates.hasMore()); assertEquals("beyond bounded browser", candidates.matchingNames().get(expected));
    }
}
