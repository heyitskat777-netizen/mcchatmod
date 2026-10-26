package dev.kat.lotcjournal.ingestion;

import dev.kat.lotcjournal.domain.CaptureRepository;
import dev.kat.lotcjournal.domain.Channel;
import dev.kat.lotcjournal.domain.MessageReference;
import dev.kat.lotcjournal.domain.ImportProgress;
import dev.kat.lotcjournal.domain.ImportSource;
import dev.kat.lotcjournal.domain.ImportView;
import dev.kat.lotcjournal.domain.TranscriptLine;
import dev.kat.lotcjournal.domain.TranscriptPage;
import dev.kat.lotcjournal.parsing.LogLineParser;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

/** Bounded historical-file importer. Exact snapshots identify observations; clocks never imply dates/zones. */
public final class LogImportService implements AutoCloseable {
    private final CaptureRepository repository;
    private final int batchLines;
    private final long maxBytes;
    private final int maxLineBytes;
    private final ThreadPoolExecutor worker;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicLong cancellation = new AtomicLong();
    private volatile boolean closed;
    private volatile ImportView view = new ImportView(ImportView.State.IDLE, "Choose a finished .log or .log.gz file", null);

    public LogImportService(CaptureRepository repository) {
        this(repository, 500, 64L * 1024 * 1024, 64 * 1024, 16);
    }

