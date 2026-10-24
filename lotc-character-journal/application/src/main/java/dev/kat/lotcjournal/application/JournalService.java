package dev.kat.lotcjournal.application;

import dev.kat.lotcjournal.domain.BackupService;
import dev.kat.lotcjournal.domain.JournalSettings;
import dev.kat.lotcjournal.domain.SettingsRepository;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Owns one bounded I/O worker. UI reads immutable snapshots without waiting on SQL. */
public final class JournalService implements AutoCloseable {
    private final SettingsRepository settings;
    private final BackupService backups;
    private final ThreadPoolExecutor worker;
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
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized CompletableFuture<Void> start() {
        if (started || closed) return CompletableFuture.failedFuture(
            new IllegalStateException("Journal has already started or closed"));
        started = true;
        return submit(() -> {
            settings.initialize();
            ready(settings.load(), "Journal ready");
            return null;
        });
    }

    public JournalSnapshot snapshot() { return snapshot; }

    public CompletableFuture<Void> toggleCompactLayout() {
        return submit(() -> {
            requireReady();
            JournalSettings next = new JournalSettings(!snapshot.settings().compactLayout());
            settings.save(next);
            ready(next, "Layout saved");
            return null;
        });
    }

    public CompletableFuture<Path> backup(Path destination) {
        return submit(() -> {
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

    private synchronized <T> CompletableFuture<T> submit(Operation<T> operation) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Journal closed"));
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                try { result.complete(operation.run()); }
                catch (Exception error) {
                    synchronized (this) {
                        if (!closed) snapshot = new JournalSnapshot(
                            JournalSnapshot.Status.FAILED, snapshot.settings(), "Storage operation failed");
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
        try {
            if (!worker.awaitTermination(3, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException interrupted) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface private interface Operation<T> { T run() throws Exception; }
}

