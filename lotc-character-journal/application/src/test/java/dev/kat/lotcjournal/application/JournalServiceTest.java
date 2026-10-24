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
}