    LogImportService(CaptureRepository repository, int batchLines, long maxBytes, int maxLineBytes, int queueCapacity) {
        if (batchLines < 1 || maxBytes < 1 || maxBytes > Integer.MAX_VALUE - 8 || maxLineBytes < 1 || queueCapacity < 1)
            throw new IllegalArgumentException("Invalid import limits");
        this.repository = Objects.requireNonNull(repository);
        this.batchLines = batchLines;
        this.maxBytes = maxBytes;
        this.maxLineBytes = maxLineBytes;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity), task -> {
            var thread = new Thread(task, "lotc-journal-import");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public ImportView snapshot() { return view; }

    public CompletableFuture<ImportProgress> importFile(Path path, Set<String> reviewedNames) {
        final Set<String> names;
        try {
            Objects.requireNonNull(path);
            names = Set.copyOf(reviewedNames);
            if (names.size() > 200 || names.stream().anyMatch(name -> name.length() > 128))
                throw new IllegalArgumentException("Use at most 200 reviewed names of 128 characters each");
        } catch (Exception error) { return CompletableFuture.failedFuture(error); }
        long generation = cancellation.get();
        return submit(() -> {
            try { return importSnapshot(path, names, generation, null, -1, null); }
            catch (CancellationException cancelled) {
                publish(ImportView.State.CANCELLED, "Import cancelled; retry resumes committed batches", currentProgress());
                throw cancelled;
            } catch (Exception error) {
                // Keep raw source content and exception details out of UI/logs.
                publish(ImportView.State.FAILED, "Import failed; check file format, limits and storage", currentProgress());
                throw error;
            }
        });
    }

    public CompletableFuture<List<ImportSource>> sources() { return submit(repository::sources); }

    public CompletableFuture<TranscriptPage> transcript(String sourceId, String query, int offset, int limit) {
        return submit(() -> repository.transcript(sourceId, query, offset, limit));
    }

    public CompletableFuture<TranscriptPage> transcript(String sourceId, String query, Channel channel, int offset, int limit) {
        return submit(() -> repository.transcript(sourceId, query, channel, offset, limit));
    }

    public CompletableFuture<TranscriptPage> transcript(String sourceId, String query, Channel channel, boolean favoritesOnly, int offset, int limit) {
        return submit(() -> repository.transcript(sourceId, query, channel, favoritesOnly, offset, limit));
    }

    public CompletableFuture<Void> setFavorite(MessageReference message, boolean favorite) {
        return submit(() -> { repository.setFavorite(message, favorite); return null; });
    }

    public CompletableFuture<Long> removedRecords(String sourceId) { return submit(() -> repository.removedRecords(sourceId)); }

    /** Full validation without changing capture or batch tables. */
    public CompletableFuture<FilePreview> previewFile(Path path, Set<String> reviewedNames) {
        final Set<String> names;
        try { names = validatedNames(reviewedNames); Objects.requireNonNull(path); }
        catch (Exception error) { return CompletableFuture.failedFuture(error); }
        long generation = cancellation.get();
        return submit(() -> {
            var snapshot = validateSnapshot(path, names, generation, true);
            ImportProgress known = repository.sources().stream().anyMatch(source -> source.id().equals(snapshot.source().id()))
                ? repository.progress(snapshot.source().id()) : null;
            return new FilePreview(path.toAbsolutePath().normalize(), snapshot.source(), snapshot.encodedBytes(),
                snapshot.bytes().length, known);
        });
    }

    /** An approved preview digest must match before even creating a source checkpoint. */
    public CompletableFuture<ImportProgress> importFileOwned(Path path, Set<String> reviewedNames,
        String batchId, int ordinal, String expectedSourceId) {
        final Set<String> names;
        try {
            names = validatedNames(reviewedNames);
            Objects.requireNonNull(path); Objects.requireNonNull(batchId); Objects.requireNonNull(expectedSourceId);
        } catch (Exception error) { return CompletableFuture.failedFuture(error); }
        long generation = cancellation.get();
        return submit(() -> {
            try { return importSnapshot(path, names, generation, batchId, ordinal, expectedSourceId); }
            catch (CancellationException cancelled) {
                publish(ImportView.State.CANCELLED, "Import cancelled; committed checkpoints retained", currentProgress());
                throw cancelled;
            } catch (Exception failure) {
                publish(ImportView.State.FAILED, "Import failed; inspect the batch report", currentProgress());
                throw failure;
            }
        });
    }

    private static Set<String> validatedNames(Set<String> reviewedNames) {
        var names = Set.copyOf(reviewedNames);
        if (names.size() > 200 || names.stream().anyMatch(name -> name.length() > 128))
            throw new IllegalArgumentException("Use at most 200 reviewed names of 128 characters each");
        return names;
    }

    public static String policyFingerprint(Set<String> reviewedNames) {
        var names = validatedNames(reviewedNames);
        try {
            var policy = MessageDigest.getInstance("SHA-256");
            policy.update("parser-policy-3\n".getBytes(StandardCharsets.UTF_8));
            for (String name : names.stream().sorted().toList()) {
                byte[] value = name.getBytes(StandardCharsets.UTF_8);
                policy.update((value.length + ":").getBytes(StandardCharsets.UTF_8));
                policy.update(value);
            }
            return HexFormat.of().formatHex(policy.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public void cancel() { cancellation.incrementAndGet(); }

    private ValidatedSnapshot validateSnapshot(Path path, Set<String> names, long generation, boolean noLinks) throws Exception {
        checkCancelled(generation);
        String filename = path.getFileName().toString();
        String lower = filename.toLowerCase(Locale.ROOT);
        boolean gzip = lower.endsWith(".log.gz");
        if (!gzip && !lower.endsWith(".log")) throw new IllegalArgumentException("Choose .log or .log.gz");
        if (noLinks && Files.isSymbolicLink(path)) throw new IllegalArgumentException("Folder imports exclude links");
        Path originalPath = path;
        path = path.toRealPath();
        if (noLinks && !path.equals(originalPath.toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Folder imports exclude linked paths");
        if (lower.equals("latest.log") || path.getFileName().toString().equalsIgnoreCase("latest.log"))
            throw new IllegalArgumentException("Use a finished historical log, not active latest.log");
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Source is not a regular file");
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class);
        long encodedLimit = maxBytes + (gzip ? 1024 * 1024 : 0);
        if (before.size() > encodedLimit) throw new IllegalArgumentException("Encoded source exceeds import limit");
        byte[] bytes;
        // Bounded memory avoids persisting even excluded PM/OOC payloads in a temporary snapshot file.
        try (InputStream input = boundedInput(Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS), encodedLimit, generation);
             InputStream decoded = gzip ? new GZIPInputStream(input) : input;
             var snapshot = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = decoded.read(buffer)) != -1) {
                checkCancelled(generation);
                if ((long) snapshot.size() + count > maxBytes) throw new IllegalArgumentException("Import exceeds decompressed limit");
                snapshot.write(buffer, 0, count);
            }
            bytes = snapshot.toByteArray();
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
            || !Objects.equals(before.fileKey(), after.fileKey()))
            throw new IllegalArgumentException("Source changed while being read");
        long totalLines = 0;
        int lineBytes = 0;
        for (int index = 0; index < bytes.length; index++) {
            byte value = bytes[index];
            checkCancelled(generation);
            if (value == '\r' && (index + 1 == bytes.length || bytes[index + 1] != '\n'))
                throw new IllegalArgumentException("Use LF or CRLF line endings, not bare CR");
            if (value == '\n') { totalLines++; lineBytes = 0; }
            else if (++lineBytes > maxLineBytes) throw new IllegalArgumentException("Source line exceeds limit");
        }
        if (bytes.length > 0 && bytes[bytes.length - 1] != '\n')
            throw new IllegalArgumentException("Unfinished final line; retry a completed historical source");
        // Decode the entire snapshot before touching the repository, so a malformed final byte cannot create a partial import.
        try (var reader = reader(bytes)) {
            char[] buffer = new char[8192];
            while (reader.read(buffer) != -1) checkCancelled(generation);
        }
        String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        checkCancelled(generation);
        return new ValidatedSnapshot(new ImportSource(id, filename, gzip ? "gzip" : "plain", totalLines,
            policyFingerprint(names)), bytes, before.size());
    }

    private ImportProgress importSnapshot(Path path, Set<String> names, long generation,
        String batchId, int ordinal, String expectedSourceId) throws Exception {
        publish(ImportView.State.VALIDATING, "Validating complete historical log (64 MiB maximum)", null);
        var snapshot = validateSnapshot(path, names, generation, batchId != null);
        String id = snapshot.source().id();
        if (expectedSourceId != null && !expectedSourceId.equals(id))
            throw new IllegalArgumentException("Source changed since preview; preview again");
        byte[] bytes = snapshot.bytes();
        long totalLines = snapshot.source().totalLines();
        checkCancelled(generation);
        ImportProgress progress = repository.beginSource(snapshot.source());
        if (progress.complete()) {
            publish(ImportView.State.COMPLETE, "Already imported; original classifications retained", progress);
            return progress;
        }
        var parser = new LogLineParser(names);
        long cursor = 0;
        long expected = progress.processedLines();
        long excluded = 0;
        long ignored = 0;
        var entries = new ArrayList<TranscriptLine>();
        publish(ImportView.State.IMPORTING, "Importing validated source", progress);
        try (var reader = reader(bytes)) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                checkCancelled(generation);
                cursor++;
                if (cursor <= expected) continue;
                var parsed = parser.parse(raw, cursor);
                if (parsed.isEmpty()) ignored++;
                else {
                    var observation = parsed.orElseThrow();
                    if (observation.channel() == Channel.PRIVATE_MESSAGE || observation.channel() == Channel.OOC) excluded++;
                    else if (observation.channel() == Channel.SYSTEM) ignored++;
                    else entries.add(new TranscriptLine(id, cursor, observation.clock().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                        observation.text(), observation.channel(), observation.observedSpeaker().orElse(null)));
                }
                if (cursor - expected == batchLines || cursor == totalLines) {
                    checkCancelled(generation);
                    progress = append(batchId, ordinal, id, expected, cursor, List.copyOf(entries), excluded, ignored, cursor == totalLines);
                    expected = progress.processedLines();
                    excluded = ignored = 0;
                    entries.clear();
                    publish(ImportView.State.IMPORTING, "Imported " + progress.importedLines() + " records; excluded "
                        + progress.excludedLines() + "; ignored " + progress.ignoredLines(), progress);
                }
            }
        }
        if (!progress.complete()) {
            checkCancelled(generation);
            progress = append(batchId, ordinal, id, expected, totalLines, List.of(), 0, 0, true);
        }
        publish(ImportView.State.COMPLETE, "Import complete; unknown channels require review", progress);
        return progress;
    }

