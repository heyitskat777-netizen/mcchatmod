package dev.kat.lotcjournal.ingestion;

import dev.kat.lotcjournal.domain.Channel;
import dev.kat.lotcjournal.domain.DialogueCleanup;
import dev.kat.lotcjournal.domain.LiveEvent;
import dev.kat.lotcjournal.domain.LiveRepository;
import dev.kat.lotcjournal.domain.LiveSession;
import dev.kat.lotcjournal.domain.StorageBusyException;
import dev.kat.lotcjournal.parsing.DialogueTextNormalizer;
import dev.kat.lotcjournal.parsing.LogLineParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Received-only capture. Filtering, journal force, SQL and replay all belong to one bounded worker. */
public final class LiveCaptureService implements AutoCloseable {
    public enum State { IDLE, CAPTURING, PAUSED, ERROR, CLOSED }
    public enum Status { SAVED, IGNORED, PAUSED }
    public record Receipt(Status status, String sessionId, long sequence) {}
    public record Snapshot(State state, long accepted, long pending, long saved, String errorCode, boolean paused) {}
    private static final String SUFFIX = ".lotc-chat.jsonl";
    private static final Pattern FILE_NAME = Pattern.compile("([0-9a-f]{64})-([0-9]{8})\\.lotc-chat\\.jsonl");
    private static final Pattern DAY = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private final Path root;
    private final LiveRepository repository;
    private final LogLineParser parser;
    private final DialogueTextNormalizer normalizer = new DialogueTextNormalizer();
    private final String policy;
    private final long maxSegmentBytes;
    private final ZoneId zone;
    private final ThreadPoolExecutor worker;
    private final long busyRetryNanos;
    private final ConcurrentHashMap<CompletableFuture<?>,Boolean> outstanding = new ConcurrentHashMap<>();
    private final AtomicLong accepted = new AtomicLong(), pending = new AtomicLong(), saved = new AtomicLong();
    private volatile State state = State.IDLE;
    private volatile String errorCode;
    private volatile boolean paused, closing;
    private volatile Thread ioThread;
    private boolean initialized, joined;
    private volatile boolean failed;
    private LiveSession session;
    private long sequence;
    private int segment;
    private LocalDate day;
    private FileChannel output, lockChannel;
    private Path readablePath;
    private FileLock profileLock;

    public LiveCaptureService(Path root, LiveRepository repository, Set<String> reviewedNames) {
        this(root, repository, reviewedNames, 256, 8L * 1024 * 1024, ZoneId.systemDefault());
    }

    LiveCaptureService(Path root, LiveRepository repository, Set<String> reviewedNames, int queueCapacity, long maxSegmentBytes, ZoneId zone) {
        this(root,repository,reviewedNames,queueCapacity,maxSegmentBytes,zone,Duration.ofSeconds(180));
    }

