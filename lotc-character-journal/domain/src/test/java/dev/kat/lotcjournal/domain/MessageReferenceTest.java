package dev.kat.lotcjournal.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageReferenceTest {
    private static final String SOURCE = "a".repeat(64);
    private static TranscriptLine line(long coordinate) {
        return new TranscriptLine(SOURCE, coordinate, null, "Synthetic Visitor nods.", Channel.UNKNOWN, null);
    }

    @Test void referenceUsesOnlyAValidSourceAndPositiveCoordinate() {
        assertEquals(new MessageReference(SOURCE, 1), MessageReference.of(line(1)));
        assertNotEquals(MessageReference.of(line(1)), MessageReference.of(line(2)));
        for (var source : List.of("", "a".repeat(63), "a".repeat(65), "G".repeat(64), "A".repeat(64)))
            assertThrows(IllegalArgumentException.class, () -> new MessageReference(source, 1));
        assertThrows(IllegalArgumentException.class, () -> new MessageReference(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new MessageReference(SOURCE, 0));
        assertThrows(IllegalArgumentException.class, () -> new MessageReference(SOURCE, -1));
    }

    @Test void pageFavoriteStateCannotReferToAnotherPageOrSource() {
        var first = line(1);
        assertEquals(Set.of(), new TranscriptPage(List.of(first), 2, 0, 1).favorites());
        assertThrows(IllegalArgumentException.class, () -> new TranscriptPage(List.of(first), 2, 0, 1,
            Set.of(MessageReference.of(line(2)))));
        assertThrows(IllegalArgumentException.class, () -> new TranscriptPage(List.of(first), 2, 0, 1,
            Set.of(new MessageReference("b".repeat(64), 1))));
        assertThrows(IllegalArgumentException.class, () -> new TranscriptPage(List.of(), 2, 2, 1,
            Set.of(MessageReference.of(first))));
    }

    @Test void asynchronousPageSnapshotsDefensivelyCopyFavoriteStateAndMessages() {
        var first = line(1);
        var entries = new ArrayList<>(List.of(first));
        var favorites = new HashSet<>(Set.of(MessageReference.of(first)));
        var page = new TranscriptPage(entries, 1, 0, 20, favorites);
        entries.clear(); favorites.clear();
        assertEquals(List.of(first), page.entries());
        assertEquals(Set.of(MessageReference.of(first)), page.favorites());
        assertThrows(UnsupportedOperationException.class, () -> page.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> page.favorites().clear());
    }
}
