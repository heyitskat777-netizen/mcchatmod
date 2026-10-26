package dev.kat.lotcjournal.ingestion;

import dev.kat.lotcjournal.domain.Channel;
import dev.kat.lotcjournal.domain.LiveEvent;
import dev.kat.lotcjournal.domain.LiveSession;
import dev.kat.lotcjournal.domain.TranscriptLine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static dev.kat.lotcjournal.ingestion.LiveTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class LiveCaptureServiceTest {
    private static final Instant TIME = Instant.parse("2026-10-25T12:34:56Z");
    @TempDir Path directory;

    @Test void privacyIsFilteredBeforeAnyPersistenceAndRepeatedReceivedEventsKeepTheirCoordinates() throws Exception {
        var repository = store(directory.resolve("journal.sqlite"));
        var ioThread = new AtomicInteger();
        long caller = Thread.currentThread().threadId();
        var checked = new ForwardingRepository(repository) {
            @Override public void beginLiveSession(LiveSession session) throws Exception {
                assertNotEquals(caller, Thread.currentThread().threadId()); ioThread.incrementAndGet(); super.beginLiveSession(session);
            }
            @Override public boolean appendLiveEvent(LiveSession session, LiveEvent event) throws Exception {
                assertNotEquals(caller, Thread.currentThread().threadId()); ioThread.incrementAndGet(); return super.appendLiveEvent(session, event);
            }
        };
        Path root = directory.resolve("live");
        try (var service = new LiveCaptureService(root, checked, Set.of())) {
            await(service.start());
            for (String excluded : List.of("[PM] SyntheticAccount -> You: LIVE_PRIVATE_SENTINEL",
                    "[Quiet] [OOC] LIVE_OOC_SENTINEL", "You are now Roleplaying as Synthetic Persona")) {
                assertEquals(LiveCaptureService.Status.IGNORED, await(service.receive(excluded, TIME, LiveEvent.Kind.GAME)).status());
            }
            var first = await(service.receive("[Quiet] Synthetic Visitor waves.", TIME, LiveEvent.Kind.CHAT));
            var repeated = await(service.receive("[Quiet] Synthetic Visitor waves.", TIME, LiveEvent.Kind.GAME));
            assertEquals(LiveCaptureService.Status.SAVED, first.status()); assertEquals(LiveCaptureService.Status.SAVED, repeated.status());
            assertEquals(first.sessionId(), repeated.sessionId()); assertEquals(1, first.sequence()); assertEquals(2, repeated.sequence());
            var page = repository.transcript(null, null, 0, 20);
            assertEquals(2, page.total());
            assertEquals(List.of(1L, 2L), page.entries().stream().map(TranscriptLine::sourceLine).toList());
            assertTrue(page.entries().stream().allMatch(line -> line.text().equals("Synthetic Visitor waves.")
                && line.channel() == Channel.ROLEPLAY && line.observedSpeaker() == null));
            assertTrue(ioThread.get() >= 3, "Real repository work must execute on the capture worker");
        }
        assertFalse(contains(directory, "LIVE_PRIVATE_SENTINEL")); assertFalse(contains(directory, "LIVE_OOC_SENTINEL"));
        assertFalse(contains(root, "You are now Roleplaying as"));
        assertFalse(journals(root).isEmpty());
    }

    @Test void unicodeMultilineDialogueSurvivesReplayWithoutInventedExtraEvents() throws Exception {
        Path root = directory.resolve("live"); var repository = store(directory.resolve("journal.sqlite"));
        String dialogue = "Synthetic Visitor: \"snow Ω ☃.\nA second line keeps [CHAT] inside speech.\"";
        try (var service = new LiveCaptureService(root, repository, Set.of())) {
            await(service.start()); await(service.receive("[Quiet] " + dialogue, TIME, LiveEvent.Kind.GAME));
        }
        var before = repository.transcript(null, null, 0, 20);
        assertEquals(1, before.total()); assertEquals(dialogue, before.entries().getFirst().text());
        for (Path file : journals(root)) {
            // Embedded dialogue line breaks belong inside a complete JSON record, never between journal records.
            assertTrue(Files.readString(file, StandardCharsets.UTF_8).lines().allMatch(line -> line.startsWith("{") && line.endsWith("}")));
        }
        try (var restarted = new LiveCaptureService(root, repository, Set.of())) { await(restarted.recover()); }
        assertEquals(before, repository.transcript(null, null, 0, 20));
        assertTrue(contains(root, "snow Ω ☃."));
    }

    @Test void pauseIsPersistentAndReconnectUsesANewNamespaceForIdenticalDialogue() throws Exception {
        Path root = directory.resolve("live"); var repository = store(directory.resolve("journal.sqlite"));
        String firstSession;
        try (var service = new LiveCaptureService(root, repository, Set.of())) {
            await(service.start());
            firstSession = await(service.receive("[Quiet] Synthetic Visitor waves.", TIME, LiveEvent.Kind.CHAT)).sessionId();
            await(service.pause(true));
            assertEquals(LiveCaptureService.Status.PAUSED,
                await(service.receive("[Quiet] Synthetic Visitor: LIVE_PAUSED_SENTINEL", TIME, LiveEvent.Kind.CHAT)).status());
        }
        try (var restarted = new LiveCaptureService(root, repository, Set.of())) {
            await(restarted.start()); assertTrue(restarted.snapshot().paused());
            assertEquals(LiveCaptureService.Status.PAUSED,
                await(restarted.receive("[Quiet] Synthetic Visitor: LIVE_PAUSED_SENTINEL", TIME, LiveEvent.Kind.CHAT)).status());
            await(restarted.pause(false));
            var second = await(restarted.receive("[Quiet] Synthetic Visitor waves.", TIME, LiveEvent.Kind.CHAT));
            assertEquals(LiveCaptureService.Status.SAVED, second.status()); assertNotEquals(firstSession, second.sessionId());
            assertEquals(1, second.sequence());
        }
        assertFalse(contains(directory, "LIVE_PAUSED_SENTINEL"));
        assertEquals(2, repository.transcript(null, null, 0, 20).total()); assertFalse(repository.livePaused());
    }

    @Test void midnightClockReversalAndSizeRotationKeepEveryEventExactlyOnce() throws Exception {
        Path root = directory.resolve("live"); var repository = store(directory.resolve("journal.sqlite"));
        var times = List.of(Instant.parse("2026-10-25T23:59:59Z"), Instant.parse("2026-10-26T00:00:00Z"),
            Instant.parse("2026-10-25T23:00:00Z"));
        try (var service = new LiveCaptureService(root, repository, Set.of(), 8, 512, ZoneId.of("UTC"))) {
            await(service.start());
            for (var time : times) await(service.receive("[Quiet] Synthetic Visitor: " + "synthetic long dialogue ".repeat(35), time, LiveEvent.Kind.CHAT));
        }
        var before = repository.transcript(null, null, 0, 20);
        assertEquals(3, before.total()); assertEquals(List.of(1L, 2L, 3L), before.entries().stream().map(TranscriptLine::sourceLine).toList());
        var journals = journals(root); assertTrue(journals.size() >= 3, "Each oversized event needs an immutable segment");
        assertTrue(journals.stream().anyMatch(path -> path.getParent().getFileName().toString().equals("2026-10-25")));
        assertTrue(journals.stream().anyMatch(path -> path.getParent().getFileName().toString().equals("2026-10-26")));
        try (var restarted = new LiveCaptureService(root, repository, Set.of())) { await(restarted.recover()); }
        assertEquals(before, repository.transcript(null, null, 0, 20));
    }

    @Test void boundedQueueRejectsOverloadWithoutWritingRejectedPayloadOrHanging() throws Exception {
        Path root = directory.resolve("live"); var repository = store(directory.resolve("journal.sqlite"));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var blocking = new ForwardingRepository(repository) {
            @Override public boolean appendLiveEvent(LiveSession session, LiveEvent event) throws Exception {
                entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Synthetic blocked repository timeout");
                return super.appendLiveEvent(session, event);
            }
        };
        try (var service = new LiveCaptureService(root, blocking, Set.of(), 1, 4096, ZoneId.of("UTC"))) {
            await(service.start());
            var first = service.receive("[Quiet] Synthetic Visitor waves.", TIME, LiveEvent.Kind.CHAT);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var queued = service.receive("[Quiet] Synthetic Visitor bows.", TIME, LiveEvent.Kind.CHAT);
            try {
                var failure = assertThrows(ExecutionException.class,
                    () -> await(service.receive("[Quiet] Synthetic Visitor: LIVE_OVERFLOW_SENTINEL", TIME, LiveEvent.Kind.CHAT)));
                assertInstanceOf(RejectedExecutionException.class, failure.getCause());
            } finally { release.countDown(); }
            assertEquals(LiveCaptureService.Status.SAVED, await(first).status());
            assertEquals(LiveCaptureService.Status.SAVED, await(queued).status());
        } finally { release.countDown(); }
        assertFalse(contains(directory, "LIVE_OVERFLOW_SENTINEL"));
        assertEquals(2, repository.transcript(null, null, 0, 20).total());
    }

    @Test void databaseFailureLeavesForcedEventReplayableAndVisibleAsAnError() throws Exception {
        Path root = directory.resolve("live"); var repository = store(directory.resolve("journal.sqlite"));
        var failing = new ForwardingRepository(repository) {
            @Override public boolean appendLiveEvent(LiveSession session, LiveEvent event) throws Exception { throw new IOException("Synthetic commit failure"); }
        };
        try (var service = new LiveCaptureService(root, failing, Set.of())) {
            await(service.start());
            assertThrows(ExecutionException.class,
                () -> await(service.receive("[Quiet] Synthetic Visitor waves.", TIME, LiveEvent.Kind.CHAT)));
            assertEquals(LiveCaptureService.State.ERROR, service.snapshot().state());
            assertNotNull(service.snapshot().errorCode());
            assertEquals(0, repository.transcript(null, null, 0, 20).total());
            assertTrue(contains(root, "Synthetic Visitor waves."));
        }
        try (var restarted = new LiveCaptureService(root, repository, Set.of())) { await(restarted.recover()); }
        assertEquals(1, repository.transcript(null, null, 0, 20).total());
    }

    @Test void unusableJournalPathFailsWithoutReportingAnyMessageSaved() throws Exception {
        var repository = store(directory.resolve("journal.sqlite"));
        Path root = Files.writeString(directory.resolve("not-a-directory"), "synthetic unrelated file");
        try (var service = new LiveCaptureService(root, repository, Set.of())) {
            assertThrows(ExecutionException.class, () -> await(service.start()));
            assertEquals(LiveCaptureService.State.ERROR, service.snapshot().state());
            assertEquals(0, service.snapshot().saved());
            assertEquals(0, repository.transcript(null, null, 0, 20).total());
        }
        assertEquals("synthetic unrelated file", Files.readString(root));
    }

    @Test void perMessageDiskFailureCannotAcknowledgeOrPersistDialogueThroughAFallback() throws Exception {
        Path root = directory.resolve("live"); var repository = store(directory.resolve("journal.sqlite"));
        try (var service = new LiveCaptureService(root, repository, Set.of(), 8, 4096, ZoneId.of("UTC"))) {
            await(service.start());
            Path obstruction = Files.writeString(root.resolve("2026-10-25"), "synthetic unrelated file");
            assertThrows(ExecutionException.class, () -> await(service.receive(
                "[Quiet] Synthetic Visitor: LIVE_DISK_FAILURE_SENTINEL", TIME, LiveEvent.Kind.CHAT)));
            assertEquals(LiveCaptureService.State.ERROR, service.snapshot().state());
            assertEquals(0, service.snapshot().saved());
            assertEquals(0, repository.transcript(null, null, 0, 20).total());
            assertTrue(journals(root).isEmpty());
            assertEquals("synthetic unrelated file", Files.readString(obstruction));
            assertFalse(contains(directory, "LIVE_DISK_FAILURE_SENTINEL"), "Failed authority append cannot trigger a raw fallback log");
        }
    }

    @Test void incompleteOwnedUtf8TailIsRepairedWithoutLosingEarlierForcedEventsOrTouchingUnrelatedFiles() throws Exception {
        Path root = directory.resolve("live"); var repository = store(directory.resolve("journal.sqlite"));
        var failing = new ForwardingRepository(repository) {
            @Override public boolean appendLiveEvent(LiveSession session, LiveEvent event) throws Exception { throw new IOException("Synthetic commit failure"); }
        };
        try (var service = new LiveCaptureService(root, failing, Set.of())) {
            await(service.start());
            assertThrows(ExecutionException.class, () -> await(service.receive("[Quiet] Synthetic Visitor waves.", TIME, LiveEvent.Kind.CHAT)));
        }
        Path journal = journals(root).getFirst(); byte[] completePrefix = Files.readAllBytes(journal);
        Files.write(journal, new byte[] {'{', '"', 't', 'e', 'x', 't', '"', ':', '"', (byte) 0xC3}, StandardOpenOption.APPEND);
        Path unrelated = Files.write(root.resolve("unrelated.txt"), new byte[] { '{', (byte) 0xC3 });
        byte[] unrelatedBytes = Files.readAllBytes(unrelated);
        try (var restarted = new LiveCaptureService(root, repository, Set.of())) {
            await(restarted.recover());
            assertArrayEquals(completePrefix, Files.readAllBytes(journal)); assertArrayEquals(unrelatedBytes, Files.readAllBytes(unrelated));
            assertEquals(1, repository.transcript(null, null, 0, 20).total());
            await(restarted.start()); await(restarted.receive("[Quiet] Synthetic Visitor bows.", TIME, LiveEvent.Kind.GAME));
        }
        assertEquals(2, repository.transcript(null, null, 0, 20).total());
    }

    @Test void aSecondServiceCannotOwnTheSameRootAndTheOwnerCanContinueSaving() throws Exception {
        Path root = directory.resolve("live"); var repository = store(directory.resolve("journal.sqlite"));
        try (var owner = new LiveCaptureService(root, repository, Set.of())) {
            await(owner.start());
            try (var other = new LiveCaptureService(root, repository, Set.of())) {
                assertThrows(ExecutionException.class, () -> await(other.start()));
                assertEquals(LiveCaptureService.State.ERROR, other.snapshot().state());
            }
            assertEquals(LiveCaptureService.Status.SAVED,
                await(owner.receive("[Quiet] Synthetic Visitor waves.", TIME, LiveEvent.Kind.CHAT)).status());
        }
        try (var nextOwner = new LiveCaptureService(root, repository, Set.of())) { await(nextOwner.start()); }
        assertEquals(1, repository.transcript(null, null, 0, 20).total());
    }

    @Test void invalidSelectedJournalDoesNotPoisonAnOtherwiseHealthyCaptureSession() throws Exception {
        var repository=store(directory.resolve("journal.sqlite"));
        Path invalid=Files.writeString(directory.resolve("invalid.lotc-chat.jsonl"),"{}\n");
        try(var service=new LiveCaptureService(directory.resolve("live"),repository,Set.of())) {
            await(service.start());
            assertThrows(ExecutionException.class,()->await(service.importJournal(invalid)));
            assertEquals(LiveCaptureService.State.CAPTURING,service.snapshot().state());
            assertEquals(LiveCaptureService.Status.SAVED,await(service.receive("[Quiet] Synthetic Visitor waves.",TIME,LiveEvent.Kind.CHAT)).status());
            assertEquals(1,repository.transcript(null,null,0,20).total());
        }
    }

    @Test void standaloneJournalImportReusesEventCoordinatesAndRejectsMalformedWholeFilesBeforeWrites() throws Exception {
        Path root = directory.resolve("live"); var original = store(directory.resolve("journal.sqlite"));
        try (var service = new LiveCaptureService(root, original, Set.of())) {
            await(service.start()); await(service.receive("[Quiet] Synthetic Visitor waves.", TIME, LiveEvent.Kind.CHAT));
            await(service.receive("[Quiet] Synthetic Visitor waves.", TIME, LiveEvent.Kind.GAME));
        }
        Path closed = journals(root).getFirst(); var expected = original.transcript(null, null, 0, 20).entries();
        var imported = store(directory.resolve("imported.sqlite"));
        try (var service = new LiveCaptureService(directory.resolve("imported-live"), imported, Set.of())) {
            await(service.importJournal(closed)); await(service.importJournal(closed));
            assertEquals(expected, imported.transcript(null, null, 0, 20).entries());
        }
        Path malformed = directory.resolve("synthetic-corrupt.lotc-chat.jsonl");
        Files.write(malformed, Files.readAllBytes(closed));
        Files.writeString(malformed, "{\"version\":1,\"type\":\"invalid\"}\n", StandardOpenOption.APPEND);
        byte[] originalBytes = Files.readAllBytes(malformed);
        var rejected = store(directory.resolve("rejected.sqlite"));
        try (var service = new LiveCaptureService(directory.resolve("rejected-live"), rejected, Set.of())) {
            assertThrows(ExecutionException.class, () -> await(service.importJournal(malformed)));
            assertEquals(0, rejected.sources().size(), "Full standalone validation must precede any source/receipt writes");
            assertEquals(0, rejected.transcript(null, null, 0, 20).total());
        }
        assertArrayEquals(originalBytes, Files.readAllBytes(malformed), "Read-only import must never repair the chosen input");
    }

    @Test void incompleteOwnedHeadersAreQuarantinedWithoutGuessingASourceOrDiscardingTheirBytes() throws Exception {
        Path root = directory.resolve("live"), day = Files.createDirectories(root.resolve("2026-10-25"));
        Path empty = Files.write(day.resolve("c".repeat(64) + "-00000001.lotc-chat.jsonl"), new byte[0]);
        byte[] incomplete = "{\"version\":1,\"type\":\"session\",\"sessionId\":\"".getBytes(StandardCharsets.UTF_8);
        Path partial = Files.write(day.resolve("d".repeat(64) + "-00000001.lotc-chat.jsonl"), incomplete);
        var repository = store(directory.resolve("journal.sqlite"));
        try (var service = new LiveCaptureService(root, repository, Set.of())) {
            await(service.recover()); assertTrue(repository.sources().isEmpty());
            assertFalse(Files.exists(empty)); assertFalse(Files.exists(partial));
            List<Path> quarantined;
            try (var files = Files.list(day)) { quarantined = files.filter(path -> path.getFileName().toString().contains(".incomplete-")).toList(); }
            assertEquals(2, quarantined.size());
            Path savedEmpty = quarantined.stream().filter(path -> path.getFileName().toString().startsWith(empty.getFileName().toString())).findFirst().orElseThrow();
            Path savedPartial = quarantined.stream().filter(path -> path.getFileName().toString().startsWith(partial.getFileName().toString())).findFirst().orElseThrow();
            assertArrayEquals(new byte[0], Files.readAllBytes(savedEmpty)); assertArrayEquals(incomplete, Files.readAllBytes(savedPartial));
            await(service.start());
            assertEquals(LiveCaptureService.Status.SAVED,
                await(service.receive("[Quiet] Synthetic Visitor waves.", TIME, LiveEvent.Kind.CHAT)).status());
            assertEquals(1, repository.sources().size());
            assertTrue(repository.sources().stream().noneMatch(source -> source.id().equals("c".repeat(64)) || source.id().equals("d".repeat(64))));
        }
        assertEquals(1, repository.transcript(null, null, 0, 20).total());
    }

    @Test void readableViewsRebuildFromAuthorityAfterCorruptionOrDeletionWithoutDuplicatingEvents() throws Exception {
        Path root = directory.resolve("live"); var repository = store(directory.resolve("journal.sqlite"));
        try (var service = new LiveCaptureService(root, repository, Set.of(), 8, 4096, ZoneId.of("UTC"))) {
            await(service.start());
            await(service.receive("[Quiet] Synthetic Visitor waves Ω.", TIME, LiveEvent.Kind.CHAT));
            await(service.receive("[Quiet] Synthetic Visitor waves Ω.", TIME, LiveEvent.Kind.GAME));
        }
        Path journal = journals(root).getFirst(); byte[] authority = Files.readAllBytes(journal);
        Path readable = journal.resolveSibling(journal.getFileName().toString().replace(".lotc-chat.jsonl", ".chat.txt"));
        String expected = "[2026-10-25 12:34:56+00:00] Synthetic Visitor waves Ω.\n".repeat(2);
        assertEquals(expected, Files.readString(readable));
        var first = repository.transcript(null, null, 0, 20).entries().getFirst();
        repository.setFavorite(dev.kat.lotcjournal.domain.MessageReference.of(first), true);
        var before = repository.transcript(null, null, 0, 20);
        Files.writeString(readable, "synthetic corrupt projection\n");
        try (var service = new LiveCaptureService(root, repository, Set.of())) { await(service.recover()); }
        assertEquals(expected, Files.readString(readable)); assertArrayEquals(authority, Files.readAllBytes(journal));
        Files.delete(readable);
        try (var service = new LiveCaptureService(root, repository, Set.of())) { await(service.recover()); }
        assertEquals(expected, Files.readString(readable)); assertArrayEquals(authority, Files.readAllBytes(journal));
        assertEquals(before, repository.transcript(null, null, 0, 20));
    }
}
