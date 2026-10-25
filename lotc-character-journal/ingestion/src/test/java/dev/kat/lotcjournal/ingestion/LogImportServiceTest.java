package dev.kat.lotcjournal.ingestion;

import dev.kat.lotcjournal.domain.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LogImportServiceTest {
    @TempDir Path directory;
    private static String chat(String text) {
        return "[23:59:59] [Render thread/INFO]: [System] [CHAT] " + text;
    }
    private Path plain(String name, String text) throws Exception {
        return Files.writeString(directory.resolve(name), text, StandardCharsets.UTF_8);
    }
    private Path gzip(String name, byte[] bytes) throws Exception {
        Path target = directory.resolve(name);
        try (var stream = new GZIPOutputStream(Files.newOutputStream(target))) { stream.write(bytes); }
        return target;
    }
    private Path gzipWithLongFilename(String name, int headerBytes) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var stream = new GZIPOutputStream(output)) {
            stream.write((chat("Mira waves.") + "\n").getBytes(StandardCharsets.UTF_8));
        }
        byte[] ordinary = output.toByteArray();
        ordinary[3] |= 8; // RFC 1952 FNAME: a null-terminated optional filename before compressed bytes.
        var wrapped = new ByteArrayOutputStream();
        wrapped.write(ordinary, 0, 10);
        wrapped.write("x".repeat(headerBytes).getBytes(StandardCharsets.US_ASCII));
        wrapped.write(0);
        wrapped.write(ordinary, 10, ordinary.length - 10);
        return Files.write(directory.resolve(name), wrapped.toByteArray());
    }
    private LogImportService importer(MemoryCapture repository) {
        return new LogImportService(repository, 2, 4096, 1024, 1);
    }
    private static <T> T result(CompletableFuture<T> task) throws Exception { return task.get(5, TimeUnit.SECONDS); }
    private static Throwable failure(CompletableFuture<?> task) {
        try { task.get(5, TimeUnit.SECONDS); fail("Expected asynchronous failure"); return null; }
        catch (ExecutionException error) { return error.getCause(); }
        catch (CancellationException cancelled) { return cancelled; }
        catch (Exception error) { throw new AssertionError("Task must finish rather than hang", error); }
    }

    @Test void importsTraceableObservationsAndExcludesPrivatePayloadsBeforeRepositoryCalls() throws Exception {
        var repository = new MemoryCapture();
        String repeated = chat("§aMira§r waves.");
        String source = "synthetic non-chat diagnostic\n" + repeated + "\n"
            + chat("[PM] Mira: private-sentinel") + "\n" + chat("[OOC]Mira: ooc-sentinel") + "\n"
            + chat("[UnknownChannel] Mira waves.") + "\n" + repeated + "\n";
        try (var service = importer(repository)) {
            var progress = result(service.importFile(plain("finished.log", source), Set.of("Mira")));
            assertAll(() -> assertTrue(progress.complete()), () -> assertEquals(6, progress.processedLines()),
                () -> assertEquals(3, progress.importedLines()), () -> assertEquals(2, progress.excludedLines()),
                () -> assertEquals(1, progress.ignoredLines()));
            assertEquals(ImportView.State.COMPLETE, service.snapshot().state());
            var page = result(service.transcript(progress.sourceId(), "", 0, 200));
            assertEquals(List.of(2L, 5L, 6L), page.entries().stream().map(TranscriptLine::sourceLine).toList());
            assertEquals(repeated, page.entries().getFirst().raw());
            assertEquals("23:59:59", page.entries().getFirst().clock());
            assertEquals("Mira", page.entries().getFirst().observedSpeaker());
            assertEquals(Channel.UNKNOWN, page.entries().get(1).channel());
            assertNull(page.entries().get(1).observedSpeaker());
            assertFalse(page.entries().get(1).channel().eligibleForAnalysis());
            assertEquals(page.entries().getFirst().text(), page.entries().getLast().text());
            assertNotEquals(page.entries().getFirst().sourceLine(), page.entries().getLast().sourceLine());
            assertTrue(repository.received.stream().noneMatch(line -> line.raw().contains("sentinel")),
                "Private/OOC payloads must not reach even a repository batch argument");
            assertTrue(repository.threads.stream().allMatch("lotc-journal-import"::equals));
        }
    }

    @Test void plainGzipAndRenamedFilesShareDecompressedIdentityAndRetainOriginalClassification() throws Exception {
        var repository = new MemoryCapture();
        byte[] bytes = (chat("Mira waves.") + "\n").getBytes(StandardCharsets.UTF_8);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        try (var service = importer(repository)) {
            var first = result(service.importFile(plain("first.log", new String(bytes, StandardCharsets.UTF_8)), Set.of()));
            assertEquals(expected, first.sourceId());
            assertEquals(first, result(service.importFile(gzip("archive.log.gz", bytes), Set.of("Mira"))));
            assertEquals(first, result(service.importFile(plain("renamed.log", new String(bytes, StandardCharsets.UTF_8)), Set.of("Mira"))));
            assertEquals(1, result(service.sources()).size());
            var source = result(service.sources()).getFirst();
            assertEquals("first.log", source.label());
            assertEquals("plain", source.format());
            var observation = result(service.transcript(first.sourceId(), "", 0, 20)).entries().getFirst();
            assertEquals(Channel.UNKNOWN, observation.channel());
            assertNull(observation.observedSpeaker());
            assertEquals(1, repository.received.size());
        }
    }

    @Test void lineEndingBytesArePartOfIdentityWhileCrLfPreservesCoordinates() throws Exception {
        var repository = new MemoryCapture();
        String body = chat("Mira waves.");
        try (var service = importer(repository)) {
            var lf = result(service.importFile(plain("lf.log", body + "\n"), Set.of()));
            var crlf = result(service.importFile(plain("crlf.log", body + "\r\n"), Set.of()));
            assertNotEquals(lf.sourceId(), crlf.sourceId());
            assertEquals(1, crlf.processedLines());
            var line = result(service.transcript(crlf.sourceId(), "", 0, 20)).entries().getFirst();
            assertEquals(body, line.raw());
            assertEquals(1, line.sourceLine());
        }
    }

    @Test void midnightRolloverKeepsOriginalFullClocksWithoutDateOrTimezoneInference() throws Exception {
        var repository = new MemoryCapture();
        String first = chat("Mira waves.");
        String second = first.replace("23:59:59", "00:00:00");
        try (var service = importer(repository)) {
            result(service.importFile(plain("midnight.log", first + "\n" + second + "\n"), Set.of()));
            assertEquals(List.of("23:59:59", "00:00:00"), repository.received.stream().map(TranscriptLine::clock).toList());
            assertEquals(List.of(1L, 2L), repository.received.stream().map(TranscriptLine::sourceLine).toList());
            assertEquals(List.of(first, second), repository.received.stream().map(TranscriptLine::raw).toList());
        }
    }

    @Test void invalidFinalUtf8ByteFailsBeforeBeginningEvenAfterManyValidBatches() throws Exception {
        var repository = new MemoryCapture();
        byte[] valid = (chat("Mira waves.") + "\n").repeat(700).getBytes(StandardCharsets.UTF_8);
        byte[] malformed = Arrays.copyOf(valid, valid.length + 3);
        malformed[valid.length] = (byte) 0xC3;
        malformed[valid.length + 1] = (byte) 0x28;
        malformed[valid.length + 2] = '\n';
        Path file = Files.write(directory.resolve("invalid-utf8.log"), malformed);
        try (var service = new LogImportService(repository, 2, 128 * 1024, 1024, 1)) {
            assertNotNull(failure(service.importFile(file, Set.of())));
            assertEquals(0, repository.beginCalls.get());
            assertTrue(repository.received.isEmpty());
            assertEquals(ImportView.State.FAILED, service.snapshot().state());
        }
    }

    @Test void incompleteFinalLineFailsBeforeAnyCheckpointAndCanBeRetriedWhenFinished() throws Exception {
        var repository = new MemoryCapture();
        Path path = plain("partial.log", (chat("Mira waves.") + "\n").repeat(30) + chat("Mira waits."));
        try (var service = new LogImportService(repository, 2, 8192, 1024, 1)) {
            assertInstanceOf(IllegalArgumentException.class, failure(service.importFile(path, Set.of())));
            assertEquals(0, repository.beginCalls.get());
            Files.writeString(path, Files.readString(path) + "\n");
            assertEquals(31, result(service.importFile(path, Set.of())).importedLines());
        }
    }

    @Test void corruptGzipFooterFailsBeforeAnyCheckpoint() throws Exception {
        var repository = new MemoryCapture();
        Path path = gzip("corrupt.log.gz", (chat("Mira waves.") + "\n").repeat(100).getBytes(StandardCharsets.UTF_8));
        byte[] compressed = Files.readAllBytes(path);
        Files.write(path, Arrays.copyOf(compressed, compressed.length - 5));
        try (var service = new LogImportService(repository, 2, 16384, 1024, 1)) {
            assertNotNull(failure(service.importFile(path, Set.of())));
            assertEquals(0, repository.beginCalls.get());
            assertTrue(repository.received.isEmpty());
        }
    }

    @Test void byteAndUtf8LineLimitsAreEnforcedBeforeSourceCreation() throws Exception {
        var repository = new MemoryCapture();
        try (var service = new LogImportService(repository, 2, 100, 90, 1)) {
            assertInstanceOf(IllegalArgumentException.class, failure(service.importFile(
                plain("big.log", "x\n".repeat(60)), Set.of())));
        }
        try (var service = new LogImportService(repository, 2, 4096, 100, 1)) {
            assertInstanceOf(IllegalArgumentException.class, failure(service.importFile(
                plain("wide.log", "é".repeat(51) + "\n"), Set.of())));
        }
        assertEquals(0, repository.beginCalls.get());
    }

    @Test void refusesActiveLatestWrongExtensionAndBareCarriageReturnBeforeWrites() throws Exception {
        var repository = new MemoryCapture();
        try (var service = importer(repository)) {
            for (String name : List.of("latest.log", "LaTeSt.LOG", "source.zip", "source.txt")) {
                assertInstanceOf(IllegalArgumentException.class,
                    failure(service.importFile(plain(name, chat("Mira waves.") + "\n"), Set.of())));
            }
            assertInstanceOf(IllegalArgumentException.class, failure(service.importFile(
                plain("bare-cr.log", chat("Mira waves.") + "\r" + chat("Mira waits.") + "\n"), Set.of())));
            assertEquals(0, repository.beginCalls.get());
        }
    }

    @Test void failedBatchResumesFromAtomicCheckpointAcrossServiceReconstruction() throws Exception {
        var repository = new MemoryCapture();
        repository.failAtAttempt = 2;
        Path path = plain("resume.log", (chat("Mira waves.") + "\n").repeat(6));
        try (var service = importer(repository)) {
            assertNotNull(failure(service.importFile(path, Set.of("Mira"))));
            assertEquals(2, repository.onlyProgress().processedLines());
            assertFalse(repository.onlyProgress().complete());
        }
        repository.failAtAttempt = -1;
        try (var service = importer(repository)) {
            var resumed = result(service.importFile(path, Set.of("Mira")));
            assertTrue(resumed.complete());
            assertEquals(6, resumed.importedLines());
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), repository.received.stream().map(TranscriptLine::sourceLine).toList());
        }
    }

    @Test void partialRetryWithChangedReviewedNamesCannotMixClassificationPolicies() throws Exception {
        var repository = new MemoryCapture();
        repository.failAtAttempt = 2;
        Path path = plain("policy-resume.log", (chat("Mira waves.") + "\n").repeat(6));
        try (var service = importer(repository)) {
            assertNotNull(failure(service.importFile(path, Set.of("Mira"))));
            assertEquals(2, repository.onlyProgress().processedLines());
            repository.failAtAttempt = -1;
            assertInstanceOf(IllegalArgumentException.class, failure(service.importFile(path, Set.of())));
            assertEquals(2, repository.onlyProgress().processedLines());
            assertEquals(2, repository.received.size());
            assertTrue(result(service.importFile(path, Set.of("Mira"))).complete());
            assertEquals(6, repository.received.size());
            assertTrue(repository.received.stream().allMatch(line -> line.channel() == Channel.ROLEPLAY));
        }
    }

    @Test void rawGzipHeaderLimitAppliesBeforeAnySourceOrObservationIsCreated() throws Exception {
        var repository = new MemoryCapture();
        try (var service = importer(repository)) {
            assertNotNull(failure(service.importFile(gzipWithLongFilename("oversized-header.log.gz", 1_100_000), Set.of())));
            assertEquals(0, repository.beginCalls.get());
            assertTrue(repository.received.isEmpty());
        }
    }

    @Test void cancellationInterruptsOptionalGzipHeaderScanningBeforeWrites() throws Exception {
        var repository = new MemoryCapture();
        Path path = gzipWithLongFilename("header-cancellation.log.gz", 800_000);
        try (var service = importer(repository)) {
            var task = service.importFile(path, Set.of());
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (service.snapshot().state() != ImportView.State.VALIDATING) {
                    assertFalse(task.isDone(), "Header fixture must reach validation before completion");
                    Thread.onSpinWait();
                }
            });
            service.cancel();
            assertInstanceOf(CancellationException.class, failure(task));
            assertEquals(0, repository.beginCalls.get());
        }
    }

    @Test void renamedSymlinkCannotImportAnActiveLatestLog() throws Exception {
        var repository = new MemoryCapture();
        Path active = plain("latest.log", chat("Mira waves.") + "\n");
        Path alias = directory.resolve("finished-looking.log");
        try { Files.createSymbolicLink(alias, active); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links unavailable: " + unavailable.getClass().getSimpleName());
        }
        try (var service = importer(repository)) {
            assertInstanceOf(IllegalArgumentException.class, failure(service.importFile(alias, Set.of())));
            assertEquals(0, repository.beginCalls.get());
        }
    }

    @Test void cancellationAfterCommittedBatchDoesNotCommitTheNextBatchAndRetryPreservesCounts() throws Exception {
        var repository = new MemoryCapture();
        var committed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        repository.afterCommit = progress -> {
            if (progress.processedLines() == 2) {
                committed.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic batch gate timed out");
            }
        };
        Path path = plain("cancel.log", (chat("Mira waves.") + "\n").repeat(6));
        try (var service = importer(repository)) {
            var task = service.importFile(path, Set.of());
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            service.cancel();
            release.countDown();
            assertInstanceOf(CancellationException.class, failure(task));
            assertEquals(ImportView.State.CANCELLED, service.snapshot().state());
            assertEquals(2, repository.onlyProgress().processedLines());
            repository.afterCommit = ignored -> {};
            var resumed = result(service.importFile(path, Set.of()));
            assertTrue(resumed.complete());
            assertEquals(6, resumed.importedLines());
            assertEquals(6, repository.received.size());
        } finally { release.countDown(); }
    }

    @Test void cancellationDuringValidationCreatesNoSourceAndDoesNotRetainPrivatePayload() throws Exception {
        var repository = new MemoryCapture();
        byte[] bytes = ("synthetic ignored line " + "x".repeat(12000) + "\n").repeat(2000).getBytes(StandardCharsets.UTF_8);
        Path path = gzip("large-validation.log.gz", bytes);
        try (var service = new LogImportService(repository, 500, 32L * 1024 * 1024, 16384, 1)) {
            var task = service.importFile(path, Set.of());
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (service.snapshot().state() != ImportView.State.VALIDATING) {
                    assertFalse(task.isDone(), "Fixture must reach cancellable validation before completion");
                    Thread.onSpinWait();
                }
            });
            service.cancel();
            assertInstanceOf(CancellationException.class, failure(task));
            assertEquals(ImportView.State.CANCELLED, service.snapshot().state());
            assertEquals(0, repository.beginCalls.get());
            assertTrue(repository.received.isEmpty());
        }
    }

    @Test void boundedQueriesRejectOverloadAndCloseRejectsNewWork() throws Exception {
        var repository = new MemoryCapture();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        repository.beforeSources = () -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic source gate timed out");
        };
        var service = importer(repository);
        try {
            var active = service.sources();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var queued = service.transcript(null, "", 0, 20);
            assertInstanceOf(RejectedExecutionException.class, failure(service.sources()));
            release.countDown();
            assertEquals(List.of(), result(active));
            assertEquals(0, result(queued).total());
            service.close();
            assertEquals(ImportView.State.CLOSED, service.snapshot().state());
            assertInstanceOf(IllegalStateException.class, failure(service.sources()));
        } finally { release.countDown(); service.close(); }
    }

    @Test void emptySourceCompletesAndInvalidReviewedNamesNeverReachRepository() throws Exception {
        var repository = new MemoryCapture();
        try (var service = importer(repository)) {
            Path path = plain("empty.log", "");
            assertInstanceOf(IllegalArgumentException.class, failure(service.importFile(path, Set.of("x".repeat(129)))));
            assertEquals(0, repository.beginCalls.get());
            var empty = result(service.importFile(path, Set.of()));
            assertTrue(empty.complete());
            assertEquals(0, empty.processedLines());
            assertEquals(0, empty.importedLines());
        }
    }

    @Test void queuedImportCopiesReviewedNamesBeforeCallerCanMutateThem() throws Exception {
        var repository = new MemoryCapture();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        repository.beforeSources = () -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic queue gate timed out");
        };
        try (var service = importer(repository)) {
            var active = service.sources();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var names = new HashSet<>(Set.of("Mira"));
            var queued = service.importFile(plain("reviewed.log", chat("Mira waves.") + "\n"), names);
            names.clear();
            release.countDown();
            result(active);
            result(queued);
            assertEquals(Channel.ROLEPLAY, repository.received.getFirst().channel());
            assertEquals("Mira", repository.received.getFirst().observedSpeaker());
        } finally { release.countDown(); }
    }

    @Test void interruptedCloseResolvesAcceptedQueuedFutures() throws Exception {
        var repository = new MemoryCapture();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        repository.beforeSources = () -> { entered.countDown(); release.await(); };
        var service = importer(repository);
        try {
            var active = service.sources();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var queued = service.transcript(null, "", 0, 20);
            var closing = new Thread(() -> {
                Thread.currentThread().interrupt();
                service.close();
            }, "synthetic-import-close");
            closing.start();
            closing.join(5000);
            assertFalse(closing.isAlive());
            assertNotNull(failure(active));
            assertNotNull(failure(queued));
            assertEquals(ImportView.State.CLOSED, service.snapshot().state());
        } finally { release.countDown(); service.close(); }
    }

    @FunctionalInterface interface Gate<T> { void run(T value) throws Exception; }
    @FunctionalInterface interface Action { void run() throws Exception; }

    /** In-memory port double; failures/latches target public atomic batch boundaries, not importer internals. */
    static class MemoryCapture implements CaptureRepository {
        final Map<String, ImportSource> sources = new LinkedHashMap<>();
        final Map<String, ImportProgress> progress = new LinkedHashMap<>();
        final List<TranscriptLine> received = new ArrayList<>();
        final Queue<String> threads = new ConcurrentLinkedQueue<>();
        final AtomicInteger beginCalls = new AtomicInteger();
        final AtomicInteger appendAttempts = new AtomicInteger();
        volatile int failAtAttempt = -1;
        volatile Gate<ImportProgress> afterCommit = ignored -> {};
        volatile Action beforeSources = () -> {};
        @Override public synchronized ImportProgress beginSource(ImportSource source) {
            threads.add(Thread.currentThread().getName());
            beginCalls.incrementAndGet();
            var existing = sources.get(source.id());
            if (existing != null && !progress.get(source.id()).complete()
                && !existing.policyFingerprint().equals(source.policyFingerprint()))
                throw new IllegalArgumentException("Incomplete source classification policy differs");
            sources.putIfAbsent(source.id(), source);
            return progress.computeIfAbsent(source.id(), id -> new ImportProgress(id, 0, 0, 0, 0, false));
        }
        @Override public synchronized ImportProgress progress(String id) { return progress.get(id); }
        @Override public ImportProgress appendBatch(String id, long expected, long next, List<TranscriptLine> entries,
                                                    long excluded, long ignored, boolean complete) throws Exception {
            threads.add(Thread.currentThread().getName());
            if (appendAttempts.incrementAndGet() == failAtAttempt) throw new IllegalStateException("Synthetic atomic write failure");
            ImportProgress updated;
            synchronized (this) {
                ImportProgress previous = progress.get(id);
                if (previous.processedLines() != expected) throw new IllegalStateException("Checkpoint conflict");
                for (var line : entries) {
                    if (received.stream().anyMatch(old -> old.sourceId().equals(id) && old.sourceLine() == line.sourceLine()))
                        throw new IllegalStateException("Duplicate source coordinate");
                }
                updated = new ImportProgress(id, next, previous.importedLines() + entries.size(),
                    previous.excludedLines() + excluded, previous.ignoredLines() + ignored, complete);
                received.addAll(entries);
                progress.put(id, updated);
            }
            afterCommit.run(updated);
            return updated;
        }
        @Override public List<ImportSource> sources() throws Exception {
            threads.add(Thread.currentThread().getName());
            beforeSources.run();
            synchronized (this) { return List.copyOf(sources.values()); }
        }
        @Override public synchronized TranscriptPage transcript(String id, String query, int offset, int limit) {
            threads.add(Thread.currentThread().getName());
            String needle = query == null ? "" : query;
            var matches = received.stream().filter(line -> id == null || line.sourceId().equals(id))
                .filter(line -> line.text().contains(needle)).toList();
            return new TranscriptPage(matches.stream().skip(offset).limit(limit).toList(), matches.size(), offset, limit);
        }
        synchronized ImportProgress onlyProgress() { return progress.values().iterator().next(); }
    }
}
