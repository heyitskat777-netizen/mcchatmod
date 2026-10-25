package dev.kat.lotcjournal.application;

import dev.kat.lotcjournal.domain.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JournalServiceTest {
    static class MemoryStore implements SettingsRepository, BackupService {
        volatile JournalSettings value = JournalSettings.defaults();
        volatile String thread;
        public void initialize() { thread = Thread.currentThread().getName(); }
        public JournalSettings load() { return value; }
        public void save(JournalSettings value) { this.value = value; }
        public Path createBackup(Path destination) { return destination; }
    }

    @Test void storageRunsOnWorkerAndTogglesAreSerialized() throws Exception {
        var store = new MemoryStore();
        try (var service = new JournalService(store, store)) {
            service.start().get(5, TimeUnit.SECONDS);
            assertEquals("lotc-journal-io", store.thread);
            var first = service.toggleCompactLayout();
            var second = service.toggleCompactLayout();
            CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS);
            assertFalse(service.snapshot().settings().compactLayout());
            assertEquals(JournalSnapshot.Status.READY, service.snapshot().status());
            assertThrows(ExecutionException.class, () -> service.start().get());
        }
    }

    @Test void failedStorageIsVisibleAndDoesNotCrashCaller() throws Exception {
        var store = new MemoryStore() {
            @Override public void initialize() { throw new IllegalStateException("synthetic failure"); }
        };
        try (var service = new JournalService(store, store)) {
            assertThrows(ExecutionException.class, () -> service.start().get(5, TimeUnit.SECONDS));
            assertEquals(JournalSnapshot.Status.FAILED, service.snapshot().status());
        }
    }

    @Test void closingRejectsNewWork() throws Exception {
        var store = new MemoryStore();
        var service = new JournalService(store, store);
        service.start().get(5, TimeUnit.SECONDS);
        service.close();
        service.close();
        assertEquals(JournalSnapshot.Status.CLOSED, service.snapshot().status());
        assertThrows(ExecutionException.class, () -> service.toggleCompactLayout().get());
    }

    @Test void boundedQueueRejectsExcessWithoutRunningOnCaller() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var store = new MemoryStore() {
            @Override public void initialize() {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        };
        try (var service = new JournalService(store, store)) {
            var start = service.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var queued = new java.util.ArrayList<CompletableFuture<Void>>();
            for (int i = 0; i < 32; i++) queued.add(service.toggleCompactLayout());
            var rejected = service.toggleCompactLayout();
            assertTrue(rejected.isCompletedExceptionally());
            assertThrows(ExecutionException.class, rejected::get);
            release.countDown();
            start.get(5, TimeUnit.SECONDS);
            CompletableFuture.allOf(queued.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            assertFalse(service.snapshot().settings().compactLayout());
        } finally { release.countDown(); }
    }

    @Test void failedBackupPreservesReadyStateAndCanBeRetried() throws Exception {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var store = new MemoryStore() {
            @Override public Path createBackup(Path destination) {
                if (attempts.getAndIncrement() == 0)
                    throw new IllegalStateException("synthetic backup failure");
                return destination;
            }
        };
        try (var service = new JournalService(store, store)) {
            service.start().get(5, TimeUnit.SECONDS);
            service.toggleCompactLayout().get(5, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class,
                () -> service.backup(Path.of("synthetic-backup.sqlite")).get(5, TimeUnit.SECONDS));
            assertEquals(JournalSnapshot.Status.READY, service.snapshot().status());
            assertTrue(service.snapshot().settings().compactLayout());
            assertFalse(service.snapshot().message().contains("synthetic backup failure"));
            assertEquals(Path.of("retry.sqlite"),
                service.backup(Path.of("retry.sqlite")).get(5, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
        }
    }

    @Test void failedSaveRetainsLastCommittedSettingsAndAllowsRetry() throws Exception {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var store = new MemoryStore() {
            @Override public void save(JournalSettings value) {
                if (attempts.getAndIncrement() == 0)
                    throw new IllegalStateException("synthetic save failure");
                super.save(value);
            }
        };
        try (var service = new JournalService(store, store)) {
            service.start().get(5, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class,
                () -> service.toggleCompactLayout().get(5, TimeUnit.SECONDS));
            assertEquals(JournalSnapshot.Status.READY, service.snapshot().status());
            assertEquals(JournalSettings.defaults(), service.snapshot().settings());
            assertEquals(JournalSettings.defaults(), store.value);
            service.toggleCompactLayout().get(5, TimeUnit.SECONDS);
            assertTrue(service.snapshot().settings().compactLayout());
            assertEquals(store.value, service.snapshot().settings());
        }
    }

    @Test void forcedShutdownCompletesAcceptedQueuedWorkAndPreservesInterrupt() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interruptedOnClose = new java.util.concurrent.atomic.AtomicBoolean();
        var store = new MemoryStore() {
            @Override public Path createBackup(Path destination) {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("synthetic interrupted backup", interrupted);
                }
                return destination;
            }
        };
        var service = new JournalService(store, store);
        try {
            service.start().get(5, TimeUnit.SECONDS);
            var active = service.backup(Path.of("synthetic-backup.sqlite"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var queued = service.toggleCompactLayout();
            var closing = new Thread(() -> {
                Thread.currentThread().interrupt();
                service.close();
                interruptedOnClose.set(Thread.currentThread().isInterrupted());
            }, "synthetic-interrupted-close");
            closing.start();
            closing.join(5_000);
            assertFalse(closing.isAlive(), "close must return after interrupted termination wait");
            assertTrue(interruptedOnClose.get(), "close must restore the caller's interrupt flag");
            assertTrue(queued.isDone(), "shutdown must resolve every accepted queued future");
            assertTrue(queued.isCompletedExceptionally());
            assertTrue(active.handle((path, error) -> error != null).get(5, TimeUnit.SECONDS));
            assertEquals(JournalSnapshot.Status.CLOSED, service.snapshot().status());
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test void allStorageOperationsStayOffTheCallerThread() throws Exception {
        var threads = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        var store = new MemoryStore() {
            @Override public void initialize() { threads.add(Thread.currentThread().getName()); }
            @Override public JournalSettings load() {
                threads.add(Thread.currentThread().getName());
                return super.load();
            }
            @Override public void save(JournalSettings value) {
                threads.add(Thread.currentThread().getName());
                super.save(value);
            }
            @Override public Path createBackup(Path destination) {
                threads.add(Thread.currentThread().getName());
                return destination;
            }
        };
        try (var service = new JournalService(store, store)) {
            service.start().get(5, TimeUnit.SECONDS);
            service.toggleCompactLayout().get(5, TimeUnit.SECONDS);
            service.backup(Path.of("synthetic-backup.sqlite")).get(5, TimeUnit.SECONDS);
            assertEquals(4, threads.size());
            assertTrue(threads.stream().allMatch("lotc-journal-io"::equals));
        }
    }

    @Test void prematureRequestDoesNotPreventLaterInitialization() throws Exception {
        var store = new MemoryStore();
        try (var service = new JournalService(store, store)) {
            assertThrows(ExecutionException.class,
                () -> service.toggleCompactLayout().get(5, TimeUnit.SECONDS));
            assertEquals(JournalSnapshot.Status.STARTING, service.snapshot().status());
            service.start().get(5, TimeUnit.SECONDS);
            assertEquals(JournalSnapshot.Status.READY, service.snapshot().status());
            assertEquals(JournalSettings.defaults(), service.snapshot().settings());
        }
    }
}

