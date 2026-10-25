package dev.kat.lotcjournal.ingestion;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.kat.lotcjournal.ingestion.BatchImportServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class FolderWatchServiceTest {
    @TempDir Path directory;

    @Test void watcherIsOptInRequiresTwoStableScansAndOnlyProcessesChangedArchives() throws Exception {
        try (var f = new Fixture(directory); var watch = new FolderWatchService(f.service, f.repository, 3600)) {
            Path folder = Files.createDirectory(directory.resolve("logs"));
            Path path = Files.writeString(folder.resolve("finished.log"), chat("Synthetic Visitor waves."));
            assertFalse(done(watch.load()).enabled());
            assertNull(done(watch.scanNow()).batchId());
            assertTrue(f.store.batches(20).isEmpty());
            done(watch.configure(folder, true, Set.of()));
            assertNull(done(watch.scanNow()).batchId());
            String first = done(watch.scanNow()).batchId();
            assertNotNull(first);
            assertEquals(1, f.store.transcript(null, "", 0, 200).total());
            assertNull(done(watch.scanNow()).batchId());
            assertEquals(1, f.store.batches(20).size(), "Polling unchanged data must not create empty jobs");
            Files.writeString(path, chat("Synthetic Visitor waves once more."));
            assertNull(done(watch.scanNow()).batchId(), "A changed archive must become stable again");
            assertNotNull(done(watch.scanNow()).batchId());
            assertEquals(2, f.store.transcript(null, "", 0, 200).total());
        }
    }

    @Test void unchangedFailedAttemptIsSuppressedAcrossRestartButChangedFileCanImport() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("logs"));
        Path path = Files.writeString(folder.resolve("bad.log.gz"), "synthetic corrupt archive");
        try (var f = new Fixture(directory); var watch = new FolderWatchService(f.service, f.repository, 3600)) {
            done(watch.configure(folder, true, Set.of()));
            assertNull(done(watch.scanNow()).batchId());
            var attempted = done(watch.scanNow());
            assertNotNull(attempted.batchId());
            assertEquals("FAILED", f.store.batchSummary(attempted.batchId()).status());
            assertNull(done(watch.scanNow()).batchId());
        }
        try (var f = new Fixture(directory); var watch = new FolderWatchService(f.service, f.repository, 3600)) {
            assertTrue(done(watch.load()).enabled());
            assertNull(done(watch.scanNow()).batchId());
            assertNull(done(watch.scanNow()).batchId());
            assertEquals(1, f.store.batches(20).size(), "Restart must not silently retry the unchanged failure");
            gzip(path, chat("Synthetic Visitor repaired the fixture."));
            assertNull(done(watch.scanNow()).batchId());
            assertNotNull(done(watch.scanNow()).batchId());
            assertEquals(1, f.store.transcript(null, "", 0, 200).total());
        }
    }

    @Test void failedConfigurationRetainsBothPersistedIntentAndEffectiveRuntimeWatch() throws Exception {
        var failSave = new AtomicBoolean();
        try (var f = new Fixture(directory, null, (method, args, next) -> {
            if (method.getName().equals("saveWatchSettings") && failSave.get())
                throw new java.sql.SQLException("Synthetic watch setting failure");
            return next.call();
        }); var watch = new FolderWatchService(f.service, f.repository, 3600)) {
            Path original = Files.createDirectory(directory.resolve("original"));
            Path other = Files.createDirectory(directory.resolve("other"));
            var previous = done(watch.configure(original, true, Set.of()));
            failSave.set(true);
            assertInstanceOf(java.sql.SQLException.class, failed(watch.configure(other, true, Set.of())));
            assertEquals(previous, watch.snapshot().settings());
            assertEquals(previous, f.store.watchSettings());
            Files.writeString(original.resolve("later.log"), chat("Synthetic Visitor appears later."));
            assertNull(done(watch.scanNow()).batchId());
            assertNotNull(done(watch.scanNow()).batchId(), "The retained watch must still actually run");
        }
    }

    @Test void latestDirectoriesAndSymlinksAreNeverDispatched() throws Exception {
        try (var f = new Fixture(directory); var watch = new FolderWatchService(f.service, f.repository, 3600)) {
            Path folder = Files.createDirectory(directory.resolve("logs"));
            Files.writeString(folder.resolve("latest.log"), chat("Synthetic Visitor active."));
            Files.writeString(folder.resolve("notes.txt"), chat("Synthetic Visitor unrelated."));
            Path nested = Files.createDirectory(folder.resolve("nested"));
            Files.writeString(nested.resolve("nested.log"), chat("Synthetic Visitor nested."));
            Path outside = Files.writeString(directory.resolve("outside.log"), chat("Synthetic Visitor outside."));
            try { Files.createSymbolicLink(folder.resolve("link.log"), outside); }
            catch (UnsupportedOperationException | FileSystemException unsupported) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links unsupported");
            }
            done(watch.configure(folder, true, Set.of()));
            assertEquals(0, done(watch.scanNow()).observed());
            assertNull(done(watch.scanNow()).batchId());
            assertEquals(List.of(), f.store.sources());
            assertEquals(List.of(), f.store.batches(20));
        }
    }

    @Test void disablingWhilePreviewIsBlockedPreventsLaterDispatch() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var gate = new AtomicBoolean(true);
        try (var f = new Fixture(directory, (method, args, next) -> {
            if (method.getName().equals("sources") && gate.getAndSet(false)) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic watch preview gate timed out");
            }
            return next.call();
        }, null); var watch = new FolderWatchService(f.service, f.repository, 3600)) {
            Path folder = Files.createDirectory(directory.resolve("logs"));
            Files.writeString(folder.resolve("a.log"), chat("Synthetic Visitor waves."));
            done(watch.configure(folder, true, Set.of()));
            done(watch.scanNow());
            var active = watch.scanNow();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var stopped = watch.configure(folder, false, Set.of());
            release.countDown();
            assertNull(done(active).batchId());
            assertFalse(done(stopped).enabled());
            assertTrue(f.store.sources().isEmpty());
            assertTrue(f.store.batches(20).isEmpty());
        } finally { release.countDown(); }
    }
}
