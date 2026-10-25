package dev.kat.lotcjournal.ingestion;

import dev.kat.lotcjournal.domain.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in finished-archive polling. Two scans must agree; it never tails latest.log or resumes old jobs. */
public final class FolderWatchService implements AutoCloseable {
    private final BatchImportService batches;
    private final BatchRepository repository;
    private final ThreadPoolExecutor worker;
    private final ScheduledExecutorService clock;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final AtomicLong revision = new AtomicLong();
    private final Object dispatch = new Object();
    private final Map<Path, Signature> observed = new HashMap<>();
    private final Map<Path, Signature> attempted = new HashMap<>();
    private volatile boolean enabled;
    private volatile boolean closed;
    private volatile WatchView view = new WatchView(WatchSettings.defaults(), "Folder watching is disabled");

    public FolderWatchService(BatchImportService batches, BatchRepository repository) { this(batches, repository, 30); }
    FolderWatchService(BatchImportService batches, BatchRepository repository, long seconds) {
        if (seconds < 1) throw new IllegalArgumentException("Invalid poll interval");
        this.batches = Objects.requireNonNull(batches); this.repository = Objects.requireNonNull(repository);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4), task -> {
            var thread = new Thread(task, "lotc-journal-watch"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        clock = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "lotc-journal-watch-clock"); thread.setDaemon(true); return thread;
        });
        clock.scheduleWithFixedDelay(() -> { if (enabled && !closed) scanNow(); }, seconds, seconds, TimeUnit.SECONDS);
    }
    public WatchView snapshot() { return view; }
    public CompletableFuture<WatchSettings> load() {
        long version = revision.get();
        return submit(() -> {
            WatchSettings settings = repository.watchSettings();
            synchronized (dispatch) {
                if (version == revision.get()) {
                    if (!settings.equals(view.settings())) { observed.clear(); attempted.clear(); }
                    enabled = settings.enabled(); view = new WatchView(settings, enabled ? "Watching requires two stable scans" : "Folder watching is disabled");
                }
            }
            return settings;
        });
    }
    public CompletableFuture<WatchSettings> configure(Path folder, boolean enable, Set<String> names) {
        Set<String> reviewed = BatchPlan.checkedNames(names);
        long version;
        synchronized (dispatch) { version = revision.incrementAndGet(); enabled = false; }
        return submit(() -> {
            try {
                Path root = folder == null ? null : BatchImportService.checkedFolder(folder);
                var settings = new WatchSettings(root == null ? null : root.toString(), enable, reviewed);
                repository.saveWatchSettings(settings);
                synchronized (dispatch) {
                    if (version == revision.get()) {
                        observed.clear(); attempted.clear(); enabled = enable;
                        view = new WatchView(settings, enable ? "Watching requires two stable scans" : "Folder watching is disabled");
                    }
                }
                return settings;
            } catch (Exception failure) {
                WatchSettings retained = view.settings();
                try { retained = repository.watchSettings(); } catch (Exception unavailable) { failure.addSuppressed(unavailable); }
                synchronized (dispatch) {
                    if (version == revision.get()) { enabled = retained.enabled(); view = new WatchView(retained, "Configuration failed; previous watch settings retained"); }
                }
                throw failure;
            }
        });
    }
    public synchronized CompletableFuture<WatchReport> scanNow() {
        if (!scanning.compareAndSet(false, true)) return CompletableFuture.failedFuture(new RejectedExecutionException("A watch scan is already active"));
        var result = submit(() -> { try { return scan(); } finally { scanning.set(false); } });
        if (result.isCompletedExceptionally()) scanning.set(false);
        return result;
    }
    private WatchReport scan() throws Exception {
        long version = revision.get();
        WatchSettings settings = view.settings();
        if (!enabled || !settings.enabled()) return new WatchReport(0, 0, null, "Folder watching is disabled");
        if (batches.busy()) return new WatchReport(0, 0, null, "Another import operation is active");
        Path root = BatchImportService.checkedFolder(Path.of(settings.folder()));
        List<Path> candidates = BatchImportService.candidates(root);
        var current = new HashMap<Path, Signature>();
        var ready = new ArrayList<Path>();
        for (Path path : candidates) {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            var signature = new Signature(attributes.size(), attributes.lastModifiedTime().toString(), String.valueOf(attributes.fileKey()));
            current.put(path, signature);
            if (signature.equals(observed.get(path)) && !signature.equals(attempted.get(path))
                && !repository.watchAttempted(path.toString(), fingerprint(signature, settings))) ready.add(path);
        }
        observed.clear(); observed.putAll(current);
        attempted.keySet().retainAll(current.keySet());
        if (ready.isEmpty()) return new WatchReport(candidates.size(), 0, null, "Waiting for changed archives to be stable");
        var preview = BatchImportService.await(batches.previewPaths(root, ready, settings.reviewedNames()));
        // Completed duplicates are acknowledged without creating another empty durable job.
        var changed = preview.entries().stream().filter(entry -> !entry.status().equals("DUPLICATE")).toList();
        synchronized (dispatch) {
            if (closed || !enabled || version != revision.get()) return new WatchReport(candidates.size(), ready.size(), null, "Watch configuration changed before dispatch");
        }
        // Save the attempt before dispatch; a crash must not silently restart an interrupted automatic job.
        for (Path path : ready) {
            repository.recordWatchAttempt(path.toString(), fingerprint(current.get(path), settings), "ATTEMPTED");
            attempted.put(path, current.get(path));
        }
        CompletableFuture<BatchImportService.BatchReport> confirmed;
        synchronized (dispatch) {
            if (closed || !enabled || version != revision.get()) return new WatchReport(candidates.size(), ready.size(), null, "Watch configuration changed before dispatch");
            if (changed.isEmpty()) {
                for (Path path : ready) attempted.put(path, current.get(path));
                return new WatchReport(candidates.size(), ready.size(), null, "All stable sources were already imported");
            }
            confirmed = batches.confirm(new BatchImportService.FolderPreview(root, settings.reviewedNames(), changed));
        }
        var report = BatchImportService.await(confirmed);
        String outcome = report.summary().status().equals("CANCELLED") ? "CANCELLED"
            : Set.of("PARTIAL", "FAILED").contains(report.summary().status()) ? "FAILED" : "COMPLETE";
        for (Path path : ready) repository.recordWatchAttempt(path.toString(), fingerprint(current.get(path), settings), outcome);
        if (!closed && version == revision.get()) view = new WatchView(settings, "Last watch batch: " + report.summary().status());
        return new WatchReport(candidates.size(), ready.size(), report.summary().id(), "Finished stable archive batch");
    }
    private synchronized <T> CompletableFuture<T> submit(Operation<T> operation) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Folder watcher closed"));
        var future = new CompletableFuture<T>(); pending.add(future);
        future.whenComplete((value, error) -> pending.remove(future));
        try { worker.execute(() -> {
            try { future.complete(operation.run()); }
            catch (Exception failure) {
                if (!closed && !view.message().startsWith("Configuration failed")) view = new WatchView(view.settings(), "Watch operation failed; check folder access and retry");
                future.completeExceptionally(failure);
            }
        }); }
        catch (RuntimeException rejected) { future.completeExceptionally(rejected); }
        return future;
    }
    @Override public void close() {
        synchronized (dispatch) { if (closed) return; closed = true; enabled = false; revision.incrementAndGet(); }
        clock.shutdownNow(); worker.shutdownNow();
        try { worker.awaitTermination(3, TimeUnit.SECONDS); clock.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        finally { for (var future : pending) future.completeExceptionally(new IllegalStateException("Folder watcher closed")); }
    }
    @FunctionalInterface private interface Operation<T> { T run() throws Exception; }
    private static String fingerprint(Signature signature, WatchSettings settings) throws Exception {
        String metadata = signature.size() + "\n" + signature.modified() + "\n" + signature.fileKey()
            + "\n" + LogImportService.policyFingerprint(settings.reviewedNames());
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(metadata.getBytes(StandardCharsets.UTF_8)));
    }
    private record Signature(long size, String modified, String fileKey) {}
    public record WatchReport(int observed, int stable, String batchId, String message) {}
    public record WatchView(WatchSettings settings, String message) {}
}
