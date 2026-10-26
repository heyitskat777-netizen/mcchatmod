package dev.kat.lotcjournal.ingestion;

import dev.kat.lotcjournal.domain.LiveEvent;
import dev.kat.lotcjournal.domain.LiveSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Deliberately dies without service.close(); launched only against synthetic @TempDir profiles. */
public final class LiveCrashProcess {
    private LiveCrashProcess() {}
    private static void boundary(Path marker, String sessionId, long sequence) throws Exception {
        Path temporary = marker.resolveSibling("boundary.pending");
        Files.writeString(temporary, sessionId + "\n" + sequence);
        Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
    }
    public static void main(String[] arguments) throws Exception {
        String mode = arguments[0]; Path profile = Path.of(arguments[1]);
        Path marker = profile.resolve("boundary.txt");
        var repository = LiveTestSupport.store(profile.resolve("journal.sqlite"));
        if (mode.equals("before-db")) {
            var blocking = new LiveTestSupport.ForwardingRepository(repository) {
                @Override public boolean appendLiveEvent(LiveSession session, LiveEvent event) throws Exception {
                    // Production calls the repository only after appending and forcing the authoritative record.
                    boundary(marker, event.sessionId(), event.sequence());
                    new CountDownLatch(1).await();
                    throw new AssertionError("Parent must forcibly terminate this process at the boundary");
                }
            };
            var service = new LiveCaptureService(profile.resolve("live"), blocking, Set.of());
            service.start().get(10, TimeUnit.SECONDS);
            service.receive("[Quiet] Synthetic Visitor waves.", Instant.parse("2026-10-25T12:00:00Z"), LiveEvent.Kind.CHAT)
                .get(30, TimeUnit.SECONDS);
            throw new AssertionError("Boundary unexpectedly returned");
        }
        if (mode.equals("after-ack")) {
            var service = new LiveCaptureService(profile.resolve("live"), repository, Set.of());
            service.start().get(10, TimeUnit.SECONDS);
            var first = service.receive("[Quiet] Synthetic Visitor waves.", Instant.parse("2026-10-25T12:00:00Z"), LiveEvent.Kind.CHAT)
                .get(10, TimeUnit.SECONDS);
            var second = service.receive("[Quiet] Synthetic Visitor waves.", Instant.parse("2026-10-25T12:00:00Z"), LiveEvent.Kind.GAME)
                .get(10, TimeUnit.SECONDS);
            if (first.status() != LiveCaptureService.Status.SAVED || second.status() != LiveCaptureService.Status.SAVED)
                throw new AssertionError("Expected two durable acknowledgements");
            boundary(marker, second.sessionId(), second.sequence());
            Runtime.getRuntime().halt(23); // No shutdown hooks, clean session ending or executor drain.
        }
        if (mode.equals("recover")) {
            try (var service = new LiveCaptureService(profile.resolve("live"), repository, Set.of())) {
                service.recover().get(10, TimeUnit.SECONDS);
            }
            return;
        }
        throw new IllegalArgumentException("Unknown synthetic subprocess mode");
    }
}
