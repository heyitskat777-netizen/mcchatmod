package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static dev.kat.lotcjournal.storage.PairedDatabaseFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class MessageTagReviewTest {
    private static final String SOURCE_ID = "e".repeat(64), LIVE_ID = "f".repeat(64);
    private static final String SPEAKER = "40000000-0000-0000-0000-000000000001", MENTION = "40000000-0000-0000-0000-000000000002",
        CORRECTION = "40000000-0000-0000-0000-000000000003", PERSONA = "50000000-0000-0000-0000-000000000001";
    @TempDir Path directory;
    private Path archive() { return directory.resolve("archive.sqlite"); }
    private Path characters() { return directory.resolve("characters.sqlite"); }
    private SqliteIdentityRepository repository() { return new SqliteIdentityRepository(archive(), characters()); }
    private static MessageReference message(long coordinate) { return new MessageReference(SOURCE_ID, coordinate); }
    private static CharacterTag tag(String identity, CharacterEvidenceKind kind) { return new CharacterTag(identity, kind); }
    private static Set<CharacterTag> tags(List<CharacterTag> tags) { return Set.copyOf(tags); }
    private static List<Long> coordinates(TranscriptPage page) { return page.entries().stream().map(TranscriptLine::sourceLine).toList(); }
    private static CharacterIdentity identity(String id, String name) { return new CharacterIdentity(id, name, "Synthetic manual note", Set.of(), ""); }
    private SqliteJournalStore fixture() throws Exception {
        var store = open(archive(), characters()); var repository = repository();
        repository.saveIdentity(identity(SPEAKER, "Synthetic Speaker")); repository.saveIdentity(identity(MENTION, "Synthetic Mention"));
        repository.saveIdentity(identity(CORRECTION, "Synthetic Correction"));
        store.beginSource(new ImportSource(SOURCE_ID, "synthetic.log", "plain", 4));
        store.appendBatch(SOURCE_ID, 0, 4, List.of(
            new TranscriptLine(SOURCE_ID, 1, "12:34:56", "Synthetic Speaker says: hello to Synthetic Mention.", Channel.ROLEPLAY, null),
            new TranscriptLine(SOURCE_ID, 2, "12:34:56", "Synthetic Speaker says: hello to Synthetic Mention.", Channel.ROLEPLAY, null),
            new TranscriptLine(SOURCE_ID, 3, null, "A note mentions Synthetic Speaker and Synthetic Mention.", Channel.UNKNOWN, null)), 1, 0, true);
        store.setFavorite(message(1), true); store.setFavorite(message(2), true);
        repository.createPersona(new Persona(PERSONA, SPEAKER, "Synthetic owning persona")); repository.selectPersona(PERSONA);
        repository.assign(new MemoryAssignment(message(1), PERSONA, SPEAKER, AttributionRole.SPEAKER));
        return store;
    }
    private void sql(Path path, String command) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path); var s = c.createStatement()) { s.execute(command); }
    }

    @Test void exactMultitagCorrectionChangesOnlyOneOfTwoIdenticalMessagesAndPreservesPersonaKnowledge() throws Exception {
        var store = fixture(); var repository = repository(); var original = repository.reviewTags(message(1));
        var automatic = Set.of(tag(SPEAKER, CharacterEvidenceKind.SPEAKER), tag(MENTION, CharacterEvidenceKind.MENTION));
        assertFalse(original.overridden()); assertEquals(0, original.revision()); assertEquals(automatic, tags(original.automaticTags()));
        assertEquals(automatic, tags(original.effectiveTags())); assertEquals("Synthetic Speaker", original.identityNames().get(SPEAKER));
        var before = store.transcript(null, null, 0, 20); var memory = repository.memoryTranscript(PERSONA, "", null, false, 0, 20);
        var reviewed = repository.saveReviewedTags(message(1), 0, List.of(tag(CORRECTION, CharacterEvidenceKind.SPEAKER), tag(MENTION, CharacterEvidenceKind.MENTION)));
        assertTrue(reviewed.overridden()); assertEquals(1, reviewed.revision()); assertEquals(original.message(), reviewed.message());
        assertEquals(automatic, tags(reviewed.automaticTags()));
        assertEquals(Set.of(tag(CORRECTION, CharacterEvidenceKind.SPEAKER), tag(MENTION, CharacterEvidenceKind.MENTION)), tags(reviewed.effectiveTags()));
        assertEquals(automatic, tags(repository.reviewTags(message(2)).effectiveTags())); assertEquals(0, repository.reviewTags(message(2)).revision());
        assertEquals(List.of(2L), coordinates(repository.characterTranscript(SPEAKER, "", Channel.ROLEPLAY, CharacterEvidenceKind.SPEAKER, false, 0, 20)));
        assertEquals(List.of(1L), coordinates(repository.characterTranscript(CORRECTION, "", Channel.ROLEPLAY, CharacterEvidenceKind.SPEAKER, true, 0, 20)));
        assertEquals(List.of(1L, 2L), coordinates(repository.characterTranscript(MENTION, "", Channel.ROLEPLAY, CharacterEvidenceKind.MENTION, true, 0, 20)));
        assertEquals(before, store.transcript(null, null, 0, 20)); assertEquals(memory, repository.memoryTranscript(PERSONA, "", null, false, 0, 20));
        assertEquals(PERSONA, repository.activePersona()); assertEquals(1, repository.personas().size());
        assertEquals(new MemoryAssignment(message(1), PERSONA, SPEAKER, AttributionRole.SPEAKER), repository.assignment(message(1), PERSONA));
    }

    @Test void rejectAllAndRestoreAreDistinctAndRevisionTombstonesPreventStaleAbaWrites() throws Exception {
        fixture(); var repository = repository(); var automatic = repository.reviewTags(message(1));
        var pinned = repository.saveReviewedTags(message(1), 0, automatic.automaticTags());
        assertTrue(pinned.overridden()); assertEquals(1, pinned.revision(), "Saving identical suggestions explicitly pins them");
        String updated = text(characters(), "SELECT updated_at FROM message_tag_reviews WHERE source_line=1");
        assertEquals(pinned, repository.saveReviewedTags(message(1), 1, pinned.effectiveTags()));
        assertEquals(updated, text(characters(), "SELECT updated_at FROM message_tag_reviews WHERE source_line=1"));
        var rejected = repository.saveReviewedTags(message(1), 1, List.of());
        assertTrue(rejected.overridden()); assertEquals(2, rejected.revision()); assertTrue(rejected.effectiveTags().isEmpty());
        assertEquals(automatic.automaticTags(), rejected.automaticTags());
        assertEquals(List.of(2L), coordinates(repository.characterTranscript(SPEAKER, "", Channel.ROLEPLAY, CharacterEvidenceKind.SPEAKER, false, 0, 20)));
        var restored = repository.restoreAutomaticTags(message(1), 2);
        assertFalse(restored.overridden()); assertEquals(3, restored.revision()); assertEquals(automatic.automaticTags(), restored.effectiveTags());
        assertEquals(1, scalar(characters(), "SELECT count(*) FROM message_tag_reviews WHERE source_line=1 AND reviewed=0 AND revision=3"));
        assertEquals(0, scalar(characters(), "SELECT count(*) FROM message_tag_choices WHERE source_line=1"));
        assertEquals(restored, repository.restoreAutomaticTags(message(1), 3));
        assertThrows(SQLException.class, () -> repository.saveReviewedTags(message(1), 0, automatic.automaticTags()));
        assertThrows(SQLException.class, () -> repository.restoreAutomaticTags(message(1), 0));
        assertEquals(restored, repository.reviewTags(message(1)));
    }

    @Test void missingReferencesDuplicateTagsAndUnknownSpeakerClaimsFailWithoutPartialReviewWrites() throws Exception {
        fixture(); var repository = repository();
        var known = repository.saveReviewedTags(message(1), 0, List.of(tag(MENTION, CharacterEvidenceKind.MENTION)));
        assertThrows(IllegalArgumentException.class, () -> repository.saveReviewedTags(message(1), 1,
            List.of(tag(MENTION, CharacterEvidenceKind.MENTION), tag(MENTION, CharacterEvidenceKind.MENTION))));
        assertThrows(SQLException.class, () -> repository.saveReviewedTags(message(1), 1,
            List.of(tag("99999999-0000-0000-0000-000000000000", CharacterEvidenceKind.MENTION))));
        assertThrows(IllegalArgumentException.class, () -> repository.saveReviewedTags(message(1), 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.saveReviewedTags(message(1), -1, List.of()));
        assertEquals(known, repository.reviewTags(message(1)));
        for (var kind : List.of(CharacterEvidenceKind.SPEAKER, CharacterEvidenceKind.ACTION))
            assertThrows(IllegalArgumentException.class, () -> repository.saveReviewedTags(message(3), 0, List.of(tag(CORRECTION, kind))));
        var mention = repository.saveReviewedTags(message(3), 0, List.of(tag(CORRECTION, CharacterEvidenceKind.MENTION)));
        assertEquals(Channel.UNKNOWN, mention.message().channel()); assertEquals(Set.of(tag(CORRECTION, CharacterEvidenceKind.MENTION)), tags(mention.effectiveTags()));
        for (var missing : List.of(message(4), message(99), new MessageReference("0".repeat(64), 1))) {
            assertThrows(SQLException.class, () -> repository.reviewTags(missing));
            assertThrows(SQLException.class, () -> repository.saveReviewedTags(missing, 0, List.of()));
            assertThrows(SQLException.class, () -> repository.restoreAutomaticTags(missing, 0));
        }
        assertEquals(2, scalar(characters(), "SELECT count(*) FROM message_tag_reviews"));
        assertEquals(1, repository.personas().size()); assertNull(repository.assignment(message(3), PERSONA));
    }

    @Test void automaticSuggestionsMayExceedTheManualLimitButOnlySixtyFourUniqueChoicesCanBeSaved() throws Exception {
        var store = open(archive(), characters()); var repository = repository(); var choices = new ArrayList<CharacterTag>(); var names = new ArrayList<String>();
        for (int index = 0; index < 65; index++) {
            String id = String.format(java.util.Locale.ROOT, "60000000-0000-0000-0000-%012d", index), name = "Synthetic Mention" + index;
            repository.saveIdentity(identity(id, name)); choices.add(tag(id, CharacterEvidenceKind.MENTION)); names.add(name);
        }
        store.beginSource(new ImportSource(SOURCE_ID, "synthetic.log", "plain", 1));
        store.appendBatch(SOURCE_ID, 0, 1, List.of(new TranscriptLine(SOURCE_ID, 1, null, String.join(", ", names), Channel.UNKNOWN, null)), 0, 0, true);
        var initial = repository.reviewTags(message(1)); assertEquals(65, initial.automaticTags().size()); assertEquals(65, initial.effectiveTags().size());
        assertThrows(IllegalArgumentException.class, () -> repository.saveReviewedTags(message(1), 0, choices));
        assertEquals(initial, repository.reviewTags(message(1)));
        var saved = repository.saveReviewedTags(message(1), 0, choices.subList(0, 64)); assertEquals(64, saved.effectiveTags().size());
        assertThrows(IllegalArgumentException.class, () -> repository.saveReviewedTags(message(1), 1, choices));
        assertEquals(saved, repository.reviewTags(message(1)));
        assertEquals(65, repository.restoreAutomaticTags(message(1), 1).effectiveTags().size());
    }

    @Test void aliasReindexRestartAndPairedRestorePreserveManualChoicesAndTheirRevision() throws Exception {
        var store = fixture(); var repository = repository();
        var review = repository.saveReviewedTags(message(1), 0, List.of(tag(CORRECTION, CharacterEvidenceKind.SPEAKER), tag(MENTION, CharacterEvidenceKind.MENTION)));
        repository.saveIdentity(new CharacterIdentity(SPEAKER, "Synthetic Speaker", "Changed administrative note", Set.of("Synthetic Mention"), ""));
        var reindexed = repository.reviewTags(message(1)); assertEquals(1, reindexed.revision()); assertEquals(review.effectiveTags(), reindexed.effectiveTags());
        assertEquals(review.message(), reindexed.message()); assertTrue(reindexed.overridden());
        open(archive(), characters()); assertEquals(reindexed, repository.reviewTags(message(1)));
        Path backup = store.createBackup(directory.resolve("backups/review.sqlite"));
        repository.saveReviewedTags(message(1), 1, List.of());
        var restored = new SqliteJournalStore(backup, new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()); restored.initialize();
        var restoredRepository = new SqliteIdentityRepository(backup); assertEquals(reindexed, restoredRepository.reviewTags(message(1)));
        assertEquals(review.message(), restored.transcript(SOURCE_ID, null, 0, 20).entries().getFirst());
        assertEquals(PERSONA, restoredRepository.activePersona()); assertNotNull(restoredRepository.assignment(message(1), PERSONA));
    }

    @Test void choiceInsertionFailureRollsBackOldChoicesModeAndRevisionAndLeavesNoNewHeader() throws Exception {
        fixture(); var repository = repository(); var before = repository.saveReviewedTags(message(1), 0, List.of(tag(MENTION, CharacterEvidenceKind.MENTION)));
        sql(characters(), "CREATE TRIGGER synthetic_tag_choice_failure BEFORE INSERT ON message_tag_choices BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        try {
            assertThrows(SQLException.class, () -> repository.saveReviewedTags(message(1), 1, List.of(tag(CORRECTION, CharacterEvidenceKind.SPEAKER))));
            assertEquals(before, repository.reviewTags(message(1)));
            assertThrows(SQLException.class, () -> repository.saveReviewedTags(message(2), 0, List.of(tag(CORRECTION, CharacterEvidenceKind.ACTION))));
            var untouched = repository.reviewTags(message(2)); assertFalse(untouched.overridden()); assertEquals(0, untouched.revision());
            assertEquals(1, scalar(characters(), "SELECT count(*) FROM message_tag_reviews"));
        } finally { sql(characters(), "DROP TRIGGER synthetic_tag_choice_failure"); }
        assertEquals(2, repository.saveReviewedTags(message(1), 1, List.of(tag(CORRECTION, CharacterEvidenceKind.SPEAKER))).revision());
    }

    @Test void liveReceiptReplayAndLaterEventsDoNotReplaceAnExistingReviewedTagSet() throws Exception {
        var store = fixture(); var repository = repository(); var session = new LiveSession(LIVE_ID, Instant.parse("2026-10-27T12:00:00Z"), "UTC", "0".repeat(64));
        store.beginLiveSession(session);
        var event = new LiveEvent(LIVE_ID, 1, session.startedAt(), 0, LiveEvent.Kind.GAME,
            "Synthetic Speaker says: hello to Synthetic Mention.", Channel.ROLEPLAY, null);
        assertTrue(store.appendLiveEvent(session, event)); var reference = new MessageReference(LIVE_ID, 1);
        var reviewed = repository.saveReviewedTags(reference, 0, List.of(tag(CORRECTION, CharacterEvidenceKind.ACTION), tag(MENTION, CharacterEvidenceKind.MENTION)));
        assertFalse(store.appendLiveEvent(session, event));
        assertTrue(store.appendLiveEvent(session, new LiveEvent(LIVE_ID, 2, session.startedAt(), 0, event.kind(), event.text(), event.channel(), null)));
        assertEquals(reviewed, repository.reviewTags(reference));
        var related = repository.characterTranscript(CORRECTION, "", null, CharacterEvidenceKind.ACTION, false, 0, 20);
        assertEquals(1, related.total()); assertEquals(reference, MessageReference.of(related.entries().getFirst()));
        store.closeLiveSession(LIVE_ID); open(archive(), characters()); assertEquals(reviewed, repository.reviewTags(reference));
        assertNull(repository.assignment(reference, PERSONA)); assertEquals(1, repository.personas().size());
    }

    private record Attempt(MessageTagReview review, Exception failure) {}
    @Test @Timeout(20) void simultaneousEditsFromOneRevisionCannotSilentlyOverwriteEachOther() throws Exception {
        fixture(); var repository = repository(); var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { release.await(); try { return new Attempt(repository.saveReviewedTags(message(1), 0, List.of()), null); } catch (Exception failure) { return new Attempt(null, failure); } });
            var second = workers.submit(() -> { release.await(); try { return new Attempt(repository.saveReviewedTags(message(1), 0, List.of(tag(CORRECTION, CharacterEvidenceKind.ACTION))), null); } catch (Exception failure) { return new Attempt(null, failure); } });
            release.countDown(); var a = first.get(10, TimeUnit.SECONDS); var b = second.get(10, TimeUnit.SECONDS);
            assertNotEquals(a.failure() == null, b.failure() == null);
            var accepted = a.failure() == null ? a : b; var rejected = a.failure() == null ? b : a;
            assertInstanceOf(SQLException.class, rejected.failure()); assertEquals(1, accepted.review().revision());
            assertEquals(accepted.review(), repository.reviewTags(message(1)));
            assertThrows(SQLException.class, () -> repository.saveReviewedTags(message(1), 0, accepted.review().effectiveTags()), "Expected revision is checked even when choices already match");
        } finally { release.countDown(); }
    }

    @Test void batchUndoRemovesOverridesAndAutomaticTombstonesOnlyForDeletedCoordinates() throws Exception {
        var store = open(archive(), characters()); var repository = repository(); repository.saveIdentity(identity(SPEAKER, "Synthetic Speaker"));
        store.beginSource(new ImportSource(SOURCE_ID, "synthetic.log", "plain", 3));
        store.appendBatch(SOURCE_ID, 0, 1, List.of(new TranscriptLine(SOURCE_ID, 1, null, "Synthetic Speaker waves.", Channel.ROLEPLAY, null)), 0, 0, false);
        String batch = "70000000-0000-0000-0000-000000000001";
        store.createBatch(new BatchPlan(batch, directory.toString(), "0".repeat(64), Instant.now().toString(), Set.of(), List.of(
            new BatchFile(batch, 0, directory.resolve("synthetic.log").toString(), "synthetic.log", SOURCE_ID, "PENDING", null, 0, 0, 0, 0, 0))));
        store.finishBatch(batch, "RUNNING"); store.updateFile(batch, 0, "RUNNING", SOURCE_ID, null);
        store.appendBatchOwned(batch, 0, SOURCE_ID, 1, 3, List.of(
            new TranscriptLine(SOURCE_ID, 2, null, "Synthetic Speaker waves.", Channel.ROLEPLAY, null),
            new TranscriptLine(SOURCE_ID, 3, null, "Synthetic Speaker waves.", Channel.ROLEPLAY, null)), 0, 0, true);
        store.updateFile(batch, 0, "COMPLETE", SOURCE_ID, null); store.finishBatch(batch, "COMPLETE");
        repository.saveReviewedTags(message(1), 0, List.of()); repository.saveReviewedTags(message(2), 0, List.of(tag(SPEAKER, CharacterEvidenceKind.MENTION)));
        repository.saveReviewedTags(message(3), 0, List.of()); repository.restoreAutomaticTags(message(3), 1); store.setFavorite(message(2), true);
        var kept = repository.reviewTags(message(1)); var progress = store.progress(SOURCE_ID);
        var ownedReview = repository.reviewTags(message(2)); var automaticTombstone = repository.reviewTags(message(3));
        sql(archive(), "CREATE TRIGGER synthetic_review_undo_failure BEFORE DELETE ON transcript_lines BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        try {
            assertThrows(SQLException.class, () -> store.undoBatch(batch));
            assertEquals(kept, repository.reviewTags(message(1))); assertEquals(ownedReview, repository.reviewTags(message(2)));
            assertEquals(automaticTombstone, repository.reviewTags(message(3)), "Rollback preserves the AUTO marker and its ABA-protection revision");
            assertEquals(Set.of(message(2)), store.transcript(SOURCE_ID, null, 0, 20).favorites());
            assertEquals(progress, store.progress(SOURCE_ID)); assertEquals(0, store.removedRecords(SOURCE_ID));
            assertEquals("COMPLETE", store.batchSummary(batch).status());
            assertEquals(3, scalar(characters(), "SELECT count(*) FROM message_tag_reviews"));
            assertEquals(1, scalar(characters(), "SELECT count(*) FROM message_tag_choices"));
        } finally { sql(archive(), "DROP TRIGGER synthetic_review_undo_failure"); }
        assertEquals(2, store.undoBatch(batch)); open(archive(), characters());
        assertEquals(kept, repository.reviewTags(message(1))); assertEquals(1, scalar(characters(), "SELECT count(*) FROM message_tag_reviews"));
        assertEquals(0, scalar(characters(), "SELECT count(*) FROM message_tag_choices"));
        for (long coordinate : List.of(2L, 3L)) {
            assertThrows(SQLException.class, () -> repository.reviewTags(message(coordinate)));
            assertThrows(SQLException.class, () -> repository.saveReviewedTags(message(coordinate), 0, List.of()));
            assertThrows(SQLException.class, () -> repository.restoreAutomaticTags(message(coordinate), 0));
        }
        assertTrue(store.transcript(null, null, 0, 20).favorites().isEmpty()); assertEquals(progress, store.progress(SOURCE_ID));
        store.appendBatch(SOURCE_ID, 3, 3, List.of(), 0, 0, true);
        assertEquals(1, scalar(characters(), "SELECT count(*) FROM message_tag_reviews"));
        assertEquals(0, repository.characterTranscript(SPEAKER, "", null, null, false, 0, 20).total(), "The retained row's explicit rejection must also survive reindex/replay");
    }

    @Test void genuineCharacterSchemaTwoUpgradesWithAPreUpgradePairBackupAndPreservesDecisionsAndEvidence() throws Exception {
        var store = seed(archive(), characters()); var repository = repository(); repository.undoMerge(repository.merges().getFirst().id());
        String denied = repository.denyIdentityLink(FIRST, SECOND); var identities = repository.identities(""); var memories = repository.memoryTranscript(PERSONA_SECOND, "", null, false, 0, 20);
        var before = store.transcript(null, null, 0, 20); var progress = store.progress(SOURCE); characterVersion(characters(), 2);
        assertFalse(tables(characters()).contains("message_tag_reviews")); assertEquals(2, scalar(characters(), "PRAGMA user_version"));
        var upgraded = open(archive(), characters()); var upgradedRepository = repository();
        assertEquals(9, scalar(archive(), "PRAGMA user_version")); assertEquals(5, scalar(characters(), "PRAGMA user_version"));
        assertEquals(before, upgraded.transcript(null, null, 0, 20)); assertEquals(progress, upgraded.progress(SOURCE));
        assertEquals(identities, upgradedRepository.identities("")); assertEquals(memories, upgradedRepository.memoryTranscript(PERSONA_SECOND, "", null, false, 0, 20));
        assertEquals(PERSONA_SECOND, upgradedRepository.activePersona()); assertEquals(denied, upgradedRepository.separations().getFirst().id());
        assertThrows(IllegalArgumentException.class, () -> upgradedRepository.mergeIdentity(FIRST, SECOND));
        assertFalse(upgradedRepository.reviewTags(ref(1)).overridden()); assertEquals(0, upgradedRepository.reviewTags(ref(1)).revision());
        List<Path> backups;
        try (var files = Files.list(directory.resolve("backups"))) { backups = files.filter(path -> path.getFileName().toString().endsWith(".sqlite") && !path.getFileName().toString().endsWith(".characters.sqlite")).toList(); }
        assertEquals(1, backups.size()); Path backup = backups.getFirst(), companion = backup.resolveSibling(backup.getFileName() + ".characters.sqlite");
        assertEquals(2, scalar(companion, "PRAGMA user_version")); assertFalse(tables(companion).contains("message_tag_reviews"));
        assertEquals(2, scalar(companion, "SELECT count(*) FROM memory_assignments")); assertEquals(1, scalar(companion, "SELECT count(*) FROM identity_separations WHERE active=1"));
        assertEquals(2, scalar(backup, "SELECT count(*) FROM message_favorites"));
        assertTrue(Files.isRegularFile(backup.resolveSibling(backup.getFileName() + ".pair.json")));
        open(archive(), characters()); try (var files = Files.list(directory.resolve("backups"))) { assertEquals(3, files.count()); }
    }
}