    LiveCaptureService(Path root, LiveRepository repository, Set<String> reviewedNames, int queueCapacity, long maxSegmentBytes, ZoneId zone, Duration busyRetryBudget) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize(); this.repository = Objects.requireNonNull(repository);
        var names = Set.copyOf(reviewedNames);
        if (names.size() > 200 || names.stream().anyMatch(name -> name.length() > 128) || queueCapacity < 1 || maxSegmentBytes < 512 || maxSegmentBytes > 64L * 1024 * 1024)
            throw new IllegalArgumentException("Invalid capture limits");
        this.zone = Objects.requireNonNull(zone); this.maxSegmentBytes = maxSegmentBytes;
        Objects.requireNonNull(busyRetryBudget);
        if(busyRetryBudget.isNegative()||busyRetryBudget.isZero()||busyRetryBudget.compareTo(Duration.ofSeconds(180))>0)
            throw new IllegalArgumentException("Invalid live storage retry budget");
        busyRetryNanos=busyRetryBudget.toNanos();
        parser = new LogLineParser(names); policy = LogImportService.policyFingerprint(names);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity), action -> {
            var thread = new Thread(action, "lotc-journal-live"); thread.setDaemon(true); ioThread = thread; return thread;
        }, new ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void terminated() {
                // Executor shutdown may interrupt an idle worker. FileChannel
                // cleanup must not inherit that flag and lose its closing marker.
                // closing=true still prevents any prolonged database retry here.
                boolean interrupted=Thread.interrupted();
                try { finishSession(); } catch (Exception failure) { fail("CLOSE_FAILED"); }
                finally { releaseFiles(); state = State.CLOSED; if(interrupted)Thread.currentThread().interrupt(); }
            }
        };
    }

    public Snapshot snapshot() { return new Snapshot(state, accepted.get(), pending.get(), saved.get(), errorCode, paused); }

    /** Startup recovery takes the profile lock, repairs only incomplete owned tails, and does not invent a clean end. */
    public CompletableFuture<Void> recover() { return submit(() -> { initialize(); return null; }, false); }

    public CompletableFuture<Void> start() {
        return submit(() -> {
            initialize(); joined = true;
            if (failed) throw new IOException("Capture requires restart after a storage failure");
            if (!paused && session == null) beginSession();
            if(!healthyState(paused ? State.PAUSED : State.CAPTURING))throw new IOException("Capture failed while starting");
            return null;
        }, false);
    }

    public CompletableFuture<Void> endSession() {
        return submit(() -> { joined = false; finishSession(); healthyState(paused ? State.PAUSED : State.IDLE); return null; }, false);
    }

    public CompletableFuture<Void> pause(boolean value) {
        return submit(() -> {
            initialize(); liveStorage(()->{repository.saveLivePaused(value);return null;}); paused = value;
            if (value) finishSession(); else if (joined && !failed && session == null) beginSession();
            healthyState(value ? State.PAUSED : joined ? State.CAPTURING : State.IDLE);
            return null;
        }, false);
    }

    public CompletableFuture<Receipt> receive(String displayText, Instant receivedAt, LiveEvent.Kind kind) {
        if (displayText == null || displayText.length() > 65_536 || receivedAt == null || kind == null)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid received message"));
        return submit(() -> {
            if (paused) return new Receipt(Status.PAUSED, null, 0);
            if (!joined || session == null) return new Receipt(Status.IGNORED, null, 0);
            if (failed) throw new IOException("Capture stopped after a storage failure");
            // Reuse original channel/name proof from the historical parser. Parse the first
            // physical display line, then normalize/privacy-check the entire component text.
            int newline = displayText.indexOf('\n');
            String firstLine = newline < 0 ? displayText : displayText.substring(0, newline);
            String clock = receivedAt.atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            var parsed = parser.parse("[" + clock + "] [Render thread/INFO]: [CHAT] " + firstLine, 1);
            Channel channel = parsed.map(line -> line.channel()).orElse(Channel.UNKNOWN);
            if (newline >= 0 && channel == Channel.SYSTEM) channel = Channel.UNKNOWN; // Whole-message noise matching remains authoritative.
            var clean = normalizer.clean(displayText, channel);
            if (clean.disposition() != DialogueCleanup.Disposition.DIALOGUE) return new Receipt(Status.IGNORED, null, 0);
            if (channel == Channel.PRIVATE_MESSAGE || channel == Channel.OOC || channel == Channel.SYSTEM)
                return new Receipt(Status.IGNORED, null, 0);
            var event = new LiveEvent(session.id(), Math.addExact(sequence, 1), receivedAt, zone.getRules().getOffset(receivedAt).getTotalSeconds(),
                kind, clean.text(), channel, parsed.flatMap(line -> line.observedSpeaker()).orElse(null));
            byte[] record = LiveJournalCodec.event(event); // Validate Unicode/size before creating a payload file.
            accepted.incrementAndGet();
            rotate(receivedAt, record.length);
            write(output, record); output.force(true); // SAVED is never acknowledged on enqueue or flush alone.
            sequence = event.sequence();
            liveStorage(()->repository.appendLiveEvent(session,event));
            saved.incrementAndGet();
            try { appendReadable(readablePath, event); }
            catch (IOException projectionFailure) { fail("READABLE_VIEW_FAILED"); } // Authority and SQL are already durable.
            return new Receipt(Status.SAVED, event.sessionId(), event.sequence());
        }, true);
    }

    /** Read-only import of a complete standalone versioned session file; event IDs, not text, reconcile repeats. */
    public CompletableFuture<Void> importJournal(Path path) {
        return submit(() -> {
            initialize();
            final Journal journal;
            try { journal = readJournal(path.toAbsolutePath().normalize(), false); }
            catch (Exception invalidInput) { throw new InvalidImportException(invalidInput); }
            applyJournal(journal);
            return null;
        }, false);
    }

    private void initialize() throws Exception {
        if (initialized) return;
        Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid live root");
        if (profileLock == null) {
            lockChannel = FileChannel.open(root.resolve(".capture.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            profileLock = lockChannel.tryLock();
            if (profileLock == null) throw new IOException("Live profile is already owned");
        }
        var paths = new ArrayList<Path>();
        try (var directories = Files.newDirectoryStream(root)) {
            for (Path directory : directories) {
                if (!DAY.matcher(directory.getFileName().toString()).matches() || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                LocalDate.parse(directory.getFileName().toString());
                try (var files = Files.newDirectoryStream(directory)) {
                    for (Path file : files) {
                        if (FILE_NAME.matcher(file.getFileName().toString()).matches() && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                            if (paths.size() >= 100_000) throw new IOException("Too many owned live segments");
                            paths.add(file);
                        }
                    }
                }
            }
        }
        // Segment numbers are monotonic across date changes, including a backwards clock.
        paths.sort(Comparator.comparing(path -> path.getFileName().toString()));
        // Fully validate each file before applying its first event. This also prevents a
        // complete malformed trailing record from creating a partially imported source.
        for (Path path : paths) {
            var journal = readJournal(path, true);
            if (journal == null) continue; // Creation died before its first complete header.
            applyJournal(journal);
            rebuildReadable(path, journal.events());
        }
        paused = liveStorage(repository::livePaused); initialized = true;
        healthyState(paused ? State.PAUSED : State.IDLE);
    }

    private void beginSession() throws Exception {
        String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("lotc-live-v1:" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)));
        session = new LiveSession(id, Instant.now(), zone.getId(), policy); sequence = 0; segment = 0; day = null;
        liveStorage(()->{repository.beginLiveSession(session);return null;});
    }

    private void rotate(Instant receivedAt, int recordBytes) throws IOException {
        LocalDate eventDay = receivedAt.atZone(zone).toLocalDate();
        if (output != null && day.equals(eventDay) && output.size() + recordBytes <= maxSegmentBytes) return;
        if (output != null) { output.force(true); output.close(); output = null; }
        if (++segment > 99_999_999) throw new IOException("Too many live segments");
        Path directory = root.resolve(eventDay.toString()); Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid live date directory");
        Path path = directory.resolve(session.id() + "-" + String.format(java.util.Locale.ROOT, "%08d", segment) + SUFFIX);
        output = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        readablePath = readablePath(path);
        if (Files.exists(readablePath, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unexpected readable live view");
        day = eventDay; write(output, LiveJournalCodec.header(session)); output.force(true);
        // On supported local filesystems, force the directory entry as well as file content.
        forceDirectory(directory); forceDirectory(root);
    }

    private static void forceDirectory(Path directory) throws IOException {
        // Windows does not expose directory FileChannels; file force remains mandatory there.
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return;
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    private void finishSession() throws Exception {
        if (session == null) return;
        try {
            if (!failed) {
                if (output != null) { write(output, LiveJournalCodec.end(session.id(), sequence)); output.force(true); }
                liveStorage(()->{repository.closeLiveSession(session.id());return null;});
            }
        } finally {
            if (output != null) { output.close(); output = null; }
            session = null; sequence = 0; segment = 0; day = null; readablePath = null;
        }
    }

    private record Journal(LiveSession session, List<LiveEvent> events, boolean ended) {}

    private Journal readJournal(Path path, boolean owned) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid live journal file");
        var filename = FILE_NAME.matcher(path.getFileName().toString());
        if (owned && (!filename.matches() || !path.getParent().getParent().equals(root))) throw new IOException("Invalid owned live path");
        var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size() > 65L * 1024 * 1024) throw new IOException("Live segment exceeds recovery limit");
        byte[] bytes;
        try (var input = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            var chunk = ByteBuffer.allocate(8192); var snapshot = new ByteArrayOutputStream();
            while (input.read(chunk) != -1) {
                chunk.flip();
                if (snapshot.size() + chunk.remaining() > 65L * 1024 * 1024) throw new IOException("Growing live segment exceeds recovery limit");
                snapshot.write(chunk.array(), 0, chunk.remaining()); chunk.clear();
            }
            bytes = snapshot.toByteArray();
        }
        var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !Objects.equals(before.fileKey(), after.fileKey()))
            throw new IOException("Live journal changed during validation");
        LiveSession original = null; boolean ended = false; long lastSequence = -1;
        var events = new ArrayList<LiveEvent>(); int lastComplete = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (i - lastComplete >= LiveJournalCodec.MAX_RECORD_BYTES) throw new IOException("Oversized live journal record");
            if (bytes[i] != '\n') continue;
            var record = LiveJournalCodec.decode(java.util.Arrays.copyOfRange(bytes, lastComplete, i));
            if (original == null) {
                if (!record.type().equals("session")) throw new IOException("Live journal header is missing");
                original = record.session();
                if (owned && !original.id().equals(filename.group(1))) throw new IOException("Live filename/header mismatch");
            } else if (ended) throw new IOException("Unexpected data after live session end");
            else if (record.type().equals("event")) {
                LiveEvent event = record.event();
                if (!original.id().equals(event.sessionId()) || (lastSequence >= 0 && event.sequence() != lastSequence + 1)
                    || (!owned && lastSequence < 0 && event.sequence() != 1)
                    || ZoneId.of(original.zoneId()).getRules().getOffset(event.receivedAt()).getTotalSeconds() != event.offsetSeconds())
                    throw new IOException("Invalid live journal event order/provenance");
                var clean = normalizer.clean(event.text(), event.channel());
                if (clean.disposition() != DialogueCleanup.Disposition.DIALOGUE || !event.text().equals(clean.text()))
                    throw new IOException("Noncanonical or excluded live journal payload");
                events.add(event); lastSequence = event.sequence();
            } else if (record.type().equals("end:" + original.id())) {
                if (record.endSequence() != (lastSequence < 0 ? 0 : lastSequence)) throw new IOException("Invalid live ending checkpoint");
                ended = true;
            } else throw new IOException("Unexpected live journal record");
            lastComplete = i + 1;
        }
        if (original == null && owned && lastComplete == 0) {
            // A new file can exist before even its header was forced. Preserve those
            // unacknowledged bytes separately; never guess a session from a partial header.
            Files.move(path, path.resolveSibling(path.getFileName() + ".incomplete-" + UUID.randomUUID()));
            forceDirectory(path.getParent()); return null;
        }
        if (lastComplete < bytes.length) {
            if (!owned || original == null) throw new IOException("Incomplete live journal record");
            try (var repair = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                repair.truncate(lastComplete); repair.force(true);
            }
        }
        if (original == null || (!owned && !ended)) throw new IOException("Journal import requires a complete closed session");
        return new Journal(original, List.copyOf(events), ended);
    }

    private void applyJournal(Journal journal) throws Exception {
        liveStorage(()->{repository.beginLiveSession(journal.session());return null;});
        for (var event : journal.events()) liveStorage(()->repository.appendLiveEvent(journal.session(),event));
        if (journal.ended()) liveStorage(()->{repository.closeLiveSession(journal.session().id());return null;});
    }

    private static Path readablePath(Path journal) {
        String name = journal.getFileName().toString();
        return journal.resolveSibling(name.substring(0, name.length() - SUFFIX.length()) + ".chat.txt");
    }
    private static byte[] readable(LiveEvent event) {
        String clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssxxx").format(event.receivedAt().atOffset(java.time.ZoneOffset.ofTotalSeconds(event.offsetSeconds())));
        return ("[" + clock + "] " + event.text() + "\n").getBytes(StandardCharsets.UTF_8);
    }
    private static void appendReadable(Path path, LiveEvent event) throws IOException {
        try (var view = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
            write(view, readable(event));
        }
    }
    private static void rebuildReadable(Path path, List<LiveEvent> events) throws IOException {
        Path target = readablePath(path);
        if (Files.isSymbolicLink(target)) throw new IOException("Refusing linked readable view");
        Path temporary = target.resolveSibling(target.getFileName() + ".rebuild-" + UUID.randomUUID());
        try {
            try (var view = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                for (var event : events) write(view, readable(event)); view.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void write(FileChannel channel, byte[] bytes) throws IOException {
        var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer);
    }
    private void fail(String code) { errorCode = code; state = State.ERROR; }
    private synchronized void terminalFailure(String code) {
        if(!failed){failed=true;fail(code);}
    }
    private synchronized boolean healthyState(State next) {
        if(failed)return false;
        state=next;return true;
    }

    private static final class BusyTimeoutException extends IOException {
        BusyTimeoutException(StorageBusyException cause) { super("Live storage remained busy until its retry deadline",cause); }
    }

    /** Retry only idempotent database operations; journal/force/sequence changes stay outside this loop. */
    private <T>T liveStorage(Work<T> operation) throws Exception {
        long started=System.nanoTime();
        StorageBusyException lastBusy=null;
        while(true){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Live storage retry interrupted");
            if(lastBusy!=null){
                if(closing||failed)throw lastBusy;
                if(System.nanoTime()-started>=busyRetryNanos)throw new BusyTimeoutException(lastBusy);
            }
            try{return operation.run();}
            catch(StorageBusyException busy){
                lastBusy=busy;
                // terminated() also closes a session. It must never enter a long
                // retry after the executor has removed its last interruptible worker.
                if(closing||failed)throw busy;
                long remaining=busyRetryNanos-(System.nanoTime()-started);
                if(remaining<=0)throw new BusyTimeoutException(busy);
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(50)));
            }
        }
    }

    private static final class InvalidImportException extends IOException {
        InvalidImportException(Throwable cause) { super("Invalid closed journal input", cause); }
    }
    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private void settle(CompletableFuture<?> future) {
        if(Boolean.TRUE.equals(outstanding.remove(future)))pending.decrementAndGet();
    }
    private <T> CompletableFuture<T> submit(Work<T> action, boolean receiving) {
        var future = new CompletableFuture<T>();
        if (closing) return CompletableFuture.failedFuture(new RejectedExecutionException("Capture is closed"));
        if (receiving) pending.incrementAndGet();
        outstanding.put(future,receiving);
        future.whenComplete((result,error)->settle(future));
        try {
            worker.execute(() -> {
                T result = null; Throwable failure = null;
                try { result = action.run(); }
                catch (Throwable error) { if (!(error instanceof InvalidImportException)) terminalFailure(error instanceof BusyTimeoutException?"CAPTURE_DATABASE_BUSY_TIMEOUT":"CAPTURE_STORAGE_FAILED"); failure = error; }
                settle(future);
                if (failure == null) future.complete(result); else future.completeExceptionally(failure);
            });
        } catch (RejectedExecutionException full) {
            if(receiving)fail("CAPTURE_QUEUE_FULL");else terminalFailure("CAPTURE_CONTROL_QUEUE_FULL");
            settle(future);
            future.completeExceptionally(full);
        }
        return future;
    }

    private void releaseFiles() {
        try { if (output != null) output.close(); } catch (IOException ignored) { fail("CLOSE_FAILED"); }
        try { if (profileLock != null) profileLock.release(); } catch (IOException ignored) { fail("CLOSE_FAILED"); }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { fail("CLOSE_FAILED"); }
    }

    @Override public void close() {
        if (closing) return;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        closing = true; worker.shutdown();
        if (Thread.currentThread() == ioThread) return;
        try {
            if (!worker.awaitTermination(Math.max(0,deadline-System.nanoTime()), TimeUnit.NANOSECONDS)) {
                fail("CLOSE_TIMEOUT"); worker.shutdownNow();
                for (var future : outstanding.keySet()) { settle(future); future.completeExceptionally(new RejectedExecutionException("Capture shutdown timed out")); }
            } else {
                Thread actual=ioThread;
                long remaining=deadline-System.nanoTime();
                if(actual!=null&&actual.isAlive()&&remaining>0)TimeUnit.NANOSECONDS.timedJoin(actual,remaining);
                if(actual!=null&&actual.isAlive())fail("CLOSE_TIMEOUT");
            }
        } catch (InterruptedException interrupted) {
            worker.shutdownNow(); Thread.currentThread().interrupt();
            for (var future : outstanding.keySet()) { settle(future); future.completeExceptionally(interrupted); }
        }
    }
}
