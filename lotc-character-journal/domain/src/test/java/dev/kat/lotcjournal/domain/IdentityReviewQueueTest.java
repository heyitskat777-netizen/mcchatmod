package dev.kat.lotcjournal.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdentityReviewQueueTest {
    private static String id(int number) { return "80000000-0000-0000-0000-" + String.format("%012d", number); }
    private static CharacterIdentity identity(int number, String name, String... aliases) {
        return new CharacterIdentity(id(number), name, "Synthetic note", Set.of(aliases), "Same observed account");
    }
    private static IdentityReviewPair pair(int first, int second) { return new IdentityReviewPair(id(first), id(second)); }
    private static IdentityReviewData data(List<CharacterIdentity> identities) { return new IdentityReviewData(identities, List.of(), List.of()); }

    @Test void exactLiteralNamesAndAliasesUseUnicodeNormalizationWithoutPairingSurnamesAccountsOrSubstrings() {
        var identities = List.of(identity(1, "Synthetic Cafe\u0301"), identity(2, "SYNTHETIC CAFÉ"),
            identity(3, "Synthetic Ash"), identity(4, "Different Ash"), identity(5, "Ann"), identity(6, "Anna"),
            identity(7, "Synthetic Literal", "%_\\'"), identity(8, "Synthetic Other", "%_\\'"));
        var result = IdentityReviewQueue.pending(data(identities), 100);
        assertEquals(Set.of(pair(1, 2), pair(7, 8)), Set.copyOf(result.pairs())); assertFalse(result.hasMore());
        assertEquals("synthetic café", result.matchingNames().get(pair(1, 2))); assertEquals("%_\\'", result.matchingNames().get(pair(7, 8)));
        assertThrows(UnsupportedOperationException.class, () -> result.matchingNames().clear());
        assertEquals(8, identities.size()); assertTrue(identities.stream().allMatch(value -> value.accountObservation().equals("Same observed account")));
    }

    @Test void confirmThenDenyAdvancesToANewRootPairAndEndsWithoutCyclingThroughAcceptedCandidates() {
        var identities = List.of(identity(1, "Synthetic Shared"), identity(2, "Synthetic Shared"), identity(3, "Synthetic Shared"));
        var first = IdentityReviewQueue.pending(data(identities), 1); assertEquals(List.of(pair(1, 2)), first.pairs()); assertTrue(first.hasMore());
        var merge = new IdentityMerge(id(101), id(1), id(2), true);
        var afterConfirm = IdentityReviewQueue.pending(new IdentityReviewData(identities, List.of(merge), List.of()), 1);
        assertEquals(List.of(pair(2, 3)), afterConfirm.pairs()); assertFalse(afterConfirm.hasMore());
        var denial = new IdentitySeparation(id(102), id(2), id(3), true);
        var exhausted = IdentityReviewQueue.pending(new IdentityReviewData(identities, List.of(merge), List.of(denial)), 1);
        assertTrue(exhausted.pairs().isEmpty()); assertFalse(exhausted.hasMore());
        var afterUndoDenial = IdentityReviewQueue.pending(new IdentityReviewData(identities, List.of(merge),
            List.of(new IdentitySeparation(denial.id(), denial.firstIdentity(), denial.secondIdentity(), false))), 1);
        assertEquals(afterConfirm, afterUndoDenial);
    }

    @Test void linkedMembersAndMultipleMatchingAliasesDoNotDuplicatePairsAndDenialsApplyAcrossResolvedGroups() {
        var identities = List.of(identity(1, "Synthetic One", "Shared Alias", "Second Shared Alias"),
            identity(2, "Synthetic Two", "Shared Alias", "Second Shared Alias"), identity(3, "Synthetic Three", "Shared Alias", "Second Shared Alias"));
        var links = List.of(new IdentityMerge(id(101), id(1), id(2), true));
        assertEquals(List.of(pair(2, 3)), IdentityReviewQueue.pending(new IdentityReviewData(identities, links, List.of()), 100).pairs());
        var deniedThroughMember = new IdentitySeparation(id(102), id(1), id(3), true);
        assertTrue(IdentityReviewQueue.pending(new IdentityReviewData(identities, links, List.of(deniedThroughMember)), 100).pairs().isEmpty());
        var unlinked = List.of(new IdentityMerge(id(101), id(1), id(2), false));
        assertEquals(Set.of(pair(1, 2), pair(2, 3)), Set.copyOf(IdentityReviewQueue.pending(new IdentityReviewData(identities, unlinked, List.of(deniedThroughMember)), 100).pairs()));
    }

    @Test void boundedBatchDoesNotFloodUnrelatedPairsAndHasMoreReflectsRemainingUndecidedCandidates() {
        var unrelated = new ArrayList<CharacterIdentity>();
        for (int number = 1; number <= 3000; number++) unrelated.add(identity(number, "Synthetic Unique " + number));
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertEquals(new IdentityReviewBatch(List.of(), false), IdentityReviewQueue.pending(data(unrelated), 100)));
        var colliding = new ArrayList<CharacterIdentity>();
        for (int number = 1; number <= 1000; number++) colliding.add(identity(number, "Synthetic Shared"));
        var firstPage = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> IdentityReviewQueue.pending(data(colliding), 100));
        assertEquals(100, firstPage.pairs().size()); assertEquals(100, Set.copyOf(firstPage.pairs()).size()); assertTrue(firstPage.hasMore());
        assertThrows(UnsupportedOperationException.class, () -> firstPage.pairs().clear());
    }

    @Test void missingConflictingOrCyclicActiveDecisionSnapshotsFailRatherThanClaimingTheQueueIsEmpty() {
        var identities = List.of(identity(1, "Synthetic Shared"), identity(2, "Synthetic Shared"), identity(3, "Synthetic Shared"));
        assertThrows(IllegalArgumentException.class, () -> IdentityReviewQueue.pending(new IdentityReviewData(identities,
            List.of(new IdentityMerge(id(101), id(1), id(99), true)), List.of()), 100));
        assertThrows(IllegalArgumentException.class, () -> IdentityReviewQueue.pending(new IdentityReviewData(identities,
            List.of(new IdentityMerge(id(101), id(1), id(2), true), new IdentityMerge(id(102), id(1), id(3), true)), List.of()), 100));
        assertThrows(IllegalArgumentException.class, () -> IdentityReviewQueue.pending(new IdentityReviewData(identities,
            List.of(new IdentityMerge(id(101), id(1), id(2), true), new IdentityMerge(id(102), id(2), id(1), true)), List.of()), 100));
        assertThrows(IllegalArgumentException.class, () -> IdentityReviewQueue.pending(new IdentityReviewData(identities, List.of(),
            List.of(new IdentitySeparation(id(101), id(1), id(99), true))), 100));
        assertThrows(IllegalArgumentException.class, () -> IdentityReviewQueue.pending(new IdentityReviewData(List.of(identities.getFirst(), identities.getFirst()), List.of(), List.of()), 100));
        assertThrows(IllegalArgumentException.class, () -> IdentityReviewQueue.pending(data(identities), 0));
        assertThrows(IllegalArgumentException.class, () -> IdentityReviewQueue.pending(data(identities), 101));
    }
}
