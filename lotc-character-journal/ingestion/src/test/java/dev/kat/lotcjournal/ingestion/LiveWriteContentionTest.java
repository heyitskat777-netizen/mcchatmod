package dev.kat.lotcjournal.ingestion;

import dev.kat.lotcjournal.domain.*;
import dev.kat.lotcjournal.storage.SqliteJournalStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static dev.kat.lotcjournal.ingestion.LiveTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class LiveWriteContentionTest {
    private static final Instant TIME = Instant.parse("2026-10-30T12:00:00Z");
    private static final String INPUT = "[Quiet] Synthetic Contended Actor waves.", CLEAN = "Synthetic Contended Actor waves.";
    @TempDir Path directory;

    @Test void realPairedReservationBeyondFiveSecondsKeepsOneForcedEventPendingThenCommitsRepeatedEmotesExactlyOnce() throws Exception {
        Path database = directory.resolve("journal.sqlite"), root = directory.resolve("live"); var repository = store(database);
        var observed = new CopyOnWriteArrayList<LiveEvent>(); var busyAfterFiveSeconds = new CountDownLatch(1); var heldSince = new AtomicLong(); var busyElapsed = new AtomicLong();
        var checked = new ForwardingRepository(repository) {
            @Override public boolean appendLiveEvent(LiveSession session, LiveEvent event) throws Exception {
                observed.add(event);
                try { return super.appendLiveEvent(session, event); }
                catch (StorageBusyException busy) {
                    long elapsed = System.nanoTime() - heldSince.get();
                    if (elapsed >= TimeUnit.SECONDS.toNanos(5)) { busyElapsed.set(elapsed); busyAfterFiveSeconds.countDown(); }
                    throw busy;
                }
            }
        };
        try (var service = new LiveCaptureService(root, checked, Set.of())) {
            await(service.start());
            try (var held = pairedReservation(database)) {
                heldSince.set(System.nanoTime()); var first = service.receive(INPUT, TIME, LiveEvent.Kind.CHAT);
                assertTrue(busyAfterFiveSeconds.await(10, TimeUnit.SECONDS), "A real SQLite BUSY after more than five held seconds must be observed");
                assertTrue(busyElapsed.get() >= TimeUnit.SECONDS.toNanos(5)); assertTrue(observed.size() > 1);
                assertFalse(first.isDone(), "Temporary contention must not fail or acknowledge the forced event");
                assertEquals(1, service.snapshot().accepted()); assertEquals(1, service.snapshot().pending()); assertEquals(0, service.snapshot().saved());
                assertNotEquals(LiveCaptureService.State.ERROR, service.snapshot().state());
                assertEquals(1, events(root).size()); assertEquals(observed.getFirst(), events(root).getFirst());
                assertEquals(0, scalar(database, "SELECT count(*) FROM live_events")); assertEquals(0, repository.transcript(null, null, 0, 20).total());
                var second = service.receive(INPUT, TIME, LiveEvent.Kind.CHAT); assertEquals(2, service.snapshot().pending());
                held.rollback(); var savedFirst = first.get(5, TimeUnit.SECONDS); var savedSecond = second.get(5, TimeUnit.SECONDS);
                assertEquals(LiveCaptureService.Status.SAVED, savedFirst.status()); assertEquals(LiveCaptureService.Status.SAVED, savedSecond.status());
                assertEquals(savedFirst.sessionId(), savedSecond.sessionId()); assertEquals(1, savedFirst.sequence()); assertEquals(2, savedSecond.sequence());
                var committed = events(root); assertEquals(2, committed.size()); assertEquals(List.of(1L, 2L), committed.stream().map(LiveEvent::sequence).toList());
                assertEquals(committed.getFirst().text(), committed.getLast().text()); assertEquals(2, service.snapshot().saved()); assertEquals(0, service.snapshot().pending());
                assertTrue(observed.stream().filter(event -> event.sequence() == 1).allMatch(committed.getFirst()::equals), "Retries must reuse the exact event, not repeat sequence allocation or journal force");
            }
        }
        var before = repository.transcript(null, null, 0, 20); assertEquals(2, before.total());
        for (int restart = 0; restart < 2; restart++) try (var recovered = new LiveCaptureService(root, repository, Set.of())) { await(recovered.recover()); }
        assertEquals(before, repository.transcript(null, null, 0, 20)); assertEquals(2, scalar(database, "SELECT count(*) FROM live_events")); assertEquals(2, events(root).size());
    }

    @Test void exhaustedBusyDeadlineFailsWithoutAcknowledgementAndLeavesExactlyOneRecoverableForcedEvent() throws Exception {
        Path database = directory.resolve("journal.sqlite"), root = directory.resolve("live"); var repository = store(database); var attempts = new AtomicInteger();
        var busy = new ForwardingRepository(repository) {
            @Override public boolean appendLiveEvent(LiveSession session, LiveEvent event) throws Exception { attempts.incrementAndGet(); throw new StorageBusyException(); }
        };
        try (var service = configured(root, busy, 4, Duration.ofMillis(200))) {
            await(service.start()); long start = System.nanoTime();
            var failure = assertThrows(ExecutionException.class, () -> service.receive(INPUT, TIME, LiveEvent.Kind.CHAT).get(2, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause()); assertEquals("CAPTURE_DATABASE_BUSY_TIMEOUT", service.snapshot().errorCode());
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); assertTrue(elapsed >= 150 && elapsed < 2_000);
            assertTrue(attempts.get() > 1); assertEquals(LiveCaptureService.State.ERROR, service.snapshot().state());
            assertEquals(0, service.snapshot().saved()); assertEquals(0, service.snapshot().pending()); assertEquals(1, events(root).size());
            assertEquals(0, repository.transcript(null, null, 0, 20).total());
        }
        recoverOneExactly(root, repository); assertEquals(0, scalar(database, "SELECT closed FROM live_sessions"), "Retry exhaustion cannot invent a clean session end");
    }

    @Test void untypedIoConstraintAndProvenanceFailuresAreNeverRetriedEvenWhenTheirTextSaysLocked() throws Exception {
        var failures = List.of(new IOException("Synthetic database is locked"), new SQLException("Synthetic SQLITE_BUSY words", "HY000", 19), new IllegalArgumentException("Synthetic conflicting provenance"));
        for (int index = 0; index < failures.size(); index++) {
            Path profile = directory.resolve("case-" + index), root = profile.resolve("live"); var repository = store(profile.resolve("journal.sqlite")); var attempts = new AtomicInteger(); var original = failures.get(index);
            var permanent = new ForwardingRepository(repository) {
                @Override public boolean appendLiveEvent(LiveSession session, LiveEvent event) throws Exception { attempts.incrementAndGet(); throw original; }
            };
            try (var service = configured(root, permanent, 4, Duration.ofSeconds(1))) {
                await(service.start()); var rejected = assertThrows(ExecutionException.class, () -> await(service.receive(INPUT, TIME, LiveEvent.Kind.CHAT)));
                assertSame(original, rejected.getCause()); assertEquals(1, attempts.get()); assertEquals("CAPTURE_STORAGE_FAILED", service.snapshot().errorCode());
                assertThrows(ExecutionException.class, () -> await(service.receive("[Quiet] Synthetic New Actor: UNRETRIED_NEW_MESSAGE", TIME, LiveEvent.Kind.CHAT)));
                assertEquals(1, attempts.get()); assertEquals(1, events(root).size()); assertEquals(0, service.snapshot().saved());
            }
            assertFalse(contains(root, "UNRETRIED_NEW_MESSAGE")); recoverOneExactly(root, repository);
        }
    }

    @Test void startPauseSessionCloseAndRecoveryRetryOnlyTheirIdempotentRepositoryOperation() throws Exception {
        Path database = directory.resolve("journal.sqlite"), root = directory.resolve("live"); var repository = store(database); var checked = new RetryOnceRepository(repository);
        try (var service = configured(root, checked, 4, Duration.ofSeconds(2))) {
            await(service.start()); assertEquals(LiveCaptureService.Status.SAVED, await(service.receive(INPUT, TIME, LiveEvent.Kind.CHAT)).status());
            await(service.endSession()); await(service.pause(true)); assertTrue(repository.livePaused()); await(service.pause(false)); assertFalse(repository.livePaused());
            for (String operation : List.of("begin", "append", "close", "read-pause", "write-pause")) assertTrue(checked.attempts.get(operation).get() >= 2, operation);
            checked.assertSameRetriedArguments();
        }
        var authority = records(root); assertEquals(1, events(root).size()); assertEquals(1, authority.stream().filter(record -> record.type().startsWith("end:")).count());
        var replaying = new RetryOnceRepository(repository);
        try (var recovered = configured(root, replaying, 4, Duration.ofSeconds(2))) { await(recovered.recover()); }
        for (String operation : List.of("begin", "append", "close", "read-pause")) assertEquals(2, replaying.attempts.get(operation).get(), operation);
        replaying.assertSameRetriedArguments();
        assertEquals(authority, records(root)); assertEquals(1, repository.transcript(null, null, 0, 20).total()); assertEquals(1, scalar(database, "SELECT count(*) FROM live_events"));
    }

    @Test void closingDuringBusyRetrySettlesQueuedFuturesPromptlyAndReleasesTheProfileForExactRecovery() throws Exception {
        Path database = directory.resolve("journal.sqlite"), root = directory.resolve("live"); var repository = store(database); var entered = new CountDownLatch(1);
        var busy = new ForwardingRepository(repository) {
            @Override public boolean appendLiveEvent(LiveSession session, LiveEvent event) throws Exception { entered.countDown(); throw new StorageBusyException(); }
        };
        var service = configured(root, busy, 1, Duration.ofSeconds(180));
        try {
            await(service.start()); var first = service.receive(INPUT, TIME, LiveEvent.Kind.CHAT); assertTrue(entered.await(2, TimeUnit.SECONDS));
            var queued = service.receive("[Quiet] Synthetic Queued Actor: SHUTDOWN_QUEUED_SENTINEL", TIME, LiveEvent.Kind.CHAT);
            long start = System.nanoTime(); service.close(); assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000, "Shutdown must abort the retry instead of waiting its 180-second budget or ten-second drain timeout");
            assertThrows(ExecutionException.class, () -> first.get(1, TimeUnit.SECONDS)); assertThrows(ExecutionException.class, () -> queued.get(1, TimeUnit.SECONDS));
            assertEquals(0, service.snapshot().pending()); assertEquals(0, service.snapshot().saved());
        } finally { service.close(); }
        assertFalse(contains(root, "SHUTDOWN_QUEUED_SENTINEL")); recoverOneExactly(root, repository);
    }

    @Test void rejectingSessionControlsOnAFullQueueFailsClosedInsteadOfReusingTheOldWorldSession() throws Exception {
        Path database = directory.resolve("journal.sqlite"), root = directory.resolve("live"); var repository = store(database); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var busy = new ForwardingRepository(repository) {
            @Override public boolean appendLiveEvent(LiveSession session, LiveEvent event) throws Exception {
                entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Synthetic control witness timeout"); throw new StorageBusyException();
            }
        };
        try (var service = configured(root, busy, 1, Duration.ofSeconds(180))) {
            await(service.start()); var first = service.receive(INPUT, TIME, LiveEvent.Kind.CHAT); assertTrue(entered.await(2, TimeUnit.SECONDS));
            var queued = service.receive("[Quiet] Synthetic Queued Actor: OLD_WORLD_QUEUED_SENTINEL", TIME, LiveEvent.Kind.CHAT);
            var endFailure = assertThrows(ExecutionException.class, () -> await(service.endSession())); assertInstanceOf(RejectedExecutionException.class, endFailure.getCause());
            var startFailure = assertThrows(ExecutionException.class, () -> await(service.start())); assertInstanceOf(RejectedExecutionException.class, startFailure.getCause());
            assertEquals("CAPTURE_CONTROL_QUEUE_FULL", service.snapshot().errorCode()); release.countDown();
            assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS)); assertThrows(ExecutionException.class, () -> queued.get(2, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> await(service.start()), "A later freed queue cannot silently turn the failed control sequence into a successful reconnect");
            assertThrows(ExecutionException.class, () -> await(service.receive("[Quiet] Synthetic New Actor: NEW_WORLD_SENTINEL", TIME, LiveEvent.Kind.CHAT)));
            assertEquals(0, service.snapshot().pending()); assertEquals(0, service.snapshot().saved()); assertEquals(0, repository.transcript(null, null, 0, 20).total());
        } finally { release.countDown(); }
        assertEquals(1, events(root).size()); assertFalse(contains(root, "OLD_WORLD_QUEUED_SENTINEL")); assertFalse(contains(root, "NEW_WORLD_SENTINEL")); recoverOneExactly(root, repository);
    }

    @Test void rejectedControlDuringAnInflightStartCannotBeOverwrittenByTheLaterSuccessfulDatabaseBegin() throws Exception {
        Path database = directory.resolve("journal.sqlite"), root = directory.resolve("live"); var repository = store(database);
        var beginEntered = new CountDownLatch(1); var releaseBegin = new CountDownLatch(1); var completedBegins = new AtomicInteger();
        var blocked = new ForwardingRepository(repository) {
            @Override public void beginLiveSession(LiveSession session) throws Exception {
                beginEntered.countDown(); if (!releaseBegin.await(5, TimeUnit.SECONDS)) throw new IOException("Synthetic in-flight start witness timeout");
                super.beginLiveSession(session); completedBegins.incrementAndGet();
            }
        };
        try (var service = configured(root, blocked, 1, Duration.ofSeconds(180))) {
            var starting = service.start(); assertTrue(beginEntered.await(2, TimeUnit.SECONDS));
            var queued = service.receive("[Quiet] Synthetic Queued Actor: INFLIGHT_START_QUEUED_SENTINEL", TIME, LiveEvent.Kind.CHAT);
            var rejected = assertThrows(ExecutionException.class, () -> await(service.endSession())); assertInstanceOf(RejectedExecutionException.class, rejected.getCause());
            assertEquals(LiveCaptureService.State.ERROR, service.snapshot().state()); assertEquals("CAPTURE_CONTROL_QUEUE_FULL", service.snapshot().errorCode());
            releaseBegin.countDown(); assertThrows(ExecutionException.class, () -> starting.get(2, TimeUnit.SECONDS));
            assertEquals(1, completedBegins.get(), "The actual database begin must succeed after the terminal decision to exercise the race");
            assertThrows(ExecutionException.class, () -> queued.get(2, TimeUnit.SECONDS));
            assertEquals(LiveCaptureService.State.ERROR, service.snapshot().state()); assertEquals("CAPTURE_CONTROL_QUEUE_FULL", service.snapshot().errorCode());
            assertThrows(ExecutionException.class, () -> await(service.receive("[Quiet] Synthetic New Actor: INFLIGHT_START_NEW_WORLD_SENTINEL", TIME, LiveEvent.Kind.CHAT)));
            assertEquals(0, service.snapshot().pending()); assertEquals(0, service.snapshot().accepted()); assertEquals(0, service.snapshot().saved());
            assertTrue(events(root).isEmpty()); assertEquals(0, repository.transcript(null, null, 0, 20).total()); assertEquals(0, scalar(database, "SELECT count(*) FROM live_events"));
        } finally { releaseBegin.countDown(); }
        assertFalse(contains(root, "INFLIGHT_START_QUEUED_SENTINEL")); assertFalse(contains(root, "INFLIGHT_START_NEW_WORLD_SENTINEL"));
    }

    @Test void forcedExecutorShutdownSettlesDiscardedQueueCountersExactlyOnceAndKeepsOnlyTheForcedEvent() throws Exception {
        Path database = directory.resolve("journal.sqlite"), root = directory.resolve("live"); var repository = store(database);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var ioThread = new AtomicReference<Thread>();
        var blocked = new ForwardingRepository(repository) {
            @Override public boolean appendLiveEvent(LiveSession session, LiveEvent event) throws Exception {
                ioThread.set(Thread.currentThread()); entered.countDown();
                if (!release.await(25, TimeUnit.SECONDS)) throw new IOException("Synthetic forced-close witness timeout");
                return super.appendLiveEvent(session, event);
            }
        };
        var service = configured(root, blocked, 1, Duration.ofSeconds(180));
        try {
            await(service.start()); var first = service.receive(INPUT, TIME, LiveEvent.Kind.CHAT); assertTrue(entered.await(2, TimeUnit.SECONDS));
            var discarded = service.receive("[Quiet] Synthetic Queued Actor: DISCARDED_QUEUE_SENTINEL", TIME, LiveEvent.Kind.CHAT);
            long start = System.nanoTime(); service.close(); long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsed >= 9_000 && elapsed < 13_000, "The latch must exercise the existing ten-second forced executor-shutdown path");
            assertThrows(ExecutionException.class, () -> first.get(1, TimeUnit.SECONDS)); assertThrows(ExecutionException.class, () -> discarded.get(1, TimeUnit.SECONDS));
            ioThread.get().join(2_000); assertFalse(ioThread.get().isAlive(), "The synthetic interruptible repository operation must finish after shutdownNow");
            assertEquals(0, service.snapshot().pending(), "Late worker completion must not decrement a discarded/already settled future twice"); assertEquals(0, service.snapshot().saved());
        } finally { release.countDown(); service.close(); }
        assertFalse(contains(root, "DISCARDED_QUEUE_SENTINEL")); recoverOneExactly(root, repository);
    }

    private static LiveCaptureService configured(Path root, LiveRepository repository, int capacity, Duration budget) {
        return new LiveCaptureService(root, repository, Set.of(), capacity, 4096, ZoneId.of("UTC"), budget);
    }
    private static Connection pairedReservation(Path database) throws Exception {
        var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (var attach = connection.prepareStatement("ATTACH DATABASE ? AS characters")) { attach.setString(1, database.resolveSibling(database.getFileName() + ".characters.sqlite").toString()); attach.execute(); }
        connection.setAutoCommit(false);
        try (var update = connection.createStatement()) {
            update.executeUpdate("UPDATE main.journal_settings SET value=value WHERE key='compact_layout'");
            update.executeUpdate("UPDATE characters.identity_state SET active_persona=active_persona WHERE id=1");
        }
        return connection;
    }
    private static long scalar(Path database, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var query = connection.createStatement(); var rows = query.executeQuery(sql)) { assertTrue(rows.next()); return rows.getLong(1); }
    }
    private static List<LiveJournalCodec.Record> records(Path root) throws Exception {
        var result = new ArrayList<LiveJournalCodec.Record>();
        for (Path journal : journals(root)) for (String line : Files.readAllLines(journal, StandardCharsets.UTF_8)) result.add(LiveJournalCodec.decode(line.getBytes(StandardCharsets.UTF_8)));
        return List.copyOf(result);
    }
    private static List<LiveEvent> events(Path root) throws Exception { return records(root).stream().map(LiveJournalCodec.Record::event).filter(java.util.Objects::nonNull).toList(); }
    private static void recoverOneExactly(Path root, SqliteJournalStore repository) throws Exception {
        var forced = events(root); assertEquals(1, forced.size()); assertEquals(CLEAN, forced.getFirst().text());
        for (int restart = 0; restart < 2; restart++) try (var service = new LiveCaptureService(root, repository, Set.of())) { await(service.recover()); }
        var page = repository.transcript(null, null, 0, 20); assertEquals(1, page.total()); assertEquals(forced.getFirst().sessionId(), page.entries().getFirst().sourceId());
        assertEquals(forced.getFirst().sequence(), page.entries().getFirst().sourceLine()); assertEquals(CLEAN, page.entries().getFirst().text()); assertEquals(forced, events(root));
    }
    private static final class RetryOnceRepository extends ForwardingRepository {
        final ConcurrentHashMap<String,AtomicInteger> attempts = new ConcurrentHashMap<>();
        final List<LiveSession> sessions = new CopyOnWriteArrayList<>(); final List<LiveEvent> events = new CopyOnWriteArrayList<>();
        final List<String> closes = new CopyOnWriteArrayList<>(); final List<Boolean> pauses = new CopyOnWriteArrayList<>();
        RetryOnceRepository(LiveRepository delegate) { super(delegate); }
        private void before(String operation) throws StorageBusyException { if (attempts.computeIfAbsent(operation, ignored -> new AtomicInteger()).incrementAndGet() == 1) throw new StorageBusyException(); }
        void assertSameRetriedArguments() {
            assertEquals(2, sessions.size()); assertEquals(sessions.getFirst(), sessions.getLast());
            assertEquals(2, events.size()); assertEquals(events.getFirst(), events.getLast());
            assertEquals(2, closes.size()); assertEquals(closes.getFirst(), closes.getLast());
            if (!pauses.isEmpty()) assertEquals(pauses.get(0), pauses.get(1));
        }
        @Override public void beginLiveSession(LiveSession session) throws Exception { sessions.add(session); before("begin"); super.beginLiveSession(session); }
        @Override public boolean appendLiveEvent(LiveSession session, LiveEvent event) throws Exception { events.add(event); before("append"); return super.appendLiveEvent(session, event); }
        @Override public void closeLiveSession(String sessionId) throws Exception { closes.add(sessionId); before("close"); super.closeLiveSession(sessionId); }
        @Override public boolean livePaused() throws Exception { before("read-pause"); return super.livePaused(); }
        @Override public void saveLivePaused(boolean paused) throws Exception { pauses.add(paused); before("write-pause"); super.saveLivePaused(paused); }
    }
}
