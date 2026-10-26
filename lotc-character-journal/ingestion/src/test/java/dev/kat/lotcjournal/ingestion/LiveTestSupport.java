package dev.kat.lotcjournal.ingestion;

import dev.kat.lotcjournal.domain.LiveEvent;
import dev.kat.lotcjournal.domain.LiveRepository;
import dev.kat.lotcjournal.domain.LiveSession;
import dev.kat.lotcjournal.parsing.DialogueTextNormalizer;
import dev.kat.lotcjournal.storage.SqliteJournalStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class LiveTestSupport {
    private LiveTestSupport() {}
    static SqliteJournalStore store(Path database) throws Exception {
        var store = new SqliteJournalStore(database, new DialogueTextNormalizer());
        store.initialize(); return store;
    }
    static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }
    static List<Path> journals(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".lotc-chat.jsonl")).sorted().toList();
        }
    }
    static boolean contains(Path root, String value) throws Exception {
        byte[] needle = value.getBytes(StandardCharsets.UTF_8);
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                byte[] bytes = Files.readAllBytes(path);
                outer: for (int start = 0; start + needle.length <= bytes.length; start++) {
                    for (int index = 0; index < needle.length; index++) if (bytes[start + index] != needle[index]) continue outer;
                    return true;
                }
            }
        }
        return false;
    }
    abstract static class ForwardingRepository implements LiveRepository {
        final LiveRepository delegate;
        ForwardingRepository(LiveRepository delegate) { this.delegate = delegate; }
        @Override public void beginLiveSession(LiveSession session) throws Exception { delegate.beginLiveSession(session); }
        @Override public boolean appendLiveEvent(LiveSession session, LiveEvent event) throws Exception { return delegate.appendLiveEvent(session, event); }
        @Override public void closeLiveSession(String sessionId) throws Exception { delegate.closeLiveSession(sessionId); }
        @Override public boolean livePaused() throws Exception { return delegate.livePaused(); }
        @Override public void saveLivePaused(boolean paused) throws Exception { delegate.saveLivePaused(paused); }
    }
}
