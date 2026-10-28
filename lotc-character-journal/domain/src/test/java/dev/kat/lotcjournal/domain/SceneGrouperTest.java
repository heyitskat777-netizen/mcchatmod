package dev.kat.lotcjournal.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SceneGrouperTest {
    private static final String SOURCE = "a".repeat(64), OTHER = "b".repeat(64);
    private static SceneObservation observation(String source, long line, Long time, String context) {
        return new SceneObservation(new TranscriptLine(source, line, null, "Synthetic Actor waves.", Channel.ROLEPLAY, null), time, context);
    }
    private static SceneObservation observation(long line, Long time) { return observation(SOURCE, line, time, "unassigned"); }

    @Test void inactivityThresholdIsInclusiveAndConfigurableWithoutChangingSourceObservations() {
        var observations = List.of(observation(1, 0L), observation(2, 900L), observation(3, 1801L));
        var grouped = SceneGrouper.group(observations, SceneSettings.DEFAULT);
        assertEquals(List.of(observations.subList(0, 2), observations.subList(2, 3)), grouped);
        assertEquals(3, SceneGrouper.group(observations, new SceneSettings(60)).size());
        assertEquals(1, SceneGrouper.group(observations, new SceneSettings(7200)).size());
        assertThrows(IllegalArgumentException.class, () -> new SceneSettings(59));
        assertThrows(IllegalArgumentException.class, () -> new SceneSettings(7201));
        assertThrows(UnsupportedOperationException.class, () -> grouped.clear());
        assertThrows(UnsupportedOperationException.class, () -> grouped.getFirst().clear());
    }

    @Test void unknownClocksClockReversalSourcesAndExplicitContextChangesCreateUncertainBoundaries() {
        for (var pair : List.of(
                List.of(observation(1, 0L), observation(2, null)),
                List.of(observation(1, null), observation(2, 1L)),
                List.of(observation(1, null), observation(2, null)),
                List.of(observation(1, 10L), observation(2, 9L)),
                List.of(observation(1, 0L), observation(OTHER, 1, 1L, "unassigned")),
                List.of(observation(1, 0L), observation(SOURCE, 2, 1L, "explicit-persona-change")))) {
            assertEquals(2, SceneGrouper.group(pair, SceneSettings.DEFAULT).size());
            assertNull(SceneGrouper.elapsed(pair)); assertNull(SceneGrouper.active(pair));
        }
        assertThrows(IllegalArgumentException.class, () -> new SceneObservation(
            new TranscriptLine(SOURCE, 1, null, "Synthetic unknown dialogue.", Channel.UNKNOWN, null), null, "unassigned"));
    }

    @Test void historicalMidnightDoesNotInventADateWhileExactLiveInstantsCanCrossMidnight() {
        var historical = List.of(observation(1, 86_340L), observation(2, 60L));
        assertEquals(2, SceneGrouper.group(historical, SceneSettings.DEFAULT).size());
        assertNull(SceneGrouper.elapsed(historical));
        long before = Instant.parse("2026-10-27T23:59:00Z").getEpochSecond(), after = Instant.parse("2026-10-28T00:01:00Z").getEpochSecond();
        var live = List.of(observation(1, before), observation(2, after));
        assertEquals(1, SceneGrouper.group(live, SceneSettings.DEFAULT).size());
        assertEquals(120L, SceneGrouper.elapsed(live)); assertEquals(60L, SceneGrouper.active(live));
    }

    @Test void repeatedTextKeepsDistinctCoordinatesAndLargeWindowsRemainBounded() {
        var observations = new ArrayList<SceneObservation>();
        for (long coordinate = 1; coordinate <= 5001; coordinate++) observations.add(observation(coordinate, coordinate));
        var grouped = SceneGrouper.group(observations, SceneSettings.DEFAULT);
        assertEquals(List.of(5000, 1), grouped.stream().map(List::size).toList());
        assertEquals(observations, grouped.stream().flatMap(List::stream).toList());
        assertEquals(5001, grouped.stream().flatMap(List::stream).map(value -> MessageReference.of(value.message())).distinct().count());
        assertThrows(IllegalArgumentException.class, () -> SceneGrouper.group(List.of(observation(1, 0L), observation(1, 1L)), SceneSettings.DEFAULT));
    }

    @Test void elapsedAndActiveEstimatesStayUnknownWithoutCompleteMonotonicContext() {
        var observations = List.of(observation(1, 0L), observation(2, 120L), observation(3, 150L));
        assertEquals(150L, SceneGrouper.elapsed(observations)); assertEquals(90L, SceneGrouper.active(observations));
        assertNull(SceneGrouper.elapsed(List.of())); assertNull(SceneGrouper.active(List.of()));
        assertEquals(0L, SceneGrouper.elapsed(List.of(observation(1, 0L)))); assertEquals(0L, SceneGrouper.active(List.of(observation(1, 0L))));
        var impossibleRange = List.of(observation(1, Long.MIN_VALUE), observation(2, Long.MAX_VALUE));
        assertEquals(2, SceneGrouper.group(impossibleRange, SceneSettings.DEFAULT).size());
        assertNull(SceneGrouper.elapsed(impossibleRange)); assertNull(SceneGrouper.active(impossibleRange));
    }

    @Test void sceneSnapshotsCopyParticipantListsAndDirectClaimsRequireAnExplicitPersonaPerspective() {
        String scene = UUID.randomUUID().toString(), actor = UUID.randomUUID().toString(), persona = UUID.randomUUID().toString();
        var participants = new ArrayList<>(List.of(new SceneParticipant(actor, "Synthetic Actor", EncounterRole.UNCERTAIN, null)));
        var summary = new SceneSummary(scene, "Suggested scene", "", SceneState.SUGGESTED, 1, 1);
        var detail = new SceneDetail(summary, participants, new SceneStatistics(1, 1, 0, 0, null, null)); participants.clear();
        assertEquals(1, detail.participants().size()); assertThrows(UnsupportedOperationException.class, () -> detail.participants().clear());
        assertThrows(IllegalArgumentException.class, () -> new SceneParticipant(actor, "Synthetic Actor", EncounterRole.DIRECT, null));
        assertThrows(IllegalArgumentException.class, () -> new SceneParticipant(actor, "Synthetic Actor", EncounterRole.OVERHEARD, null));
        assertEquals(persona, new SceneParticipant(actor, "Synthetic Actor", EncounterRole.DIRECT, persona).perspectivePersonaId());
        assertThrows(IllegalArgumentException.class, () -> new SceneDetail(summary, List.of(), new SceneStatistics(2, 1, 0, 0, null, null)));
    }
}
