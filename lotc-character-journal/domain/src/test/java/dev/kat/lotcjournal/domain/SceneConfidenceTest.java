package dev.kat.lotcjournal.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SceneConfidenceTest {
    private static final String SOURCE = "a".repeat(64);
    private static String actor(int number) { return "40000000-0000-0000-0000-" + String.format("%012d", number); }
    private static List<SceneObservation> observations(int count) {
        var result = new ArrayList<SceneObservation>();
        for (int index = 0; index < count; index++) result.add(new SceneObservation(
            new TranscriptLine(SOURCE, index + 1, "12:00:00", "Synthetic Actor says: repeated observation.", Channel.ROLEPLAY, null), (long) index * 10, "same-context"));
        return result;
    }
    private static List<Set<String>> actors(int count, int distinct) {
        var result = new ArrayList<Set<String>>();
        for (int index = 0; index < count; index++) result.add(Set.of(actor(index % distinct + 1)));
        return result;
    }

    @Test void exactlyTenMessagesAndTwoDistinctResolvedActorsAreRequiredForAQualifiedScene() {
        assertFalse(SceneConfidence.assess(observations(9), actors(9, 2)).eligible());
        assertFalse(SceneConfidence.assess(observations(10), actors(10, 1)).eligible());
        var qualified = SceneConfidence.assess(observations(10), actors(10, 2));
        assertTrue(qualified.eligible()); assertTrue(qualified.highConfidence()); assertEquals(2, qualified.participantCount());
        assertTrue(qualified.reasons().isEmpty());
        // Repeating one resolved group across every coordinate never invents a second actor.
        assertEquals(1, SceneConfidence.assess(observations(30), actors(30, 1)).participantCount());
    }

    @Test void anUnresolvedOrMultiplyAttributedMessageKeepsAnOtherwiseEligibleWindowInReview() {
        var unresolved = actors(10, 2); unresolved.set(9, Set.of());
        var unknownAssessment = SceneConfidence.assess(observations(10), unresolved);
        assertTrue(unknownAssessment.eligible()); assertFalse(unknownAssessment.highConfidence()); assertEquals(2, unknownAssessment.participantCount());
        var multiple = actors(10, 2); multiple.set(9, Set.of(actor(1), actor(2)));
        var multipleAssessment = SceneConfidence.assess(observations(10), multiple);
        assertTrue(multipleAssessment.eligible()); assertFalse(multipleAssessment.highConfidence()); assertFalse(multipleAssessment.reasons().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> multipleAssessment.reasons().clear());
    }

    @Test void unknownClockContextChangesAndLongGapsStayUncertainWhileTheFiveMinuteBoundaryIsInclusive() {
        var unknown = observations(10); var old = unknown.get(5);
        unknown.set(5, new SceneObservation(old.message(), null, old.boundaryKey()));
        assertFalse(SceneConfidence.assess(unknown, actors(10, 2)).highConfidence());
        var changedContext = observations(10); old = changedContext.get(5);
        changedContext.set(5, new SceneObservation(old.message(), old.timelineSeconds(), "different-persona-context"));
        assertFalse(SceneConfidence.assess(changedContext, actors(10, 2)).highConfidence());
        for (int gap : List.of(300, 301)) {
            var rows = observations(10);
            for (int index = 5; index < rows.size(); index++) {
                var value = rows.get(index); rows.set(index, new SceneObservation(value.message(), value.timelineSeconds() + gap - 10, value.boundaryKey()));
            }
            var assessment = SceneConfidence.assess(rows, actors(10, 2));
            assertTrue(assessment.eligible()); assertEquals(gap == 300, assessment.highConfidence());
        }
    }

    @Test void crowdedWindowsRemainSuggestionsWithoutClaimingWhichPeopleConversed() {
        assertTrue(SceneConfidence.assess(observations(16), actors(16, 8)).highConfidence());
        var crowded = SceneConfidence.assess(observations(18), actors(18, 9));
        assertTrue(crowded.eligible()); assertFalse(crowded.highConfidence()); assertEquals(9, crowded.participantCount());
        assertFalse(crowded.reasons().isEmpty());
    }

    @Test void malformedEvidenceDoesNotProduceAConfidenceResult() {
        assertThrows(IllegalArgumentException.class, () -> SceneConfidence.assess(observations(10), actors(9, 2)));
        var invalid = actors(10, 2); invalid.set(0, Set.of("not-an-identity"));
        assertThrows(IllegalArgumentException.class, () -> SceneConfidence.assess(observations(10), invalid));
    }
}
