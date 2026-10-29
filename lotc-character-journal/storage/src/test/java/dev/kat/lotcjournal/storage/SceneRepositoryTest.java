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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SceneRepositoryTest {
    private static final String SOURCE = "a".repeat(64), OTHER = "b".repeat(64), LIVE = "c".repeat(64), NEXT_LIVE = "d".repeat(64);
    private static final String FIRST = "40000000-0000-0000-0000-000000000001", SECOND = "40000000-0000-0000-0000-000000000002",
        MENTION = "40000000-0000-0000-0000-000000000003", SILENT = "40000000-0000-0000-0000-000000000004";
    private static final String PERSONA_FIRST = "50000000-0000-0000-0000-000000000001", PERSONA_SECOND = "50000000-0000-0000-0000-000000000002", PERSONA_EMPTY = "50000000-0000-0000-0000-000000000003";
    @TempDir Path directory;
    private Path archive() { return directory.resolve("archive.sqlite"); }
    private Path characters() { return directory.resolve("characters.sqlite"); }
    private SqliteSceneRepository scenes() { return new SqliteSceneRepository(archive(), characters()); }
    private SqliteIdentityRepository identities() { return new SqliteIdentityRepository(archive(), characters()); }
    private static MessageReference ref(long coordinate) { return new MessageReference(SOURCE, coordinate); }
    private static List<Long> coordinates(TranscriptPage page) { return page.entries().stream().map(TranscriptLine::sourceLine).toList(); }
    private static Set<String> participants(SceneDetail scene) { return scene.participants().stream().map(SceneParticipant::identityId).collect(java.util.stream.Collectors.toSet()); }
    private static Set<String> sceneIds(ScenePage page) { return page.entries().stream().map(SceneSummary::id).collect(java.util.stream.Collectors.toSet()); }
    private static TranscriptLine line(long coordinate, String clock, String text, Channel channel) { return new TranscriptLine(SOURCE, coordinate, clock, text, channel, null); }
    private static List<Long> range(long first, long last) { return java.util.stream.LongStream.rangeClosed(first, last).boxed().toList(); }
    private static List<Long> firstCoordinates() { return range(1, 20); }
    private static List<Long> secondCoordinates() { var result = new ArrayList<>(range(21, 41)); result.remove(22L); return List.copyOf(result); }
    private static List<TranscriptLine> block(String source, long first, int count, String clock) {
        var entries = new ArrayList<TranscriptLine>();
        for (int index = 0; index < count; index++) entries.add(new TranscriptLine(source, first + index, clock,
            (index % 2 == 0 ? "Synthetic First" : "Synthetic Second") + " says: synthetic observation " + (first + index) + ".", Channel.ROLEPLAY, null));
        return entries;
    }
    private SqliteJournalStore empty() throws Exception {
        var store = PairedDatabaseFixtures.open(archive(), characters()); var identities = identities();
        var ids = List.of(FIRST, SECOND, MENTION, SILENT); var names = List.of("Synthetic First", "Synthetic Second", "Synthetic Mention", "Synthetic Silent");
        for (int index = 0; index < ids.size(); index++) identities.saveIdentity(new CharacterIdentity(ids.get(index), names.get(index), "", Set.of(), ""));
        return store;
    }
    private SqliteJournalStore fixture() throws Exception {
        // Both windows qualify under the reviewed 10-message/two-actor minimum.
        // Twenty observations also permit a meaningful split into qualifying halves.
        var store = empty(); store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 43));
        var entries = new ArrayList<>(List.of(
            line(1, "12:00:00", "Synthetic First says: hello Synthetic Mention.", Channel.ROLEPLAY),
            line(2, "12:02:00", "Synthetic Second waves.", Channel.ROLEPLAY), line(3, "12:05:00", "Synthetic First says: continued.", Channel.ROLEPLAY)));
        entries.addAll(block(SOURCE, 4, 17, "12:05:00"));
        entries.add(line(21, "12:30:00", "Synthetic Second says: later.", Channel.ROLEPLAY));
        entries.add(line(22, "12:30:30", "A note mentions Synthetic First.", Channel.UNKNOWN));
        entries.add(line(23, "12:31:00", "Synthetic Second nods.", Channel.ROLEPLAY));
        entries.addAll(block(SOURCE, 24, 18, "12:31:00"));
        store.appendBatch(SOURCE, 0, 43, entries, 2, 0, true);
        return store;
    }
    private SceneDetail containing(MessageReference reference) throws Exception {
        var repository = scenes();
        for (var summary : repository.scenes(null, true, "", 0, 100).entries()) {
            var page = repository.transcript(summary.id(), null, "", 0, 200);
            if (page.entries().stream().map(MessageReference::of).anyMatch(reference::equals)) return repository.scene(summary.id(), null);
        }
        throw new AssertionError("No scene contains the requested synthetic coordinate");
    }
    private void sql(Path path, String command) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path); var s = c.createStatement()) { s.execute(command); }
    }

    @Test void suggestionsUseTrustedRpClockGapsAndActorsWhileMentionsRemainSeparateUncertainEvidence() throws Exception {
        var store = fixture(); var before = store.transcript(null, null, 0, 200); var repository = scenes();
        assertEquals(SceneSettings.DEFAULT, repository.settings()); repository.refreshSuggestions();
        assertEquals(2, repository.scenes(null, false, "", 0, 100).total());
        var first = containing(ref(1)); assertEquals(SceneState.CONFIRMED, first.summary().state()); assertEquals("", first.summary().location());
        assertEquals(firstCoordinates(), coordinates(repository.transcript(first.summary().id(), null, "", 0, 200)));
        assertEquals(Set.of(FIRST, SECOND), participants(first)); assertFalse(participants(first).contains(MENTION));
        assertTrue(first.participants().stream().allMatch(value -> value.role() == EncounterRole.UNCERTAIN && value.perspectivePersonaId() == null));
        assertEquals(20, first.statistics().messages()); assertEquals(19, first.statistics().speechObservations());
        assertEquals(1, first.statistics().actionObservations()); assertEquals(1, first.statistics().mentionObservations());
        assertEquals(300L, first.statistics().elapsedSeconds()); assertEquals(120L, first.statistics().estimatedActiveSeconds());
        var second = containing(ref(21)); assertEquals(secondCoordinates(), coordinates(repository.transcript(second.summary().id(), null, "", 0, 200)));
        assertEquals(20, second.statistics().messages()); assertEquals(60L, second.statistics().elapsedSeconds());
        assertEquals(before, store.transcript(null, null, 0, 200)); assertTrue(identities().personas().isEmpty());
        var unchanged = repository.scene(first.summary().id(), null); repository.refreshSuggestions(); assertEquals(unchanged, repository.scene(first.summary().id(), null));
    }

    @Test void historicalMidnightMissingClocksAndLiveSessionOrInstantReversalStayDistinct() throws Exception {
        var store = empty(); store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 31));
        var historical = new ArrayList<>(block(SOURCE, 1, 10, "23:59:00"));
        historical.addAll(block(SOURCE, 11, 10, "00:01:00")); historical.add(line(21, null, "Synthetic First waves.", Channel.ROLEPLAY));
        historical.addAll(block(SOURCE, 22, 10, "00:02:00")); store.appendBatch(SOURCE, 0, 31, historical, 0, 0, true);
        store.beginSource(new ImportSource(OTHER, "synthetic-other.log", "plain", 10));
        store.appendBatch(OTHER, 0, 10, block(OTHER, 1, 10, "00:03:00"), 0, 0, true);
        var start = Instant.parse("2026-10-27T23:59:00Z"); var session = new LiveSession(LIVE, start, "UTC", "0".repeat(64)); store.beginLiveSession(session);
        for (int index = 1; index <= 20; index++) {
            Instant received = index <= 9 ? start : index == 10 ? start.plusSeconds(120) : start.minusSeconds(60);
            store.appendLiveEvent(session, new LiveEvent(LIVE, index, received, 0, LiveEvent.Kind.CHAT,
                (index % 2 == 0 ? "Synthetic First" : "Synthetic Second") + " waves.", Channel.ROLEPLAY, null));
        }
        store.closeLiveSession(LIVE); var next = new LiveSession(NEXT_LIVE, start.plusSeconds(121), "UTC", "0".repeat(64)); store.beginLiveSession(next);
        for (int index = 1; index <= 10; index++) store.appendLiveEvent(next, new LiveEvent(NEXT_LIVE, index, next.startedAt(), 0, LiveEvent.Kind.CHAT,
            (index % 2 == 0 ? "Synthetic First" : "Synthetic Second") + " waves.", Channel.ROLEPLAY, null));
        var repository = scenes(); repository.refreshSuggestions(); assertEquals(7, repository.scenes(null, false, "", 0, 100).total());
        assertEquals(range(1, 10), coordinates(repository.transcript(containing(ref(1)).summary().id(), null, "", 0, 200)));
        assertEquals(range(11, 20), coordinates(repository.transcript(containing(ref(11)).summary().id(), null, "", 0, 200)));
        assertEquals(range(22, 31), coordinates(repository.transcript(containing(ref(22)).summary().id(), null, "", 0, 200)));
        assertThrows(AssertionError.class, () -> containing(ref(21)), "An isolated unknown clock cannot form a ten-message scene");
        var live = containing(new MessageReference(LIVE, 1)); assertEquals(10, live.summary().messageCount()); assertEquals(120L, live.statistics().elapsedSeconds());
        assertEquals(range(1, 10), coordinates(repository.transcript(live.summary().id(), null, "", 0, 200)));
        assertEquals(10, containing(new MessageReference(LIVE, 11)).summary().messageCount());
        assertEquals(10, containing(new MessageReference(NEXT_LIVE, 1)).summary().messageCount());
    }

    @Test void skipAndConfirmedEditsSurviveRegenerationThresholdChangesReplayAndRestart() throws Exception {
        var store = fixture(); var repository = scenes(); repository.refreshSuggestions(); var skipped = containing(ref(1)); var second = containing(ref(21));
        repository.skipScene(skipped.summary().id(), skipped.summary().revision());
        var confirmed = repository.saveScene(second.summary().id(), second.summary().revision(), "Reviewed synthetic scene", "Manual location",
            List.of(new SceneParticipant(SECOND, "Synthetic Second", EncounterRole.UNCERTAIN, null)));
        var skippedSaved = repository.scene(skipped.summary().id(), null); assertEquals(SceneState.SKIPPED, skippedSaved.summary().state());
        repository.saveSettings(new SceneSettings(60)); repository.refreshSuggestions(); repository.refreshSuggestions();
        assertEquals(1, repository.scenes(null, false, "", 0, 100).total()); assertEquals(2, repository.scenes(null, true, "", 0, 100).total());
        assertEquals(skippedSaved, repository.scene(skipped.summary().id(), null)); assertEquals(confirmed, repository.scene(second.summary().id(), null));
        store.appendBatch(SOURCE, 43, 43, List.of(), 0, 0, true); PairedDatabaseFixtures.open(archive(), characters());
        var reopened = scenes(); reopened.refreshSuggestions(); assertEquals(new SceneSettings(60), reopened.settings());
        assertEquals(skippedSaved, reopened.scene(skipped.summary().id(), null)); assertEquals(confirmed, reopened.scene(second.summary().id(), null));
        assertThrows(SQLException.class, () -> reopened.skipScene(second.summary().id(), second.summary().revision()));
    }

    @Test void explicitOwnershipChangesSeparateSuggestionsAndGrowingMembershipRejectsOldRevisions() throws Exception {
        var store = empty(); var identities = identities(); var repository = scenes();
        identities.createPersona(new Persona(PERSONA_FIRST, FIRST, "First persona")); identities.createPersona(new Persona(PERSONA_SECOND, SECOND, "Second persona"));
        store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 21));
        store.appendBatch(SOURCE, 0, 20, block(SOURCE, 1, 20, "12:00:00"), 0, 0, false);
        for (long coordinate = 1; coordinate <= 20; coordinate++) identities.assign(new MemoryAssignment(ref(coordinate),
            coordinate <= 10 ? PERSONA_FIRST : PERSONA_SECOND, coordinate % 2 == 1 ? FIRST : SECOND, AttributionRole.SPEAKER));
        repository.refreshSuggestions(); assertEquals(2, repository.scenes(null, false, "", 0, 100).total());
        var first = containing(ref(1)); var growing = containing(ref(11));
        assertEquals(range(1, 10), coordinates(repository.transcript(first.summary().id(), null, "", 0, 200)));
        assertEquals(range(11, 20), coordinates(repository.transcript(growing.summary().id(), null, "", 0, 200)));
        store.appendBatch(SOURCE, 20, 21, block(SOURCE, 21, 1, "12:01:00"), 0, 0, true);
        identities.assign(new MemoryAssignment(ref(21), PERSONA_SECOND, FIRST, AttributionRole.SPEAKER)); repository.refreshSuggestions();
        var updated = containing(ref(11)); assertEquals(growing.summary().id(), updated.summary().id());
        assertTrue(updated.summary().revision() > growing.summary().revision());
        assertEquals(range(11, 21), coordinates(repository.transcript(updated.summary().id(), null, "", 0, 200)));
        assertEquals(first, repository.scene(first.summary().id(), null));
        assertThrows(SQLException.class, () -> repository.saveScene(growing.summary().id(), growing.summary().revision(), "Stale edit", "", List.of()));
        repository.refreshSuggestions(); assertEquals(updated, repository.scene(updated.summary().id(), null));
        assertEquals(21, store.transcript(null, null, 0, 200).total());
    }

    @Test void splitAndCombinePreserveExactCoordinatesOnceAndRejectOldSceneRevisions() throws Exception {
        var store = fixture(); store.setFavorite(ref(2), true); var before = store.transcript(null, null, 0, 200); var repository = scenes(); repository.refreshSuggestions();
        var original = containing(ref(1)); var split = repository.splitScene(original.summary().id(), original.summary().revision(), ref(11)); assertEquals(2, split.size());
        var parts = new ArrayList<List<Long>>(); var details = new ArrayList<SceneDetail>();
        for (String id : split) { parts.add(coordinates(repository.transcript(id, null, "", 0, 200))); details.add(repository.scene(id, null)); }
        assertEquals(Set.of(range(1, 10), range(11, 20)), Set.copyOf(parts)); assertTrue(details.stream().allMatch(value -> value.summary().state() == SceneState.CONFIRMED));
        assertThrows(SQLException.class, () -> repository.splitScene(original.summary().id(), original.summary().revision(), ref(11)));
        String combined = repository.combineScenes(split.get(0), details.get(0).summary().revision(), split.get(1), details.get(1).summary().revision());
        assertEquals(firstCoordinates(), coordinates(repository.transcript(combined, null, "", 0, 200)));
        assertEquals(SceneState.CONFIRMED, repository.scene(combined, null).summary().state()); repository.refreshSuggestions();
        assertEquals(2, repository.scenes(null, true, "", 0, 100).total());
        assertThrows(SQLException.class, () -> repository.combineScenes(split.get(0), details.get(0).summary().revision(), split.get(1), details.get(1).summary().revision()));
        assertEquals(before, store.transcript(null, null, 0, 200));
    }

    @Test void explicitCrossSourceCombinationPreservesProvenanceAndLeavesUnprovedDurationsUnknown() throws Exception {
        var store = fixture(); store.beginSource(new ImportSource(OTHER, "synthetic-other.log", "plain", 10));
        store.appendBatch(OTHER, 0, 10, block(OTHER, 1, 10, "12:00:00"), 0, 0, true);
        var repository = scenes(); repository.refreshSuggestions(); var first = containing(ref(1)); var other = containing(new MessageReference(OTHER, 1));
        String combined = repository.combineScenes(first.summary().id(), first.summary().revision(), other.summary().id(), other.summary().revision());
        var detail = repository.scene(combined, null); assertEquals(30, detail.summary().messageCount());
        assertNull(detail.statistics().elapsedSeconds()); assertNull(detail.statistics().estimatedActiveSeconds());
        var expected = new java.util.HashSet<MessageReference>(); firstCoordinates().forEach(value -> expected.add(ref(value)));
        range(1, 10).forEach(value -> expected.add(new MessageReference(OTHER, value)));
        assertEquals(expected, repository.transcript(combined, null, "", 0, 200).entries().stream().map(MessageReference::of).collect(java.util.stream.Collectors.toSet()));
        assertEquals(51, store.transcript(null, null, 0, 200).total()); assertTrue(identities().personas().isEmpty());
    }

    @Test void personaViewsProjectAllMetadataCountsActorsAndRolesFromAssignedEvidenceOnly() throws Exception {
        var store = fixture(); var repository = scenes(); var identities = identities(); repository.refreshSuggestions(); var first = containing(ref(1));
        identities.createPersona(new Persona(PERSONA_FIRST, FIRST, "First persona")); identities.createPersona(new Persona(PERSONA_SECOND, SECOND, "Second persona"));
        identities.createPersona(new Persona(PERSONA_EMPTY, SILENT, "Unassigned persona"));
        var confirmed = repository.saveScene(first.summary().id(), first.summary().revision(), "SECRET_SHARED_SCENE_TITLE", "SECRET_SHARED_LOCATION", List.of(
            new SceneParticipant(FIRST, "Display name must not override identity", EncounterRole.DIRECT, PERSONA_FIRST),
            new SceneParticipant(SILENT, "Synthetic Silent", EncounterRole.UNCERTAIN, null)));
        assertEquals(Set.of(FIRST, SILENT), participants(confirmed)); assertEquals("Synthetic First", confirmed.participants().stream().filter(value -> value.identityId().equals(FIRST)).findFirst().orElseThrow().name());
        identities.assign(new MemoryAssignment(ref(1), PERSONA_FIRST, FIRST, AttributionRole.SPEAKER));
        identities.assign(new MemoryAssignment(ref(2), PERSONA_SECOND, SECOND, AttributionRole.SPEAKER)); repository.refreshSuggestions();
        var a = repository.scene(first.summary().id(), PERSONA_FIRST); var b = repository.scene(first.summary().id(), PERSONA_SECOND);
        for (var projection : List.of(a, b)) {
            assertEquals("Assigned scene", projection.summary().title()); assertEquals("", projection.summary().location());
            assertEquals(SceneState.SUGGESTED, projection.summary().state()); assertEquals(1, projection.summary().messageCount()); assertEquals(1, projection.statistics().messages());
        }
        assertEquals(Set.of(FIRST), participants(a)); assertEquals(EncounterRole.DIRECT, a.participants().getFirst().role()); assertEquals(PERSONA_FIRST, a.participants().getFirst().perspectivePersonaId());
        assertEquals(Set.of(SECOND), participants(b)); assertEquals(EncounterRole.UNCERTAIN, b.participants().getFirst().role()); assertNull(b.participants().getFirst().perspectivePersonaId());
        assertEquals(List.of(1L), coordinates(repository.transcript(first.summary().id(), PERSONA_FIRST, "", 0, 200)));
        assertEquals(List.of(2L), coordinates(repository.transcript(first.summary().id(), PERSONA_SECOND, "", 0, 200)));
        assertEquals(0, repository.scenes(PERSONA_FIRST, false, "SECRET_SHARED", 0, 100).total());
        assertEquals(0, repository.transcript(first.summary().id(), PERSONA_FIRST, "Synthetic Second", 0, 200).total());
        assertEquals(0, repository.scenes(PERSONA_EMPTY, true, "", 0, 100).total()); assertThrows(SQLException.class, () -> repository.scene(first.summary().id(), PERSONA_EMPTY));
        assertEquals(41, store.transcript(null, null, 0, 200).total());
        var sharedAfterOwnershipChange = repository.scene(first.summary().id(), null);
        assertEquals(confirmed.summary(), sharedAfterOwnershipChange.summary()); assertEquals(confirmed.participants(), sharedAfterOwnershipChange.participants());
        assertEquals(firstCoordinates(), coordinates(repository.transcript(first.summary().id(), null, "", 0, 200)));
        assertEquals(20, sharedAfterOwnershipChange.statistics().messages()); assertEquals(19, sharedAfterOwnershipChange.statistics().speechObservations());
        assertEquals(1, sharedAfterOwnershipChange.statistics().actionObservations()); assertEquals(1, sharedAfterOwnershipChange.statistics().mentionObservations());
        assertNull(sharedAfterOwnershipChange.statistics().elapsedSeconds(), "Mixed explicit ownership contexts no longer prove one elapsed timeline");
        assertNull(sharedAfterOwnershipChange.statistics().estimatedActiveSeconds());
        repository.skipScene(first.summary().id(), sharedAfterOwnershipChange.summary().revision());
        assertTrue(repository.scenes(null, false, "", 0, 100).entries().stream().noneMatch(value -> value.id().equals(first.summary().id())));
        for (String persona : List.of(PERSONA_FIRST, PERSONA_SECOND)) {
            assertTrue(repository.scenes(persona, false, "", 0, 100).entries().stream().anyMatch(value -> value.id().equals(first.summary().id())),
                "A shared skip must not hide explicitly assigned persona evidence");
        }
        var aAfterSkip = repository.scene(first.summary().id(), PERSONA_FIRST); var bAfterSkip = repository.scene(first.summary().id(), PERSONA_SECOND);
        assertEquals(a.statistics(), aAfterSkip.statistics()); assertEquals(a.participants(), aAfterSkip.participants());
        assertEquals(b.statistics(), bAfterSkip.statistics()); assertEquals(b.participants(), bAfterSkip.participants());
        assertEquals("Assigned scene", aAfterSkip.summary().title()); assertEquals("", aAfterSkip.summary().location()); assertEquals(SceneState.SUGGESTED, aAfterSkip.summary().state());
    }

    @Test void globalTagCorrectionsAndConfirmedLinksCannotIntroduceANewIdentityIntoPersonaParticipants() throws Exception {
        fixture(); var repository = scenes(); var identities = identities(); repository.refreshSuggestions(); var first = containing(ref(1));
        identities.createPersona(new Persona(PERSONA_FIRST, FIRST, "First persona")); identities.createPersona(new Persona(PERSONA_SECOND, SECOND, "Unresolved persona"));
        identities.createPersona(new Persona(PERSONA_EMPTY, FIRST, "Reference-only persona"));
        identities.assign(new MemoryAssignment(ref(1), PERSONA_FIRST, FIRST, AttributionRole.SPEAKER));
        identities.assign(new MemoryAssignment(ref(2), PERSONA_SECOND, null, AttributionRole.SPEAKER));
        identities.assign(new MemoryAssignment(ref(3), PERSONA_EMPTY, FIRST, AttributionRole.REFERENCED));
        var current = repository.scene(first.summary().id(), null);
        repository.saveScene(first.summary().id(), current.summary().revision(), "Shared secret title", "Shared secret location", List.of(
            new SceneParticipant(FIRST, "Synthetic First", EncounterRole.DIRECT, PERSONA_FIRST),
            new SceneParticipant(SILENT, "Synthetic Silent", EncounterRole.OVERHEARD, PERSONA_FIRST)));
        var before = repository.scene(first.summary().id(), PERSONA_FIRST);
        var unresolvedBefore = repository.scene(first.summary().id(), PERSONA_SECOND);
        var referenceBefore = repository.scene(first.summary().id(), PERSONA_EMPTY);
        assertEquals(1, before.statistics().speechObservations()); assertEquals(0, before.statistics().actionObservations()); assertEquals(0, before.statistics().mentionObservations());
        assertEquals(1, unresolvedBefore.statistics().actionObservations()); assertEquals(1, referenceBefore.statistics().mentionObservations());
        identities.saveReviewedTags(ref(1), 0, List.of(new CharacterTag(SILENT, CharacterEvidenceKind.SPEAKER), new CharacterTag(SECOND, CharacterEvidenceKind.ACTION)));
        identities.mergeIdentity(FIRST, SILENT); repository.refreshSuggestions();
        var projected = repository.scene(first.summary().id(), PERSONA_FIRST);
        assertEquals(Set.of(FIRST), participants(projected), "Only the exact explicitly attributed identity is persona knowledge");
        assertEquals("Synthetic First", projected.participants().getFirst().name()); assertEquals(EncounterRole.DIRECT, projected.participants().getFirst().role());
        assertEquals(PERSONA_FIRST, projected.participants().getFirst().perspectivePersonaId());
        assertEquals(before.statistics(), projected.statistics()); assertEquals(before.participants(), projected.participants());
        assertEquals("Assigned scene", projected.summary().title()); assertEquals("", projected.summary().location()); assertEquals(1, projected.statistics().messages());
        assertTrue(repository.scene(first.summary().id(), PERSONA_SECOND).participants().isEmpty(), "An unresolved speaker must not inherit the global actor tag");
        assertTrue(repository.scene(first.summary().id(), PERSONA_EMPTY).participants().isEmpty(), "A referenced identity is not a confirmed attendee");
        assertEquals(unresolvedBefore.statistics(), repository.scene(first.summary().id(), PERSONA_SECOND).statistics());
        assertEquals(referenceBefore.statistics(), repository.scene(first.summary().id(), PERSONA_EMPTY).statistics());
        assertEquals(new MemoryAssignment(ref(1), PERSONA_FIRST, FIRST, AttributionRole.SPEAKER), identities.assignment(ref(1), PERSONA_FIRST));
        assertEquals(SILENT, identities.resolveIdentity(FIRST));
    }

    @Test void participantNameSearchUsesEffectiveReviewsConfirmedRootsAndManualListsWithoutLeakingIntoPersonas() throws Exception {
        var store = fixture(); store.setFavorite(ref(1), true); var sourceBefore = store.transcript(null, null, 0, 200);
        var identities = identities(); var repository = scenes();
        var originalNames = List.of("Synthetic First", "Synthetic Second", "Synthetic Mention", "Synthetic Silent");
        var labels = List.of("Primary Review Label", "Second Review Label", "Canonical Review Label", "Replacement Review Label");
        var ids = List.of(FIRST, SECOND, MENTION, SILENT);
        for (int index = 0; index < ids.size(); index++) identities.saveIdentity(new CharacterIdentity(ids.get(index), labels.get(index), "", Set.of(originalNames.get(index)), ""));
        String manualAttendee = "40000000-0000-0000-0000-000000000099", manualLabel = "Manual Attendee Label";
        identities.saveIdentity(new CharacterIdentity(manualAttendee, manualLabel, "", Set.of(), ""));
        for (String label : labels) assertTrue(sourceBefore.entries().stream().noneMatch(line -> line.text().contains(label)), "Canonical search labels must be absent from dialogue so text matching cannot hide actor-search defects");
        identities.saveReviewedTags(ref(3), 0, List.of(new CharacterTag(SILENT, CharacterEvidenceKind.SPEAKER)));
        repository.refreshSuggestions(); var first = containing(ref(1)); var second = containing(ref(21));
        var both = Set.of(first.summary().id(), second.summary().id());
        assertEquals(both, sceneIds(repository.scenes(null, true, labels.get(0), 0, 100)));
        assertEquals(Set.of(first.summary().id()), sceneIds(repository.scenes(null, true, labels.get(3), 0, 100)));
        assertEquals(0, repository.scenes(null, true, labels.get(2), 0, 100).total(), "Mention-only evidence is not an attendee name");
        // Remove every primary actor observation in the first window. SECOND and
        // SILENT remain, keeping it qualified independently of the search under test.
        for (long coordinate : List.of(1L, 4L, 6L, 8L, 10L, 12L, 14L, 16L, 18L, 20L)) identities.saveReviewedTags(ref(coordinate), 0, List.of());
        repository.refreshSuggestions(); assertEquals(2, repository.scenes(null, true, "", 0, 100).total());
        assertEquals(Set.of(second.summary().id()), sceneIds(repository.scenes(null, true, labels.get(0), 0, 100)), "Reviewed-empty tags must suppress their automatic actor rows");
        var replaced = identities.saveReviewedTags(ref(1), 1, List.of(new CharacterTag(MENTION, CharacterEvidenceKind.ACTION)));
        assertEquals(Set.of(first.summary().id()), sceneIds(repository.scenes(null, true, labels.get(2), 0, 100)), "An explicit replacement contributes the chosen actor, not the old automatic actor");
        identities.restoreAutomaticTags(ref(1), replaced.revision());
        assertEquals(both, sceneIds(repository.scenes(null, true, labels.get(0), 0, 100)), "Restored AUTO revision markers must contribute automatic evidence again");
        assertEquals(0, repository.scenes(null, true, labels.get(2), 0, 100).total());
        identities.mergeIdentity(FIRST, MENTION); repository.refreshSuggestions();
        assertEquals(both, sceneIds(repository.scenes(null, true, labels.get(2), 0, 100)), "Shared automatic search uses the confirmed group's canonical name");
        assertEquals(0, repository.scenes(null, true, labels.get(0), 0, 100).total());
        var current = repository.scene(first.summary().id(), null);
        repository.saveScene(first.summary().id(), current.summary().revision(), "Manual scene", "", List.of(new SceneParticipant(manualAttendee, manualLabel, EncounterRole.UNCERTAIN, null)));
        assertEquals(Set.of(first.summary().id()), sceneIds(repository.scenes(null, true, manualLabel, 0, 100)));
        assertEquals(Set.of(second.summary().id()), sceneIds(repository.scenes(null, true, labels.get(2), 0, 100)), "An exact manual attendee list replaces automatic actors in shared search");
        assertEquals(0, repository.scenes(null, true, labels.get(3), 0, 100).total());
        identities.createPersona(new Persona(PERSONA_FIRST, FIRST, "First persona")); identities.createPersona(new Persona(PERSONA_SECOND, SECOND, "Second persona"));
        identities.assign(new MemoryAssignment(ref(1), PERSONA_FIRST, FIRST, AttributionRole.SPEAKER));
        identities.assign(new MemoryAssignment(ref(2), PERSONA_SECOND, SECOND, AttributionRole.SPEAKER));
        var review = identities.reviewTags(ref(1)); identities.saveReviewedTags(ref(1), review.revision(), List.of(new CharacterTag(SILENT, CharacterEvidenceKind.ACTION)));
        repository.refreshSuggestions();
        assertEquals(Set.of(first.summary().id()), sceneIds(repository.scenes(PERSONA_FIRST, false, labels.get(0), 0, 100)));
        assertEquals(Set.of(first.summary().id()), sceneIds(repository.scenes(PERSONA_SECOND, false, labels.get(1), 0, 100)));
        for (String hidden : List.of(labels.get(2), labels.get(3), manualLabel)) {
            assertEquals(0, repository.scenes(PERSONA_FIRST, true, hidden, 0, 100).total());
            assertEquals(0, repository.scenes(PERSONA_SECOND, true, hidden, 0, 100).total());
        }
        assertEquals(sourceBefore, store.transcript(null, null, 0, 200));
        assertEquals(2, PairedDatabaseFixtures.scalar(characters(), "SELECT count(*) FROM memory_assignments"));
        assertEquals(new MemoryAssignment(ref(1), PERSONA_FIRST, FIRST, AttributionRole.SPEAKER), identities.assignment(ref(1), PERSONA_FIRST));
    }

    @Test void failedUndoRollsBackSceneTrimmingThenSuccessfulUndoKeepsOnlyUnremovedMembersAndStalesOldEdits() throws Exception {
        var store = empty(); var repository = scenes(); String batch = "70000000-0000-0000-0000-000000000001";
        store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 12));
        store.appendBatch(SOURCE, 0, 1, block(SOURCE, 1, 1, "12:00:00"), 0, 0, false);
        store.createBatch(new BatchPlan(batch, directory.toString(), "0".repeat(64), Instant.now().toString(), Set.of(), List.of(
            new BatchFile(batch, 0, directory.resolve("synthetic.log").toString(), "synthetic.log", SOURCE, "PENDING", null, 0, 0, 0, 0, 0))));
        store.finishBatch(batch, "RUNNING"); store.updateFile(batch, 0, "RUNNING", SOURCE, null);
        store.appendBatchOwned(batch, 0, SOURCE, 1, 12, block(SOURCE, 2, 11, "12:01:00"), 0, 0, true);
        store.updateFile(batch, 0, "COMPLETE", SOURCE, null); store.finishBatch(batch, "COMPLETE"); store.setFavorite(ref(2), true);
        repository.refreshSuggestions(); var first = containing(ref(1));
        var saved = repository.saveScene(first.summary().id(), first.summary().revision(), "Keep manual metadata", "Manual place", List.of(new SceneParticipant(FIRST, "Synthetic First", EncounterRole.UNCERTAIN, null)));
        var before = store.transcript(null, null, 0, 200); var progress = store.progress(SOURCE);
        sql(archive(), "CREATE TRIGGER synthetic_scene_undo_failure BEFORE DELETE ON transcript_lines BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        try {
            assertThrows(SQLException.class, () -> store.undoBatch(batch)); assertEquals(saved, repository.scene(saved.summary().id(), null));
            assertEquals(range(1, 12), coordinates(repository.transcript(saved.summary().id(), null, "", 0, 200))); assertEquals(before, store.transcript(null, null, 0, 200));
        } finally { sql(archive(), "DROP TRIGGER synthetic_scene_undo_failure"); }
        assertEquals(11, store.undoBatch(batch)); var trimmed = repository.scene(saved.summary().id(), null);
        assertEquals(1, trimmed.summary().messageCount()); assertEquals("Keep manual metadata", trimmed.summary().title()); assertEquals("Manual place", trimmed.summary().location());
        assertTrue(trimmed.summary().revision() > saved.summary().revision()); assertEquals(List.of(1L), coordinates(repository.transcript(saved.summary().id(), null, "", 0, 200)));
        assertThrows(SQLException.class, () -> repository.saveScene(saved.summary().id(), saved.summary().revision(), "Stale title", "", List.of()));
        repository.refreshSuggestions(); assertEquals(1, repository.scenes(null, true, "", 0, 100).total());
        assertEquals(0, store.undoBatch(batch)); assertEquals(progress, store.progress(SOURCE)); assertTrue(store.transcript(null, null, 0, 200).favorites().isEmpty());
    }

    @Test void restartAndPairedBackupPreserveReviewedSkippedAndSettingsWithoutChangingSourceOrMemories() throws Exception {
        var store = fixture(); var repository = scenes(); repository.refreshSuggestions(); var first = containing(ref(1)); var second = containing(ref(21));
        var saved = repository.saveScene(first.summary().id(), first.summary().revision(), "Saved synthetic scene", "Manual place", List.of(new SceneParticipant(FIRST, "Synthetic First", EncounterRole.UNCERTAIN, null)));
        repository.skipScene(second.summary().id(), second.summary().revision()); var skipped = repository.scene(second.summary().id(), null); repository.saveSettings(new SceneSettings(120));
        Path backup = store.createBackup(directory.resolve("backups/scenes.sqlite")); var before = store.transcript(null, null, 0, 200);
        PairedDatabaseFixtures.open(archive(), characters()); scenes().refreshSuggestions(); assertEquals(saved, scenes().scene(saved.summary().id(), null));
        repository.saveScene(saved.summary().id(), saved.summary().revision(), "Later title", "", List.of());
        var restored = new SqliteJournalStore(backup, new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()); restored.initialize();
        var restoredScenes = new SqliteSceneRepository(backup); restoredScenes.refreshSuggestions();
        assertEquals(saved, restoredScenes.scene(saved.summary().id(), null)); assertEquals(skipped, restoredScenes.scene(skipped.summary().id(), null));
        assertEquals(new SceneSettings(120), restoredScenes.settings()); assertEquals(before, restored.transcript(null, null, 0, 200));
    }

    @Test void rejectedSceneEditsPreserveSavedMetadataMembershipParticipantsAndSourceExactly() throws Exception {
        var store = fixture(); store.setFavorite(ref(2), true); var repository = scenes(); var identities = identities();
        identities.createPersona(new Persona(PERSONA_FIRST, FIRST, "First persona")); repository.refreshSuggestions(); var first = containing(ref(1));
        var participants = List.of(new SceneParticipant(FIRST, "Synthetic First", EncounterRole.DIRECT, PERSONA_FIRST),
            new SceneParticipant(SECOND, "Synthetic Second", EncounterRole.UNCERTAIN, null));
        var saved = repository.saveScene(first.summary().id(), first.summary().revision(), "Saved manual title", "Saved manual place", participants);
        var source = store.transcript(null, null, 0, 200); var membership = repository.transcript(saved.summary().id(), null, "", 0, 200);
        var sceneList = repository.scenes(null, true, "", 0, 100);
        var invalidLists = List.of(
            List.of(new SceneParticipant(FIRST, "Synthetic First", EncounterRole.DIRECT, PERSONA_FIRST),
                new SceneParticipant(FIRST, "Synthetic First", EncounterRole.OVERHEARD, PERSONA_FIRST)),
            java.util.Collections.nCopies(65, new SceneParticipant(FIRST, "Synthetic First", EncounterRole.UNCERTAIN, null)));
        for (var invalid : invalidLists) {
            assertThrows(IllegalArgumentException.class, () -> repository.saveScene(saved.summary().id(), saved.summary().revision(), "Rejected title", "", invalid));
            assertEquals(saved, repository.scene(saved.summary().id(), null));
        }
        assertThrows(SQLException.class, () -> repository.saveScene(saved.summary().id(), saved.summary().revision(), "Rejected title", "", List.of(
            new SceneParticipant("99999999-0000-0000-0000-000000000000", "Missing synthetic identity", EncounterRole.UNCERTAIN, null))));
        for (String invalidTitle : List.of("", " ", "x".repeat(161)))
            assertThrows(IllegalArgumentException.class, () -> repository.saveScene(saved.summary().id(), saved.summary().revision(), invalidTitle, "", participants));
        assertThrows(IllegalArgumentException.class, () -> repository.saveScene(saved.summary().id(), saved.summary().revision(), "Valid title", "x".repeat(241), participants));
        assertThrows(SQLException.class, () -> repository.saveScene(saved.summary().id(), first.summary().revision(), saved.summary().title(), saved.summary().location(), participants),
            "Even an otherwise idempotent stale edit must fail before returning success");
        assertEquals(saved, repository.scene(saved.summary().id(), null)); assertEquals(membership, repository.transcript(saved.summary().id(), null, "", 0, 200));
        assertEquals(sceneList, repository.scenes(null, true, "", 0, 100)); assertEquals(source, store.transcript(null, null, 0, 200));
        assertEquals(1, identities.personas().size()); assertNull(identities.assignment(ref(1), PERSONA_FIRST));
    }

    @Test void failedSplitAndCombineRollBackPartiallyCreatedHeadersMembersAndParticipantsThenRetrySucceeds() throws Exception {
        var store = fixture(); for (long coordinate : List.of(1L, 2L, 21L)) store.setFavorite(ref(coordinate), true);
        var repository = scenes(); repository.refreshSuggestions(); var first = containing(ref(1)); var second = containing(ref(21));
        var firstSaved = repository.saveScene(first.summary().id(), first.summary().revision(), "First manual scene", "Manual place", List.of(
            new SceneParticipant(FIRST, "Synthetic First", EncounterRole.UNCERTAIN, null), new SceneParticipant(SECOND, "Synthetic Second", EncounterRole.UNCERTAIN, null)));
        var secondSaved = repository.saveScene(second.summary().id(), second.summary().revision(), "Second manual scene", "Second place", List.of(
            new SceneParticipant(SECOND, "Synthetic Second", EncounterRole.UNCERTAIN, null), new SceneParticipant(SILENT, "Synthetic Silent", EncounterRole.UNCERTAIN, null)));
        var originalList = repository.scenes(null, true, "", 0, 100); var source = store.transcript(null, null, 0, 200);
        long headersBefore = PairedDatabaseFixtures.scalar(characters(), "SELECT count(*) FROM scenes");
        String trigger = "CREATE TRIGGER synthetic_scene_membership_failure BEFORE INSERT ON scene_members WHEN NEW.source_id='" + SOURCE
            + "' AND NEW.source_line=11 BEGIN SELECT RAISE(ABORT,'synthetic membership failure'); END";
        sql(characters(), trigger);
        try {
            assertThrows(SQLException.class, () -> repository.splitScene(firstSaved.summary().id(), firstSaved.summary().revision(), ref(11)));
            assertEquals(firstSaved, repository.scene(firstSaved.summary().id(), null)); assertEquals(secondSaved, repository.scene(secondSaved.summary().id(), null));
            assertEquals(firstCoordinates(), coordinates(repository.transcript(firstSaved.summary().id(), null, "", 0, 200)));
            assertEquals(secondCoordinates(), coordinates(repository.transcript(secondSaved.summary().id(), null, "", 0, 200)));
            assertEquals(headersBefore, PairedDatabaseFixtures.scalar(characters(), "SELECT count(*) FROM scenes"));
            assertEquals(40, PairedDatabaseFixtures.scalar(characters(), "SELECT count(*) FROM scene_members"));
            assertEquals(originalList, repository.scenes(null, true, "", 0, 100)); assertEquals(source, store.transcript(null, null, 0, 200));
        } finally { sql(characters(), "DROP TRIGGER synthetic_scene_membership_failure"); }
        var split = repository.splitScene(firstSaved.summary().id(), firstSaved.summary().revision(), ref(11));
        var a = repository.scene(split.get(0), null); var b = repository.scene(split.get(1), null);
        var aMembers = repository.transcript(split.get(0), null, "", 0, 200); var bMembers = repository.transcript(split.get(1), null, "", 0, 200);
        var beforeCombineList = repository.scenes(null, true, "", 0, 100); long splitHeaders = PairedDatabaseFixtures.scalar(characters(), "SELECT count(*) FROM scenes");
        sql(characters(), trigger);
        try {
            assertThrows(SQLException.class, () -> repository.combineScenes(split.get(0), a.summary().revision(), split.get(1), b.summary().revision()));
            assertEquals(a, repository.scene(split.get(0), null)); assertEquals(b, repository.scene(split.get(1), null));
            assertEquals(aMembers, repository.transcript(split.get(0), null, "", 0, 200)); assertEquals(bMembers, repository.transcript(split.get(1), null, "", 0, 200));
            assertEquals(secondSaved, repository.scene(secondSaved.summary().id(), null));
            assertEquals(splitHeaders, PairedDatabaseFixtures.scalar(characters(), "SELECT count(*) FROM scenes"));
            assertEquals(40, PairedDatabaseFixtures.scalar(characters(), "SELECT count(*) FROM scene_members"));
            assertEquals(beforeCombineList, repository.scenes(null, true, "", 0, 100)); assertEquals(source, store.transcript(null, null, 0, 200));
        } finally { sql(characters(), "DROP TRIGGER synthetic_scene_membership_failure"); }
        String combined = repository.combineScenes(split.get(0), a.summary().revision(), split.get(1), b.summary().revision());
        assertEquals(firstCoordinates(), coordinates(repository.transcript(combined, null, "", 0, 200)));
        assertEquals(firstSaved.participants(), repository.scene(combined, null).participants()); assertEquals(2, repository.scenes(null, true, "", 0, 100).total());
        assertEquals(source, store.transcript(null, null, 0, 200));
    }

    @Test void genuineCharacterSchemaThreeUpgradeBacksUpExistingReviewsDenialsPersonasAndArchiveBeforeAddingScenes() throws Exception {
        var store = PairedDatabaseFixtures.seed(archive(), characters()); var identities = identities(); identities.undoMerge(identities.merges().getFirst().id());
        String denial = identities.denyIdentityLink(PairedDatabaseFixtures.FIRST, PairedDatabaseFixtures.SECOND);
        var review = identities.saveReviewedTags(PairedDatabaseFixtures.ref(1), 0, List.of(new CharacterTag(PairedDatabaseFixtures.FIRST, CharacterEvidenceKind.MENTION)));
        var before = store.transcript(null, null, 0, 200); var memory = identities.memoryTranscript(PairedDatabaseFixtures.PERSONA_SECOND, "", null, false, 0, 200);
        PairedDatabaseFixtures.characterVersion(characters(), 3); assertFalse(PairedDatabaseFixtures.tables(characters()).contains("scenes"));
        PairedDatabaseFixtures.open(archive(), characters());
        assertEquals(9, PairedDatabaseFixtures.scalar(archive(), "PRAGMA user_version")); assertEquals(5, PairedDatabaseFixtures.scalar(characters(), "PRAGMA user_version"));
        assertEquals(before, store.transcript(null, null, 0, 200)); assertEquals(review, identities.reviewTags(PairedDatabaseFixtures.ref(1)));
        assertEquals(memory, identities.memoryTranscript(PairedDatabaseFixtures.PERSONA_SECOND, "", null, false, 0, 200));
        assertEquals(denial, identities.separations().getFirst().id()); assertEquals(SceneSettings.DEFAULT, scenes().settings());
        List<Path> backups;
        try (var files = Files.list(directory.resolve("backups"))) { backups = files.filter(path -> path.getFileName().toString().endsWith(".sqlite") && !path.getFileName().toString().endsWith(".characters.sqlite")).toList(); }
        assertEquals(1, backups.size()); Path backup = backups.getFirst(), companion = backup.resolveSibling(backup.getFileName() + ".characters.sqlite");
        assertEquals(3, PairedDatabaseFixtures.scalar(companion, "PRAGMA user_version")); assertFalse(PairedDatabaseFixtures.tables(companion).contains("scenes"));
        assertEquals(1, PairedDatabaseFixtures.scalar(companion, "SELECT count(*) FROM message_tag_reviews"));
        assertEquals(1, PairedDatabaseFixtures.scalar(companion, "SELECT count(*) FROM identity_separations WHERE active=1"));
        assertEquals(2, PairedDatabaseFixtures.scalar(companion, "SELECT count(*) FROM memory_assignments")); assertEquals(2, PairedDatabaseFixtures.scalar(backup, "SELECT count(*) FROM message_favorites"));
        assertTrue(Files.isRegularFile(backup.resolveSibling(backup.getFileName() + ".pair.json")));
    }
}
