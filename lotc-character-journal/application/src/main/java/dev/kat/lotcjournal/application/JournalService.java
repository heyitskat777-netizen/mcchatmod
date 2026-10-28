package dev.kat.lotcjournal.application;

import dev.kat.lotcjournal.domain.BackupService;
import dev.kat.lotcjournal.domain.JournalSettings;
import dev.kat.lotcjournal.domain.SettingsRepository;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Owns one bounded I/O worker. UI reads immutable snapshots without waiting on SQL. */
public final class JournalService implements AutoCloseable {
    private final SettingsRepository settings;
    private final BackupService backups;
    private final ThreadPoolExecutor worker;
    private volatile Thread ioThread;
    private final java.util.Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile JournalSnapshot snapshot = new JournalSnapshot(
        JournalSnapshot.Status.STARTING, JournalSettings.defaults(), "Opening journal");
    private boolean started;
    private boolean closed;

    public JournalService(SettingsRepository settings, BackupService backups) {
        this.settings = settings;
        this.backups = backups;
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "lotc-journal-io");
                thread.setDaemon(true);
                ioThread = thread;
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized CompletableFuture<Void> start() {
        if (started || closed) return CompletableFuture.failedFuture(
            new IllegalStateException("Journal has already started or closed"));
        started = true;
        return submit(true, () -> {
            settings.initialize();
            ready(settings.load(), "Journal ready");
            return null;
        });
    }

    public JournalSnapshot snapshot() { return snapshot; }

    public CompletableFuture<Void> toggleCompactLayout() {
        return submit(false, () -> {
            requireReady();
            JournalSettings next = new JournalSettings(!snapshot.settings().compactLayout());
            settings.save(next);
            ready(next, "Layout saved");
            return null;
        });
    }

    public CompletableFuture<Path> backup(Path destination) {
        return submit(false, () -> {
            requireReady();
            Path saved = backups.createBackup(destination);
            ready(snapshot.settings(), "Backup created");
            return saved;
        });
    }

    private void requireReady() {
        if (snapshot.status() != JournalSnapshot.Status.READY)
            throw new IllegalStateException("Journal is not ready");
    }

    private synchronized void ready(JournalSettings value, String message) {
        if (!closed) snapshot = new JournalSnapshot(JournalSnapshot.Status.READY, value, message);
    }

    private synchronized <T> CompletableFuture<T> submit(boolean initialization, Operation<T> operation) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Journal closed"));
        CompletableFuture<T> result = new CompletableFuture<>();
        pending.add(result);
        result.whenComplete((value, error) -> pending.remove(result));
        try {
            worker.execute(() -> {
                try { result.complete(operation.run()); }
                catch (Exception error) {
                    synchronized (this) {
                        if (!closed) snapshot = new JournalSnapshot(
                            initialization ? JournalSnapshot.Status.FAILED : snapshot.status(),
                            snapshot.settings(), "Storage operation failed");
                    }
                    result.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException busy) { result.completeExceptionally(busy); }
        return result;
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            worker.shutdown();
            snapshot = new JournalSnapshot(JournalSnapshot.Status.CLOSED,
                snapshot.settings(), "Journal closed");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        try {
            if (Thread.currentThread() == ioThread) { forceShutdown(); return; }
            if (!worker.awaitTermination(3, TimeUnit.SECONDS)) forceShutdown();
            // Executor termination is signalled inside the worker's final exit path;
            // it can precede actual Thread death. Complete close's lifetime boundary.
            Thread thread = ioThread;
            long remaining = deadline - System.nanoTime();
            if (thread != null && remaining > 0) TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
        } catch (InterruptedException interrupted) {
            forceShutdown();
            Thread.currentThread().interrupt();
        }
    }

    private void forceShutdown() {
        worker.shutdownNow();
        // shutdownNow removes queued Runnables without executing their completion paths.
        // Resolve both removed work and an interrupted operation that cannot finish promptly.
        for (var result : pending)
            result.completeExceptionally(new IllegalStateException("Journal closed"));
    }

    @FunctionalInterface private interface Operation<T> { T run() throws Exception; }
}

