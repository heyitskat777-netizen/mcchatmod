package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.Channel;
import dev.kat.lotcjournal.domain.ImportSource;
import dev.kat.lotcjournal.domain.LiveEvent;
import dev.kat.lotcjournal.domain.LiveSession;
import dev.kat.lotcjournal.domain.MessageReference;
import dev.kat.lotcjournal.parsing.DialogueTextNormalizer;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LiveRepositoryTest {
    private static final String SOURCE = "c".repeat(64), OTHER = "d".repeat(64), POLICY = "a".repeat(64);
    private static final Instant TIME = Instant.parse("2026-10-25T12:34:56Z");
    private static final LiveSession SESSION = new LiveSession(SOURCE, TIME, "UTC", POLICY);
    @TempDir Path directory;
    private Path path() { return directory.resolve("journal.sqlite"); }
    private SqliteJournalStore store() throws Exception {
        var store = new SqliteJournalStore(path(), new DialogueTextNormalizer()); store.initialize(); return store;
    }
    private static LiveEvent event(long sequence, String text) {
        return new LiveEvent(SOURCE, sequence, TIME, 0, LiveEvent.Kind.GAME, text, Channel.ROLEPLAY, null);
    }
    private long scalar(String query) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path());
             var sql = connection.createStatement(); var row = sql.executeQuery(query)) { return row.getLong(1); }
    }
    private void execute(String command) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path()); var sql = connection.createStatement()) { sql.execute(command); }
    }

    @Test void replayIsIdempotentOnlyForTheSameEntireEventAndNeverDeduplicatesRepeatedDialogue() throws Exception {
        var store = store(); store.beginLiveSession(SESSION); store.beginLiveSession(SESSION);
        var first = event(1, "Synthetic Visitor waves.");
        assertTrue(store.appendLiveEvent(SESSION, first)); assertFalse(store.appendLiveEvent(SESSION, first));
        assertTrue(store.appendLiveEvent(SESSION, event(2, first.text())));
        var page = store.transcript(SOURCE, null, 0, 20);
        assertEquals(2, page.total()); assertEquals(List.of(1L, 2L), page.entries().stream().map(line -> line.sourceLine()).toList());
        assertEquals("12:34:56", page.entries().getFirst().clock());
        for (var conflicting : List.of(event(1, "Synthetic Visitor bows."),
                new LiveEvent(SOURCE, 1, TIME.plusSeconds(1), 0, first.kind(), first.text(), first.channel(), null),
                new LiveEvent(SOURCE, 1, TIME, 0, LiveEvent.Kind.CHAT, first.text(), first.channel(), null),
                new LiveEvent(SOURCE, 1, TIME, 0, first.kind(), first.text(), Channel.UNKNOWN, null),
                new LiveEvent(SOURCE, 1, TIME, 0, first.kind(), first.text(), first.channel(), "Synthetic Visitor")))
            assertThrows(SQLException.class, () -> store.appendLiveEvent(SESSION, conflicting));
        assertEquals(page, store.transcript(SOURCE, null, 0, 20));
        assertEquals(2, store.progress(SOURCE).processedLines()); assertEquals(2, scalar("SELECT count(*) FROM live_events"));
    }

    @Test void receiptFailureRollsBackObservationAndCheckpointTogether() throws Exception {
        var store = store(); store.beginLiveSession(SESSION);
        execute("CREATE TRIGGER synthetic_live_receipt_failure BEFORE INSERT ON live_events BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        try {
            assertThrows(SQLException.class, () -> store.appendLiveEvent(SESSION, event(1, "Synthetic Visitor waves.")));
            assertEquals(0, store.progress(SOURCE).processedLines()); assertEquals(0, store.sources().getFirst().totalLines());
            assertEquals(0, store.transcript(null, null, 0, 20).total()); assertEquals(0, scalar("SELECT count(*) FROM live_events"));
        } finally { execute("DROP TRIGGER synthetic_live_receipt_failure"); }
        assertTrue(store.appendLiveEvent(SESSION, event(1, "Synthetic Visitor waves.")));
        assertEquals(1, store().transcript(null, null, 0, 20).total());
    }

    @Test void gapsWrongSessionPolicyAndOffsetCannotMoveTheLiveCheckpoint() throws Exception {
        var store = store(); store.beginLiveSession(SESSION);
        assertThrows(SQLException.class, () -> store.appendLiveEvent(SESSION, event(2, "Synthetic Visitor waves.")));
        assertThrows(SQLException.class, () -> store.beginLiveSession(new LiveSession(SOURCE, TIME.plusSeconds(1), "UTC", POLICY)));
        assertThrows(SQLException.class, () -> store.beginLiveSession(new LiveSession(SOURCE, TIME, "UTC", "b".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> store.appendLiveEvent(SESSION,
            new LiveEvent(OTHER, 1, TIME, 0, LiveEvent.Kind.CHAT, "Synthetic Visitor waves.", Channel.UNKNOWN, null)));
        assertThrows(IllegalArgumentException.class, () -> store.appendLiveEvent(SESSION,
            new LiveEvent(SOURCE, 1, TIME, 3600, LiveEvent.Kind.CHAT, "Synthetic Visitor waves.", Channel.UNKNOWN, null)));
        assertEquals(0, store.progress(SOURCE).processedLines()); assertEquals(0, scalar("SELECT count(*) FROM live_events"));
        assertTrue(store.appendLiveEvent(SESSION, event(1, "Synthetic Visitor waves.")));
    }

    @Test void closedSessionReplaysExistingReceiptsButCannotAppendOrLoseFavoritesOnRestartAndBackup() throws Exception {
        var store = store(); store.beginLiveSession(SESSION); var first = event(1, "Synthetic Visitor waits.");
        store.appendLiveEvent(SESSION, first); var ref = new MessageReference(SOURCE, 1); store.setFavorite(ref, true);
        store.closeLiveSession(SOURCE); store.closeLiveSession(SOURCE);
        assertFalse(store.appendLiveEvent(SESSION, first));
        assertThrows(SQLException.class, () -> store.appendLiveEvent(SESSION, event(2, first.text())));
        var restarted = store(); assertTrue(restarted.progress(SOURCE).complete());
        assertEquals(Set.of(ref), restarted.transcript(SOURCE, null, 0, 20).favorites());
        assertFalse(restarted.appendLiveEvent(SESSION, first));
        Path backup = restarted.createBackup(directory.resolve("backups/live.sqlite"));
        var restored = new SqliteJournalStore(backup, new DialogueTextNormalizer()); restored.initialize();
        assertEquals(restarted.transcript(null, null, 0, 20), restored.transcript(null, null, 0, 20));
        assertFalse(restored.appendLiveEvent(SESSION, first));
    }

    @Test void historicalAndGrowingLiveSourcesCannotClaimOrMutateEachOthersProvenance() throws Exception {
        var store = store(); store.beginLiveSession(SESSION); store.appendLiveEvent(SESSION, event(1, "Synthetic Visitor waves."));
        assertThrows(SQLException.class, () -> store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 1, POLICY)));
        assertThrows(SQLException.class, () -> store.appendBatch(SOURCE, 1, 2, List.of(), 0, 1, false));
        store.beginSource(new ImportSource(OTHER, "synthetic-historical.log", "plain", 0, POLICY));
        store.appendBatch(OTHER, 0, 0, List.of(), 0, 0, true);
        assertThrows(SQLException.class, () -> store.beginLiveSession(new LiveSession(OTHER, TIME, "UTC", POLICY)));
        assertEquals(1, store.transcript(null, null, 0, 20).total());
        assertEquals(1, scalar("SELECT count(*) FROM live_sessions"));
        assertEquals(1, store.progress(SOURCE).processedLines()); assertEquals(0, store.progress(OTHER).processedLines());
    }

    @Test void persistenceRejectsPrivateNoiseAndUncleanedLivePayloadsBeforeCreatingReceipts() throws Exception {
        var store = store(); store.beginLiveSession(SESSION);
        for (var channel : List.of(Channel.PRIVATE_MESSAGE, Channel.OOC, Channel.SYSTEM))
            assertThrows(IllegalArgumentException.class, () -> new LiveEvent(SOURCE, 1, TIME, 0, LiveEvent.Kind.GAME,
                "LIVE_EXCLUDED_SENTINEL", channel, null));
        for (String payload : List.of("[PM] SyntheticAccount -> You: LIVE_PRIVATE_SENTINEL", "[OOC] LIVE_OOC_SENTINEL",
                "The key did not fit!", "[Quiet] Synthetic Visitor waves."))
            assertThrows(IllegalArgumentException.class, () -> store.appendLiveEvent(SESSION,
                new LiveEvent(SOURCE, 1, TIME, 0, LiveEvent.Kind.GAME, payload, Channel.UNKNOWN, null)));
        assertEquals(0, store.transcript(null, null, 0, 20).total());
        assertEquals(0, store.progress(SOURCE).processedLines()); assertEquals(0, scalar("SELECT count(*) FROM live_events"));
    }

    @Test void startupRejectsReceiptAndActivePayloadThatNoLongerDescribeTheSameEvent() throws Exception {
        var store = store(); store.beginLiveSession(SESSION); store.appendLiveEvent(SESSION, event(1, "Synthetic Visitor waves."));
        execute("UPDATE transcript_lines SET text='Synthetic Visitor bows.' WHERE source_line=1");
        assertThrows(SQLException.class, this::store);
        assertEquals("Synthetic Visitor bows.", store.transcript(null, null, 0, 20).entries().getFirst().text(),
            "Startup validation must reject corruption without silently rewriting evidence");
        execute("UPDATE transcript_lines SET text='Synthetic Visitor waves.' WHERE source_line=1");
        execute("UPDATE live_events SET fingerprint='" + "0".repeat(64) + "' WHERE sequence=1");
        assertThrows(SQLException.class, this::store);
        assertEquals(1, scalar("SELECT count(*) FROM transcript_lines")); assertEquals(1, scalar("SELECT count(*) FROM live_events"));
    }
}
