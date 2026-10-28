package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.kat.lotcjournal.storage.PairedDatabaseFixtures.scalar;
import static org.junit.jupiter.api.Assertions.*;

class SceneQualificationTest {
    private static final String FIRST = "60000000-0000-0000-0000-000000000001", SECOND = "60000000-0000-0000-0000-000000000002";
    private static final String PERSONA = "70000000-0000-0000-0000-000000000001";
    @TempDir Path directory;
    private Path archive() { return directory.resolve("archive.sqlite"); }
    private Path characters() { return directory.resolve("characters.sqlite"); }
    private SqliteIdentityRepository identities() { return new SqliteIdentityRepository(archive(), characters()); }
    private SqliteSceneRepository scenes() { return new SqliteSceneRepository(archive(), characters()); }
    private static String source(char symbol) { return String.valueOf(symbol).repeat(64); }
    private static MessageReference ref(char symbol, long coordinate) { return new MessageReference(source(symbol), coordinate); }
    private SqliteJournalStore empty() throws Exception {
        var store = PairedDatabaseFixtures.open(archive(), characters());
        identities().saveIdentity(new CharacterIdentity(FIRST, "Synthetic First", "", Set.of(), ""));
        identities().saveIdentity(new CharacterIdentity(SECOND, "Synthetic Second", "", Set.of(), ""));
        return store;
    }
    private static List<TranscriptLine> rows(char symbol, int count, boolean twoActors) {
        var result = new ArrayList<TranscriptLine>();
        for (int index = 0; index < count; index++) result.add(new TranscriptLine(source(symbol), index + 1,
            "12:00:" + String.format("%02d", index), (twoActors && index % 2 == 1 ? "Synthetic Second" : "Synthetic First") + " says: repeated synthetic dialogue.", Channel.ROLEPLAY, null));
        return result;
    }
    private void append(SqliteJournalStore store, char symbol, List<TranscriptLine> rows) throws Exception {
        store.beginSource(new ImportSource(source(symbol), "synthetic-" + symbol + ".log", "plain", rows.size()));
        store.appendBatch(source(symbol), 0, rows.size(), rows, 0, 0, true);
    }
    private SceneDetail containing(char symbol, long coordinate) throws Exception {
        for (var summary : scenes().scenes(null, true, "", 0, 100).entries())
            if (scenes().transcript(summary.id(), null, "", 0, 200).entries().stream().map(MessageReference::of).anyMatch(ref(symbol, coordinate)::equals))
                return scenes().scene(summary.id(), null);
        throw new AssertionError("No visible scene contains the requested synthetic coordinate");
    }
    private void sql(Path path, String command) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) { statement.execute(command); }
    }

    @Test void sharedScenesRequireTenRpCoordinatesAndTwoDistinctActorGroupsRatherThanMentionsOrTagRows() throws Exception {
        var store = empty(); append(store, 'a', rows('a', 10, true)); append(store, 'b', rows('b', 9, true));
        append(store, 'c', rows('c', 10, false));
        var mentionOnly = rows('d', 10, false);
        for (int index = 0; index < mentionOnly.size(); index++) {
            var line = mentionOnly.get(index); mentionOnly.set(index, new TranscriptLine(line.sourceId(), line.sourceLine(), line.clock(), line.text() + " Synthetic Second is mentioned.", line.channel(), null));
        }
        append(store, 'd', mentionOnly); append(store, 'e', rows('e', 10, false));
        for (long coordinate = 1; coordinate <= 10; coordinate++) identities().saveReviewedTags(ref('e', coordinate), 0,
            List.of(new CharacterTag(FIRST, CharacterEvidenceKind.SPEAKER), new CharacterTag(FIRST, CharacterEvidenceKind.ACTION)));
        var before = store.transcript(null, null, 0, 200); scenes().refreshSuggestions();
        assertEquals(1, scenes().scenes(null, false, "", 0, 100).total());
        var qualified = containing('a', 1); assertEquals(10, qualified.summary().messageCount()); assertEquals(SceneState.CONFIRMED, qualified.summary().state());
        assertEquals(SceneOrigin.AUTO, qualified.summary().origin()); assertTrue(qualified.assessment().eligible()); assertTrue(qualified.assessment().highConfidence());
        assertEquals(2, qualified.assessment().participantCount()); assertEquals(10, scenes().transcript(qualified.summary().id(), null, "", 0, 200).entries().size());
        assertEquals(before, store.transcript(null, null, 0, 200)); assertTrue(identities().personas().isEmpty());
    }

    @Test void unresolvedMultipleCrowdedAndLongGapEvidenceStaysSuggestedInsteadOfAutomaticallyConfirmed() throws Exception {
        var store = empty(); append(store, 'a', rows('a', 10, true)); append(store, 'b', rows('b', 10, true));
        append(store, 'c', rows('c', 10, true)); var gap = rows('d', 10, true);
        for (int index = 5; index < gap.size(); index++) {
            var line = gap.get(index); gap.set(index, new TranscriptLine(line.sourceId(), line.sourceLine(), "12:06:" + String.format("%02d", index), line.text(), line.channel(), null));
        }
        append(store, 'd', gap);
        identities().saveReviewedTags(ref('a', 10), 0, List.of());
        identities().saveReviewedTags(ref('b', 10), 0, List.of(new CharacterTag(FIRST, CharacterEvidenceKind.SPEAKER), new CharacterTag(SECOND, CharacterEvidenceKind.SPEAKER)));
        for (int index = 3; index <= 9; index++) {
            String id = "60000000-0000-0000-0000-" + String.format("%012d", index);
            identities().saveIdentity(new CharacterIdentity(id, "Synthetic Crowd " + index, "", Set.of(), ""));
            identities().saveReviewedTags(ref('c', index), 0, List.of(new CharacterTag(id, CharacterEvidenceKind.ACTION)));
        }
        scenes().refreshSuggestions(); assertEquals(4, scenes().scenes(null, false, true, "", 0, 100).total());
        for (char symbol : List.of('a', 'b', 'c', 'd')) {
            var detail = containing(symbol, 1); assertEquals(SceneState.SUGGESTED, detail.summary().state()); assertEquals(SceneOrigin.AUTO, detail.summary().origin());
            assertTrue(detail.assessment().eligible()); assertFalse(detail.assessment().highConfidence()); assertFalse(detail.assessment().reasons().isEmpty());
        }
        assertEquals(9, containing('c', 1).assessment().participantCount());
    }

    @Test void autoConfirmedWindowsGrowAndReassessWhileExplicitUnchangedSaveAndSkipRemainProtected() throws Exception {
        var store = empty(); store.beginSource(new ImportSource(source('a'), "synthetic-growing.log", "plain", 12));
        store.appendBatch(source('a'), 0, 10, rows('a', 10, true), 0, 0, false); append(store, 'b', rows('b', 10, true)); append(store, 'c', rows('c', 10, true));
        var repository = scenes(); repository.refreshSuggestions(); var growing = containing('a', 1); var manual = containing('b', 1); var skipped = containing('c', 1);
        assertEquals(SceneState.CONFIRMED, growing.summary().state()); assertEquals(SceneOrigin.AUTO, growing.summary().origin());
        manual = repository.saveScene(manual.summary().id(), manual.summary().revision(), manual.summary().title(), manual.summary().location(), manual.participants());
        assertEquals(SceneOrigin.MANUAL, manual.summary().origin()); repository.skipScene(skipped.summary().id(), skipped.summary().revision());
        var skippedSaved = repository.scene(skipped.summary().id(), null);
        store.appendBatch(source('a'), 10, 12, rows('a', 12, true).subList(10, 12), 0, 0, true); repository.refreshSuggestions();
        var grown = containing('a', 1); assertEquals(growing.summary().id(), grown.summary().id()); assertEquals(12, grown.summary().messageCount());
        assertTrue(grown.summary().revision() > growing.summary().revision());
        assertThrows(SQLException.class, () -> repository.skipScene(growing.summary().id(), growing.summary().revision()));
        identities().saveReviewedTags(ref('a', 12), 0, List.of()); repository.refreshSuggestions(); var uncertain = containing('a', 1);
        assertEquals(SceneState.SUGGESTED, uncertain.summary().state()); assertEquals(SceneOrigin.AUTO, uncertain.summary().origin());
        identities().mergeIdentity(FIRST, SECOND); repository.refreshSuggestions();
        assertEquals(2, repository.scenes(null, true, "", 0, 100).total(), "One resolved group hides only automatic ineligible windows");
        assertThrows(SQLException.class, () -> repository.scene(grown.summary().id(), null));
        assertEquals(manual.summary(), repository.scene(manual.summary().id(), null).summary());
        assertEquals(manual.participants(), repository.scene(manual.summary().id(), null).participants());
        assertEquals(skippedSaved.summary(), repository.scene(skippedSaved.summary().id(), null).summary());
    }

    @Test void globalQualificationChangesCannotHidePersonaOwnedWindowOrRevealSharedConfidence() throws Exception {
        var store = empty(); append(store, 'a', rows('a', 10, true));
        identities().createPersona(new Persona(PERSONA, FIRST, "Synthetic persona"));
        for (long coordinate = 1; coordinate <= 10; coordinate++) identities().assign(new MemoryAssignment(ref('a', coordinate), PERSONA,
            coordinate % 2 == 1 ? FIRST : SECOND, AttributionRole.SPEAKER));
        var repository = scenes(); repository.refreshSuggestions(); var shared = containing('a', 1); var before = repository.scene(shared.summary().id(), PERSONA);
        assertEquals(SceneAssessment.unassessed(), before.assessment()); assertEquals(SceneOrigin.MANUAL, before.summary().origin());
        identities().mergeIdentity(FIRST, SECOND); repository.refreshSuggestions();
        assertEquals(0, repository.scenes(null, false, "", 0, 100).total());
        assertEquals(1, repository.scenes(PERSONA, false, "", 0, 100).total()); var after = repository.scene(shared.summary().id(), PERSONA);
        assertEquals(before.participants(), after.participants()); assertEquals(before.statistics(), after.statistics()); assertEquals(before.assessment(), after.assessment());
        assertEquals(before.summary().title(), after.summary().title()); assertEquals(before.summary().location(), after.summary().location());
        assertEquals(before.summary().state(), after.summary().state()); assertEquals(before.summary().origin(), after.summary().origin());
        assertEquals(before.summary().messageCount(), after.summary().messageCount()); assertEquals(before.summary().id(), after.summary().id());
        assertEquals(10, repository.transcript(shared.summary().id(), PERSONA, "", 0, 200).total());
        assertThrows(SQLException.class, () -> repository.scene(shared.summary().id(), null));
    }

    @Test void sceneListPrioritizesSuggestionsThenNewerFirstAndIntersectsFilterSearchAndPagination() throws Exception {
        var store = empty(); append(store, 'a', rows('a', 10, true)); append(store, 'b', rows('b', 10, true)); append(store, 'c', rows('c', 10, true));
        identities().saveReviewedTags(ref('b', 10), 0, List.of()); var repository = scenes(); repository.refreshSuggestions();
        var first = containing('a', 1); var middle = containing('b', 1); var last = containing('c', 1);
        for (var entry : List.of(first, middle, last)) sql(characters(), "UPDATE scenes SET created_at='2026-10-" + (entry == first ? "25" : entry == middle ? "26" : "27") + "T12:00:00Z' WHERE id='" + entry.summary().id() + "'");
        assertEquals(List.of(middle.summary().id(), last.summary().id(), first.summary().id()), repository.scenes(null, true, "", 0, 100).entries().stream().map(SceneSummary::id).toList());
        assertEquals(List.of(last.summary().id()), repository.scenes(null, true, "", 1, 1).entries().stream().map(SceneSummary::id).toList());
        var suggestions = repository.scenes(null, true, true, "Synthetic Second", 0, 1);
        assertEquals(1, suggestions.total()); assertEquals(List.of(middle.summary().id()), suggestions.entries().stream().map(SceneSummary::id).toList());
        assertEquals(0, repository.scenes(null, true, true, "%_\\'", 0, 100).total());
        repository.skipScene(middle.summary().id(), middle.summary().revision()); assertEquals(0, repository.scenes(null, true, true, "", 0, 100).total());
        assertEquals(3, repository.scenes(null, true, false, "", 0, 100).total()); assertEquals(2, repository.scenes(null, false, false, "", 0, 100).total());
    }

    @Test void splitRejectsUndersizedOrSingleActorHalvesWithoutChangingOriginalOrFavorites() throws Exception {
        var store = empty(); append(store, 'a', rows('a', 20, true)); store.setFavorite(ref('a', 10), true);
        var repository = scenes(); repository.refreshSuggestions(); var original = containing('a', 1); var source = store.transcript(null, null, 0, 200);
        assertThrows(IllegalArgumentException.class, () -> repository.splitScene(original.summary().id(), original.summary().revision(), ref('a', 10)));
        assertEquals(original, repository.scene(original.summary().id(), null));
        for (long coordinate = 11; coordinate <= 20; coordinate++) identities().saveReviewedTags(ref('a', coordinate), 0, List.of(new CharacterTag(FIRST, CharacterEvidenceKind.SPEAKER)));
        repository.refreshSuggestions(); var updated = containing('a', 1);
        assertThrows(IllegalArgumentException.class, () -> repository.splitScene(updated.summary().id(), updated.summary().revision(), ref('a', 11)));
        assertEquals(updated, repository.scene(updated.summary().id(), null)); assertEquals(source, store.transcript(null, null, 0, 200));
        assertEquals(1, repository.scenes(null, true, "", 0, 100).total());
    }

    @Test void genuineSchemaFourUpgradeBacksUpOldShapeAndPreservesManualSmallScenesReviewsAndRevisionTombstones() throws Exception {
        var store = empty(); for (char symbol : List.of('a', 'b', 'c', 'd')) append(store, symbol, rows(symbol, 10, true));
        var repository = scenes(); repository.refreshSuggestions(); var automatic = containing('a', 1); var manual = containing('b', 1); var skipped = containing('c', 1); var inactive = containing('d', 1);
        manual = repository.saveScene(manual.summary().id(), manual.summary().revision(), "Saved manual title", "Saved place", manual.participants());
        repository.skipScene(skipped.summary().id(), skipped.summary().revision());
        identities().createPersona(new Persona(PERSONA, FIRST, "Synthetic persona")); identities().assign(new MemoryAssignment(ref('b', 1), PERSONA, FIRST, AttributionRole.SPEAKER));
        var review = identities().saveReviewedTags(ref('b', 2), 0, List.of(new CharacterTag(SECOND, CharacterEvidenceKind.MENTION)));
        String denial = identities().denyIdentityLink(FIRST, SECOND); store.setFavorite(ref('b', 1), true);
        append(store, 'e', rows('e', 2, true)); append(store, 'f', rows('f', 2, true));
        var before = store.transcript(null, null, 0, 200); var memory = identities().memoryTranscript(PERSONA, "", null, false, 0, 200);
        sql(characters(), "UPDATE scenes SET state='SUGGESTED',revision=8 WHERE id='" + automatic.summary().id() + "'");
        sql(characters(), "DELETE FROM scene_members WHERE scene_id='" + inactive.summary().id() + "'");
        sql(characters(), "UPDATE scenes SET active=0,state='CONFIRMED',revision=7 WHERE id='" + inactive.summary().id() + "'");
        PairedDatabaseFixtures.characterVersion(characters(), 4);
        String legacyFirst = "90000000-0000-0000-0000-000000000001", legacySecond = "90000000-0000-0000-0000-000000000002";
        for (var pair : List.of(new String[] {legacyFirst, "e"}, new String[] {legacySecond, "f"})) {
            sql(characters(), "INSERT INTO scenes VALUES ('" + pair[0] + "','Legacy reviewed small scene','Legacy place','CONFIRMED',1,9,0,'2026-10-01T12:00:00Z','2026-10-01T12:00:00Z')");
            for (int coordinate = 1; coordinate <= 2; coordinate++) sql(characters(), "INSERT INTO scene_members VALUES ('" + pair[0] + "'," + (coordinate - 1) + ",'" + source(pair[1].charAt(0)) + "'," + coordinate + ")");
        }
        assertEquals(0, scalar(characters(), "SELECT count(*) FROM pragma_table_info('scenes') WHERE name IN ('origin','qualified')"));
        String preservedFields = "SELECT group_concat(value,'|') FROM (SELECT id||':'||title||':'||location||':'||state||':'||active||':'||revision||':'||participants_reviewed||':'||created_at||':'||updated_at AS value FROM scenes ORDER BY id)";
        String oldHeaders = PairedDatabaseFixtures.text(characters(), preservedFields);
        PairedDatabaseFixtures.open(archive(), characters());
        assertEquals(5, scalar(characters(), "PRAGMA user_version")); assertEquals(9, scalar(archive(), "PRAGMA user_version"));
        assertEquals(oldHeaders, PairedDatabaseFixtures.text(characters(), preservedFields));
        assertEquals("AUTO", PairedDatabaseFixtures.text(characters(), "SELECT origin FROM scenes WHERE id='" + automatic.summary().id() + "'"));
        assertEquals(0, scalar(characters(), "SELECT qualified FROM scenes WHERE id='" + automatic.summary().id() + "'"));
        assertEquals(5, scalar(characters(), "SELECT count(*) FROM scenes WHERE origin='MANUAL'"));
        assertEquals(before, store.transcript(null, null, 0, 200)); assertEquals(review, identities().reviewTags(ref('b', 2)));
        assertEquals(memory, identities().memoryTranscript(PERSONA, "", null, false, 0, 200)); assertEquals(denial, identities().separations().getFirst().id());
        assertEquals(2, repository.scene(legacyFirst, null).summary().messageCount()); assertEquals(9, repository.scene(legacyFirst, null).summary().revision());
        var listBefore = repository.scenes(null, true, "", 0, 100);
        assertThrows(IllegalArgumentException.class, () -> repository.combineScenes(legacyFirst, 9, legacySecond, 9));
        assertEquals(listBefore, repository.scenes(null, true, "", 0, 100)); assertEquals(oldHeaders, PairedDatabaseFixtures.text(characters(), preservedFields));
        repository.refreshSuggestions(); assertEquals(10, repository.scene(automatic.summary().id(), null).summary().messageCount());
        assertEquals(SceneOrigin.AUTO, repository.scene(automatic.summary().id(), null).summary().origin());
        assertEquals(SceneOrigin.MANUAL, repository.scene(legacyFirst, null).summary().origin());
        assertEquals(SceneState.SKIPPED, repository.scene(skipped.summary().id(), null).summary().state());
        assertThrows(SQLException.class, () -> repository.scene(inactive.summary().id(), null));
        List<Path> backups;
        try (var paths = Files.list(directory.resolve("backups"))) { backups = paths.filter(path -> path.getFileName().toString().endsWith(".sqlite") && !path.getFileName().toString().endsWith(".characters.sqlite")).toList(); }
        assertEquals(1, backups.size()); var backup = backups.getFirst(); var backupCharacters = backup.resolveSibling(backup.getFileName() + ".characters.sqlite");
        assertEquals(4, scalar(backupCharacters, "PRAGMA user_version")); assertEquals(oldHeaders, PairedDatabaseFixtures.text(backupCharacters, preservedFields));
        assertEquals(0, scalar(backupCharacters, "SELECT count(*) FROM pragma_table_info('scenes') WHERE name IN ('origin','qualified')"));
        assertEquals(1, scalar(backupCharacters, "SELECT count(*) FROM message_tag_reviews")); assertEquals(1, scalar(backupCharacters, "SELECT count(*) FROM memory_assignments"));
        assertEquals(1, scalar(backup, "SELECT count(*) FROM message_favorites"));
        Path restored = directory.resolve("restore/archive.sqlite"); Files.createDirectories(restored.getParent());
        Files.copy(backup, restored); Files.copy(backupCharacters, restored.resolveSibling(restored.getFileName() + ".characters.sqlite"));
        Files.copy(backup.resolveSibling(backup.getFileName() + ".pair.json"), restored.resolveSibling(restored.getFileName() + ".pair.json"));
        var restoredStore = new SqliteJournalStore(restored, new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()); restoredStore.initialize();
        assertEquals(before, restoredStore.transcript(null, null, 0, 200)); assertEquals(2, new SqliteSceneRepository(restored).scene(legacyFirst, null).summary().messageCount());
        assertEquals(4, scalar(backupCharacters, "PRAGMA user_version"), "Restoring a copy must preserve the verified old pair snapshot");
    }
}
