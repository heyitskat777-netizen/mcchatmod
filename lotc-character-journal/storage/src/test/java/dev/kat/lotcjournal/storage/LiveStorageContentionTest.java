package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import dev.kat.lotcjournal.parsing.DialogueTextNormalizer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class LiveStorageContentionTest {
    private static final String SOURCE = "a".repeat(64), POLICY = "b".repeat(64);
    private static final Instant TIME = Instant.parse("2026-10-30T12:00:00Z");
    private static final LiveSession SESSION = new LiveSession(SOURCE, TIME, "UTC", POLICY);
    @TempDir Path directory;
    private Path archive() { return directory.resolve("archive.sqlite"); }
    private Path characters() { return directory.resolve("characters.sqlite"); }
    private SqliteJournalStore initialized() throws Exception { return PairedDatabaseFixtures.open(archive(), characters()); }
    private static LiveEvent event() { return new LiveEvent(SOURCE, 1, TIME, 0, LiveEvent.Kind.CHAT, "Synthetic Contended Actor waves.", Channel.ROLEPLAY, null); }
    private long count(Path path, String query) throws Exception { return PairedDatabaseFixtures.scalar(path, query); }
    private Connection reservation(Path path, String table, String assignment) throws Exception {
        var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        connection.setAutoCommit(false);
        try (var query = connection.createStatement()) { query.executeUpdate("UPDATE " + table + " SET " + assignment); }
        return connection;
    }
    private static void assertSqliteBusyCause(StorageBusyException failure) {
        var sql = assertInstanceOf(SQLException.class, failure.getCause());
        assertTrue(Set.of(5, 6).contains(sql.getErrorCode() & 0xff), "Only SQLite BUSY/LOCKED may use the transient storage signal");
    }

    @Test void liveOperationsUseShortTypedBusyAttemptsWithoutChangingCheckpointOrPauseState() throws Exception {
        var store = initialized(); store.beginLiveSession(SESSION);
        try (var held = reservation(archive(), "journal_settings", "value=value WHERE key='compact_layout'")) {
            long start = System.nanoTime();
            assertSqliteBusyCause(assertThrows(StorageBusyException.class, () -> store.appendLiveEvent(SESSION, event())));
            assertSqliteBusyCause(assertThrows(StorageBusyException.class, () -> store.beginLiveSession(SESSION)));
            assertSqliteBusyCause(assertThrows(StorageBusyException.class, () -> store.closeLiveSession(SOURCE)));
            assertSqliteBusyCause(assertThrows(StorageBusyException.class, () -> store.saveLivePaused(true)));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 4_000, "Four live attempts must not each inherit the ordinary five-second writer wait");
            assertFalse(store.livePaused()); assertEquals(0, store.progress(SOURCE).processedLines());
            assertEquals(0, count(archive(), "SELECT count(*) FROM live_events")); assertEquals(0, count(archive(), "SELECT count(*) FROM transcript_lines"));
            assertEquals(0, count(archive(), "SELECT closed FROM live_sessions")); held.rollback();
        }
        assertTrue(store.appendLiveEvent(SESSION, event())); assertFalse(store.appendLiveEvent(SESSION, event()));
        assertEquals(1, count(archive(), "SELECT count(*) FROM live_events")); assertEquals(1, store.progress(SOURCE).processedLines());
    }

    @Test void characterMemberContentionRollsBackAlreadyEnteredArchiveAppendBeforeAnExactRetry() throws Exception {
        var normalizer = new DialogueTextNormalizer(); var normalized = new AtomicInteger();
        var store = new SqliteJournalStore(archive(), characters(), (text, channel) -> { normalized.incrementAndGet(); return normalizer.clean(text, channel); });
        store.initialize(); store.beginLiveSession(SESSION); normalized.set(0);
        try (var held = reservation(characters(), "identity_state", "active_persona=active_persona WHERE id=1")) {
            assertSqliteBusyCause(assertThrows(StorageBusyException.class, () -> store.appendLiveEvent(SESSION, event())));
            assertEquals(1, normalized.get(), "The append must reach canonical payload validation past connection setup and archive reservation");
            assertEquals(0, store.progress(SOURCE).processedLines()); assertEquals(0, store.sources().getFirst().totalLines());
            assertEquals(0, count(archive(), "SELECT count(*) FROM transcript_lines")); assertEquals(0, count(archive(), "SELECT count(*) FROM live_events"));
            assertEquals(0, count(characters(), "SELECT count(*) FROM character_identities")); held.rollback();
        }
        assertTrue(store.appendLiveEvent(SESSION, event())); assertFalse(store.appendLiveEvent(SESSION, event()));
        assertEquals(1, store.transcript(SOURCE, "", 0, 20).total()); assertEquals(1, count(characters(), "SELECT count(*) FROM character_identities"));
        assertEquals(1, count(characters(), "SELECT count(*) FROM character_evidence"));
    }

    @Test void provenanceConstraintAndMismatchedPairFailuresNeverBecomeTransientBusySignals() throws Exception {
        var store = initialized(); store.beginLiveSession(SESSION);
        var mismatched = new LiveSession(SOURCE, TIME.plusSeconds(1), "UTC", POLICY);
        assertThrows(SQLException.class, () -> store.appendLiveEvent(mismatched, event()));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + archive()); var query = connection.createStatement()) {
            query.execute("CREATE TRIGGER synthetic_permanent_live_failure BEFORE INSERT ON live_events BEGIN SELECT RAISE(ABORT,'synthetic database is locked text'); END");
        }
        try { assertThrows(SQLException.class, () -> store.appendLiveEvent(SESSION, event())); }
        finally {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + archive()); var query = connection.createStatement()) { query.execute("DROP TRIGGER synthetic_permanent_live_failure"); }
        }
        String generation = PairedDatabaseFixtures.text(characters(), "SELECT generation FROM storage_pair");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + characters()); var update = connection.prepareStatement("UPDATE storage_pair SET generation=?")) {
            update.setString(1, UUID.randomUUID().toString()); update.executeUpdate();
        }
        try { assertThrows(SQLException.class, () -> store.appendLiveEvent(SESSION, event())); }
        finally {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + characters()); var update = connection.prepareStatement("UPDATE storage_pair SET generation=?")) { update.setString(1, generation); update.executeUpdate(); }
        }
        assertEquals(0, store.progress(SOURCE).processedLines()); assertEquals(0, count(archive(), "SELECT count(*) FROM live_events"));
        assertEquals(0, count(archive(), "SELECT count(*) FROM transcript_lines")); assertTrue(store.appendLiveEvent(SESSION, event()));
    }
}
