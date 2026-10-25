package dev.kat.lotcjournal.ingestion;

import dev.kat.lotcjournal.domain.*;
import dev.kat.lotcjournal.storage.SqliteJournalStore;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BatchImportServiceTest {
    @TempDir Path directory;
    static String chat(String body) { return "[23:59:59] [Render thread/INFO]: [CHAT] " + body + "\n"; }
    static <T> T done(CompletableFuture<T> task) throws Exception { return task.get(10, TimeUnit.SECONDS); }
    static Throwable failed(CompletableFuture<?> task) {
        try { task.get(10, TimeUnit.SECONDS); return new AssertionError("Expected failure"); }
        catch (ExecutionException error) { return error.getCause(); }
        catch (CancellationException error) { return error; }
        catch (Exception error) { throw new AssertionError("Operation must finish", error); }
    }
    static void gzip(Path path, String text) throws Exception {
        try (var output = new GZIPOutputStream(Files.newOutputStream(path))) { output.write(text.getBytes(StandardCharsets.UTF_8)); }
    }

    @Test void previewDoesNotWriteAndMixedFolderContinuesPastDuplicateAndCorruptArchive() throws Exception {
        try (var f = new Fixture(directory)) {
            Path folder = Files.createDirectory(directory.resolve("logs"));
            String text = chat("Synthetic Visitor waves.")
                + chat("[PM] BATCH_PRIVATE_SENTINEL") + chat("[OOC]BATCH_OOC_SENTINEL");
            Files.writeString(folder.resolve("a.log"), text);
            gzip(folder.resolve("b.log.gz"), text);
            Files.writeString(folder.resolve("c.log.gz"), "synthetic corrupt gzip");
            Files.writeString(folder.resolve("latest.log"), text);
            Files.writeString(folder.resolve("notes.txt"), text);
            Path nested = Files.createDirectory(folder.resolve("nested"));
            Files.writeString(nested.resolve("not-recursive.log"), text);
            var preview = done(f.service.previewFolder(folder, Set.of()));
            assertEquals(3, preview.entries().size());
            assertEquals(List.of("NEW", "NEW", "INVALID"), preview.entries().stream().map(BatchImportService.PreviewEntry::status).toList());
            assertEquals(List.of(), f.store.sources());
            assertEquals(List.of(), f.store.batches(20));
            assertEquals(ImportView.State.IDLE, f.importer.snapshot().state(), "Read-only preview must not lock the single-file UI");
            var report = done(f.service.confirm(preview));
            assertEquals("PARTIAL", report.summary().status());
            assertEquals(List.of("COMPLETE", "DUPLICATE", "FAILED"), report.files().stream().map(BatchFile::status).toList());
            assertEquals(1, f.store.transcript(null, "", 0, 200).total());
            assertEquals(2, f.store.progress(f.store.sources().getFirst().id()).excludedLines());
            assertTrue(f.store.transcript(null, "", 0, 200).entries().stream()
                .noneMatch(line -> line.text().contains("SENTINEL")),
                "Folder import must exclude PM/OOC before persistent transcript storage");
            assertEquals(1, done(f.service.unknownRecords(report.summary().id())));
            assertEquals("PARTIAL", done(f.service.retry(report.summary().id())).summary().status());
            assertEquals(1, f.store.transcript(null, "", 0, 200).total());
            assertTrue(report.files().stream().filter(file -> file.errorCode() != null)
                .allMatch(file -> file.errorCode().matches("[A-Z][A-Z0-9_]*")));
        }
    }

    @Test void changedDigestAfterPreviewFailsBeforeCreatingSourceOrTranscript() throws Exception {
        try (var f = new Fixture(directory)) {
            Path folder = Files.createDirectory(directory.resolve("logs"));
            Path path = Files.writeString(folder.resolve("changed.log"), chat("Synthetic Visitor waves."));
            var preview = done(f.service.previewFolder(folder, Set.of()));
            String expected = preview.entries().getFirst().validated().source().id();
            Files.writeString(path, chat("Synthetic Visitor leaves."));
            var report = done(f.service.confirm(preview));
            assertEquals("FAILED", report.summary().status());
            assertEquals(expected, report.files().getFirst().sourceId());
            assertEquals(List.of(), f.store.sources());
            assertEquals(0, f.store.transcript(null, "", 0, 200).total());
        }
    }

    @Test void partialFailureRetainsOwnershipAndOriginalPolicyAcrossRetryAndRestart() throws Exception {
        var calls = new AtomicInteger();
        var shouldFail = new AtomicBoolean(true);
        String batch;
        Path folder = Files.createDirectory(directory.resolve("logs"));
        Path path = Files.writeString(folder.resolve("resume.log"), chat("Synthetic Visitor waves.").repeat(6));
        try (var f = new Fixture(directory, (method, args, next) -> {
            if (method.getName().equals("appendBatchOwned") && calls.incrementAndGet() == 2 && shouldFail.get())
                throw new java.sql.SQLException("SYNTHETIC_PRIVATE_ERROR_NOT_FOR_REPORT");
            return next.call();
        }, null)) {
            var report = done(f.service.confirm(done(f.service.previewFolder(folder, Set.of("Synthetic Visitor")))));
            batch = report.summary().id();
            assertEquals("FAILED", report.summary().status());
            assertEquals(2, report.files().getFirst().added());
            assertEquals(2, f.store.transcript(null, "", 0, 200).total());
            assertEquals(Set.of("Synthetic Visitor"), f.store.batchPlan(batch).reviewedNames());
        }
        shouldFail.set(false);
        try (var reopened = new Fixture(directory)) {
            assertEquals(2, reopened.store.transcript(null, "", 0, 200).total(), "Restart alone must not resume any job");
            String originalSource = reopened.store.batchFiles(batch).getFirst().sourceId();
            Files.writeString(path, chat("Synthetic Visitor changed.").repeat(6));
            var rejected = done(reopened.service.retry(batch));
            assertEquals("FAILED", rejected.summary().status());
            assertEquals(originalSource, rejected.files().getFirst().sourceId());
            assertEquals(2, rejected.files().getFirst().added());
            Files.writeString(path, chat("Synthetic Visitor waves.").repeat(6));
            var resumed = done(reopened.service.retry(batch));
            assertEquals("COMPLETE", resumed.summary().status());
            assertEquals(6, resumed.files().getFirst().added());
            assertEquals(6, reopened.store.transcript(null, "", 0, 200).total());
            assertTrue(reopened.store.transcript(null, "", 0, 200).entries().stream()
                .allMatch(line -> line.channel() == Channel.ROLEPLAY));
            assertEquals(6, done(reopened.service.undo(batch)));
            assertEquals(0, done(reopened.service.unknownRecords(batch)));
            assertInstanceOf(IllegalStateException.class, failed(reopened.service.retry(batch)));
            assertEquals(0, reopened.store.transcript(null, "", 0, 200).total());
        }
    }

    @Test void cancellationPersistsPendingFilesAndExplicitRetryFinishesWithoutDuplicates() throws Exception {
        var committed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var gateOnce = new AtomicBoolean(true);
        try (var f = new Fixture(directory, (method, args, next) -> {
            Object value = next.call();
            if (method.getName().equals("appendBatchOwned") && gateOnce.getAndSet(false)) {
                committed.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic batch gate timed out");
            }
            return value;
        }, null)) {
            Path folder = Files.createDirectory(directory.resolve("logs"));
            Files.writeString(folder.resolve("a.log"), chat("Synthetic Visitor waves.").repeat(6));
            Files.writeString(folder.resolve("b.log"), chat("Other Synthetic Visitor waves."));
            var task = f.service.confirm(done(f.service.previewFolder(folder, Set.of())));
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            f.service.cancel(); release.countDown();
            var cancelled = done(task);
            assertEquals("CANCELLED", cancelled.summary().status());
            assertTrue(cancelled.files().stream().allMatch(file -> file.status().equals("CANCELLED")));
            assertEquals(2, f.store.transcript(null, "", 0, 200).total());
            var resumed = done(f.service.retry(cancelled.summary().id()));
            assertEquals("COMPLETE", resumed.summary().status());
            assertEquals(7, f.store.transcript(null, "", 0, 200).total());
        } finally { release.countDown(); }
    }

    @Test void cancelledPreviewHasNoDurableWritesAndCompletionChainsRetainExclusivity() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var gate = new AtomicBoolean(true);
        try (var f = new Fixture(directory, (method, args, next) -> {
            if (method.getName().equals("sources") && gate.getAndSet(false)) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic preview gate timed out");
            }
            return next.call();
        }, null)) {
            Path folder = Files.createDirectory(directory.resolve("logs"));
            Files.writeString(folder.resolve("a.log"), chat("Synthetic Visitor waves."));
            var active = f.service.previewFolder(folder, Set.of());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, failed(f.service.previewFolder(folder, Set.of())));
            f.service.cancel(); release.countDown();
            assertInstanceOf(CancellationException.class, failed(active));
            assertEquals(List.of(), f.store.sources());
            assertEquals(List.of(), f.store.batches(20));
            var chained = f.service.previewFolder(folder, Set.of()).thenCompose(f.service::confirm);
            assertEquals("COMPLETE", done(chained).summary().status());
        } finally { release.countDown(); }
    }

    @Test void emptyCompletedSourceIsReportedAsDuplicateWhileNewEmptySourceCompletes() throws Exception {
        try (var f = new Fixture(directory)) {
            Path folder = Files.createDirectory(directory.resolve("logs"));
            Files.writeString(folder.resolve("empty.log"), "");
            var first = done(f.service.confirm(done(f.service.previewFolder(folder, Set.of()))));
            assertEquals("COMPLETE", first.files().getFirst().status());
            var repeat = done(f.service.confirm(done(f.service.previewFolder(folder, Set.of()))));
            assertEquals("DUPLICATE", repeat.files().getFirst().status());
        }
    }

    @FunctionalInterface interface Hook { Object call(Method method, Object[] args, Callable<Object> next) throws Exception; }
    static final class Fixture implements AutoCloseable {
        final SqliteJournalStore store;
        final BatchRepository repository;
        final LogImportService importer;
        final BatchImportService service;
        Fixture(Path directory) throws Exception { this(directory, null, null); }
        Fixture(Path directory, Hook captureHook, Hook batchHook) throws Exception {
            store = new SqliteJournalStore(directory.resolve("journal.sqlite"), new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()); store.initialize();
            var capture = proxy(CaptureRepository.class, store, captureHook);
            repository = proxy(BatchRepository.class, store, batchHook);
            importer = new LogImportService(capture, 2, 1024 * 1024, 65536, 4);
            service = new BatchImportService(importer, repository);
        }
        @Override public void close() { service.close(); importer.close(); }
        @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type, Object target, Hook hook) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                Callable<Object> invoke = () -> {
                    try { return method.invoke(target, args); }
                    catch (InvocationTargetException error) {
                        if (error.getCause() instanceof Exception exception) throw exception;
                        throw (Error) error.getCause();
                    }
                };
                return hook == null ? invoke.call() : hook.call(method, args, invoke);
            });
        }
    }
}