    private ImportProgress append(String batchId, int ordinal, String sourceId, long expected, long next,
        List<TranscriptLine> entries, long excluded, long ignored, boolean complete) throws Exception {
        return batchId == null ? repository.appendBatch(sourceId, expected, next, entries, excluded, ignored, complete)
            : repository.appendBatchOwned(batchId, ordinal, sourceId, expected, next, entries, excluded, ignored, complete);
    }

    private record ValidatedSnapshot(ImportSource source, byte[] bytes, long encodedBytes) {}

    private static BufferedReader reader(byte[] bytes) {
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)));
    }

    private InputStream boundedInput(InputStream input, long limit, long generation) {
        return new FilterInputStream(input) {
            private long consumed;
            private void account(int count) throws IOException {
                if (count > 0 && (consumed += count) > limit) throw new IOException("Encoded source exceeds limit");
            }
            @Override public int read() throws IOException {
                checkCancelled(generation);
                int value = in.read();
                account(value < 0 ? 0 : 1);
                return value;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                checkCancelled(generation);
                int count = in.read(bytes, offset, length);
                account(count);
                return count;
            }
            @Override public long skip(long amount) throws IOException {
                byte[] discarded = new byte[8192];
                long skipped = 0;
                while (skipped < amount) {
                    int count = read(discarded, 0, (int) Math.min(discarded.length, amount - skipped));
                    if (count < 0) break;
                    skipped += count;
                }
                return skipped;
            }
        };
    }

    private void checkCancelled(long generation) {
        if (closed || Thread.currentThread().isInterrupted() || generation != cancellation.get()) throw new CancellationException("Import cancelled");
    }

    private ImportProgress currentProgress() { return view.progress(); }

    private void publish(ImportView.State state, String message, ImportProgress progress) {
        if (!closed) view = new ImportView(state, message, progress);
    }

    private synchronized <T> CompletableFuture<T> submit(Operation<T> operation) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Importer closed"));
        var result = new CompletableFuture<T>();
        pending.add(result);
        result.whenComplete((value, error) -> pending.remove(result));
        try {
            worker.execute(() -> {
                try { result.complete(operation.run()); }
                catch (Exception error) { result.completeExceptionally(error); }
            });
        } catch (RuntimeException rejected) { result.completeExceptionally(rejected); }
        return result;
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            cancel();
            worker.shutdown();
            view = new ImportView(ImportView.State.CLOSED, "Importer closed", currentProgress());
        }
        try {
            if (!worker.awaitTermination(3, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException interrupted) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            for (var result : pending) result.completeExceptionally(new IllegalStateException("Importer closed"));
        }
    }

    @FunctionalInterface private interface Operation<T> { T run() throws Exception; }
}
