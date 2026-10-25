package dev.kat.lotcjournal.ingestion;

import dev.kat.lotcjournal.domain.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit read-only previews and durable, sequential folder jobs on a bounded worker. */
public final class BatchImportService implements AutoCloseable {
    public static final int MAX_FILES = 10_000;
    private final LogImportService importer;
    private final BatchRepository repository;
    private final ThreadPoolExecutor worker;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicLong cancellation = new AtomicLong();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile BatchView view = new BatchView(State.IDLE, "Preview a finished-log folder before importing", null, null);

    public BatchImportService(LogImportService importer, BatchRepository repository) {
        this(importer, repository, 4);
    }
    BatchImportService(LogImportService importer, BatchRepository repository, int capacity) {
        this.importer = Objects.requireNonNull(importer);
        this.repository = Objects.requireNonNull(repository);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity), task -> {
            var thread = new Thread(task, "lotc-journal-batch"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    public BatchView snapshot() { return view; }
    public boolean busy() { return busy.get(); }
    public CompletableFuture<FolderPreview> previewFolder(Path folder, Set<String> names) {
        Set<String> copied = Set.copyOf(names);
        return exclusive(generation -> preview(folder, null, copied, generation));
    }
    CompletableFuture<FolderPreview> previewPaths(Path folder, List<Path> paths, Set<String> names) {
        List<Path> copied = List.copyOf(paths);
        return exclusive(generation -> preview(folder, copied, names, generation));
    }
    public CompletableFuture<BatchReport> confirm(FolderPreview preview) {
        return exclusive(generation -> {
            Objects.requireNonNull(preview);
            check(generation);
            if (preview.entries().isEmpty()) throw new IllegalArgumentException("No finished logs to import");
            String id = UUID.randomUUID().toString();
            String policy = LogImportService.policyFingerprint(preview.reviewedNames());
            var files = new ArrayList<BatchFile>();
            for (var entry : preview.entries()) {
                if (entry.validated() != null && !entry.validated().source().policyFingerprint().equals(policy))
                    throw new IllegalArgumentException("Preview classification policy changed");
                long before = entry.validated() == null || entry.validated().knownProgress() == null ? 0
                    : entry.validated().knownProgress().processedLines();
                files.add(new BatchFile(id, files.size(), entry.path().toString(), entry.path().getFileName().toString(),
                    entry.validated() == null ? null : entry.validated().source().id(),
                    entry.errorCode() == null ? "PENDING" : "FAILED", entry.errorCode(), before, before, 0, 0, 0));
            }
            check(generation);
            var plan = new BatchPlan(id, preview.folder().toString(), policy, Instant.now().toString(), preview.reviewedNames(), files);
            repository.createBatch(plan);
            return run(plan, false, generation);
        });
    }
    public CompletableFuture<BatchReport> retry(String id) {
        return exclusive(generation -> {
            var summary = repository.batchSummary(id);
            if (summary.status().equals("UNDONE")) throw new IllegalStateException("Undone batches cannot be retried");
            var plan = repository.batchPlan(id);
            if (!LogImportService.policyFingerprint(plan.reviewedNames()).equals(plan.policyFingerprint()))
                throw new IllegalStateException("Original import policy is no longer supported");
            return run(plan, true, generation);
        });
    }
    public CompletableFuture<List<BatchSummary>> batches() { return submit(false, ignored -> repository.batches(200)); }
    public CompletableFuture<BatchReport> report(String id) { return submit(false, ignored -> readReport(id)); }
    public CompletableFuture<Long> unknownRecords(String id) { return submit(false, ignored -> repository.unknownRecords(id)); }
    public CompletableFuture<Long> undo(String id) { return exclusive(ignored -> repository.undoBatch(id)); }
    public void cancel() { cancellation.incrementAndGet(); importer.cancel(); }

    private FolderPreview preview(Path folder, List<Path> selected, Set<String> names, long generation) throws Exception {
        Set<String> reviewed = BatchPlan.checkedNames(names);
        Path root = checkedFolder(folder);
        publish(State.PREVIEWING, "Validating candidates without changing the journal", null, null);
        List<Path> paths = selected == null ? candidates(root) : selected;
        if (paths.size() > MAX_FILES) throw new IllegalArgumentException("Too many folder candidates");
        var entries = new ArrayList<PreviewEntry>();
        for (Path path : paths) {
            check(generation);
            try {
                checkedCandidate(root, path);
                var valid = await(importer.previewFile(path, reviewed));
                check(generation);
                entries.add(new PreviewEntry(path, valid, null));
            } catch (CancellationException cancelled) { throw cancelled; }
            catch (Exception invalid) { entries.add(new PreviewEntry(path, null, "VALIDATION_FAILED")); }
        }
        var result = new FolderPreview(root, reviewed, entries);
        check(generation);
        publish(State.PREVIEW_READY, "Preview ready: " + entries.size() + " candidate files", result, null);
        return result;
    }

    private BatchReport run(BatchPlan plan, boolean retry, long generation) throws Exception {
        try { return runFiles(plan, retry, generation); }
        catch (Exception failure) {
            try {
                for (var file : repository.batchFiles(plan.id())) {
                    if (Set.of("PENDING", "RUNNING").contains(file.status()))
                        repository.updateFile(plan.id(), file.ordinal(), "INTERRUPTED", file.sourceId(), "BATCH_INTERRUPTED");
                }
                repository.finishBatch(plan.id(), "INTERRUPTED");
            } catch (Exception recovery) { failure.addSuppressed(recovery); }
            throw failure;
        }
    }
    private BatchReport runFiles(BatchPlan plan, boolean retry, long generation) throws Exception {
        repository.finishBatch(plan.id(), "RUNNING");
        publish(State.IMPORTING, "Importing confirmed files", null, readReport(plan.id()));
        Set<String> successfulSources = new HashSet<>();
        boolean cancelled = false;
        List<BatchFile> files = repository.batchFiles(plan.id());
        for (var file : files) {
            if (Set.of("COMPLETE", "DUPLICATE", "SKIPPED").contains(file.status())) {
                if (file.sourceId() != null) successfulSources.add(file.sourceId());
                continue;
            }
            if (!retry && file.status().equals("FAILED")) continue;
            if (closed || generation != cancellation.get()) { cancelled = true; break; }
            String sourceId = file.sourceId();
            try {
                Path path = Path.of(file.path());
                checkedCandidate(Path.of(plan.root()), path);
                var currentPreview = await(importer.previewFile(path, plan.reviewedNames()));
                if (sourceId != null && !sourceId.equals(currentPreview.source().id()))
                    throw new IllegalArgumentException("Source changed since the batch was confirmed");
                if (sourceId == null) sourceId = currentPreview.source().id();
                boolean alreadyComplete = currentPreview.knownProgress() != null && currentPreview.knownProgress().complete();
                check(generation);
                repository.updateFile(plan.id(), file.ordinal(), "RUNNING", sourceId, null);
                var progress = await(importer.importFileOwned(path, plan.reviewedNames(), plan.id(), file.ordinal(), sourceId));
                check(generation);
                var current = repository.batchFiles(plan.id()).get(file.ordinal());
                boolean duplicate = alreadyComplete || successfulSources.contains(sourceId)
                    || (current.afterLine() == current.beforeLine() && progress.processedLines() > 0);
                repository.updateFile(plan.id(), file.ordinal(), duplicate ? "DUPLICATE" : "COMPLETE", sourceId, null);
                successfulSources.add(sourceId);
            } catch (CancellationException stopped) {
                repository.updateFile(plan.id(), file.ordinal(), "CANCELLED", sourceId, "CANCELLED");
                cancelled = true; break;
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                repository.updateFile(plan.id(), file.ordinal(), "CANCELLED", sourceId, "CANCELLED");
                cancelled = true; break;
            } catch (Exception failed) {
                // Keep the old source identity/contribution when a partial file has changed.
                repository.updateFile(plan.id(), file.ordinal(), "FAILED", file.sourceId() == null ? sourceId : file.sourceId(), "IMPORT_FAILED");
            }
            publish(State.IMPORTING, "Processed file " + (file.ordinal() + 1) + " of " + files.size(), null, readReport(plan.id()));
        }
        if (cancelled) {
            for (var file : repository.batchFiles(plan.id())) {
                if (Set.of("PENDING", "RUNNING", "INTERRUPTED").contains(file.status()))
                    repository.updateFile(plan.id(), file.ordinal(), "CANCELLED", file.sourceId(), "CANCELLED");
            }
        }
        var results = repository.batchFiles(plan.id());
        long failures = results.stream().filter(file -> file.status().equals("FAILED")).count();
        String status = cancelled ? "CANCELLED" : failures == results.size() ? "FAILED" : failures > 0 ? "PARTIAL" : "COMPLETE";
        repository.finishBatch(plan.id(), status);
        var report = readReport(plan.id());
        publish(cancelled ? State.CANCELLED : State.COMPLETE, "Batch " + status.toLowerCase(Locale.ROOT), null, report);
        return report;
    }

    static Path checkedFolder(Path folder) throws Exception {
        Objects.requireNonNull(folder);
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Choose a real folder");
        return folder.toRealPath();
    }
    static List<Path> candidates(Path root) throws Exception {
        var found = new ArrayList<Path>();
        try (var paths = Files.newDirectoryStream(root)) {
            int inspected = 0;
            for (Path path : paths) {
                if (++inspected > MAX_FILES) throw new IllegalArgumentException("Folder exceeds entry limit");
                if (eligible(path)) found.add(path.toAbsolutePath().normalize());
            }
        }
        found.sort(Comparator.comparing(Path::toString));
        return List.copyOf(found);
    }
    static boolean eligible(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !name.equals("latest.log")
            && (name.endsWith(".log") || name.endsWith(".log.gz"));
    }
    private static void checkedCandidate(Path root, Path path) throws Exception {
        if (!eligible(path) || !path.toRealPath().getParent().equals(root.toRealPath()))
            throw new IllegalArgumentException("Candidate left the selected folder");
    }
    private BatchReport readReport(String id) throws Exception { return new BatchReport(repository.batchSummary(id), repository.batchFiles(id)); }
    private void check(long generation) { if (closed || generation != cancellation.get() || Thread.currentThread().isInterrupted()) throw new CancellationException("Batch cancelled"); }
    private void publish(State state, String message, FolderPreview preview, BatchReport report) {
        if (!closed) view = new BatchView(state, message, preview, report);
    }
    private <T> CompletableFuture<T> exclusive(Operation<T> operation) { return submit(true, operation); }
    private synchronized <T> CompletableFuture<T> submit(boolean exclusive, Operation<T> operation) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Batch service closed"));
        if (exclusive && !busy.compareAndSet(false, true)) return CompletableFuture.failedFuture(new RejectedExecutionException("Another batch operation is active"));
        long generation = cancellation.get();
        var future = new CompletableFuture<T>(); pending.add(future);
        future.whenComplete((value, error) -> pending.remove(future));
        try {
            worker.execute(() -> {
                try { T result = operation.run(generation); if (exclusive) busy.set(false); future.complete(result); }
                catch (CancellationException cancelled) { if (exclusive) busy.set(false); publish(State.CANCELLED, "Batch operation cancelled", view.preview(), view.report()); future.completeExceptionally(cancelled); }
                catch (Exception failure) { if (exclusive) busy.set(false); publish(State.FAILED, "Batch operation failed; inspect the saved report", view.preview(), view.report()); future.completeExceptionally(failure); }
            });
        } catch (RuntimeException rejected) { if (exclusive) busy.set(false); future.completeExceptionally(rejected); }
        return future;
    }
    static <T> T await(CompletableFuture<T> future) throws Exception {
        try { return future.get(); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw new IllegalStateException("Worker failed", failure.getCause());
        }
    }
    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; cancel(); worker.shutdownNow(); view = new BatchView(State.CLOSED, "Batch service closed", null, view.report()); }
        try { worker.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        finally { for (var future : pending) future.completeExceptionally(new IllegalStateException("Batch service closed")); }
    }
    @FunctionalInterface private interface Operation<T> { T run(long generation) throws Exception; }
    public enum State { IDLE, PREVIEWING, PREVIEW_READY, IMPORTING, COMPLETE, CANCELLED, FAILED, CLOSED }
    public record BatchView(State state, String message, FolderPreview preview, BatchReport report) {}
    public record BatchReport(BatchSummary summary, List<BatchFile> files) { public BatchReport { files = List.copyOf(files); } }
    public record FolderPreview(Path folder, Set<String> reviewedNames, List<PreviewEntry> entries) {
        public FolderPreview { folder = folder.toAbsolutePath().normalize(); reviewedNames = BatchPlan.checkedNames(reviewedNames); entries = List.copyOf(entries); if (entries.size() > MAX_FILES) throw new IllegalArgumentException("Too many candidates"); }
        public long decodedBytes() { return entries.stream().filter(entry -> entry.validated() != null).mapToLong(entry -> entry.validated().decodedBytes()).sum(); }
        public long totalLines() { return entries.stream().filter(entry -> entry.validated() != null).mapToLong(entry -> entry.validated().source().totalLines()).sum(); }
    }
    public record PreviewEntry(Path path, FilePreview validated, String errorCode) {
        public PreviewEntry { path = path.toAbsolutePath().normalize(); if ((validated == null) == (errorCode == null)) throw new IllegalArgumentException("Preview must have validation metadata or an error"); }
        public String status() { return validated == null ? "INVALID" : validated.knownProgress() == null ? "NEW" : validated.knownProgress().complete() ? "DUPLICATE" : "RESUMABLE"; }
    }
}
