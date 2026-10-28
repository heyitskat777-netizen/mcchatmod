package dev.kat.lotcjournal.application;

import dev.kat.lotcjournal.domain.*;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class SceneServiceTest {
    @FunctionalInterface private interface Invocation { Object run(String method, Object[] arguments) throws Exception; }
    private static SceneRepository repository(Invocation invocation) {
        return (SceneRepository) Proxy.newProxyInstance(SceneRepository.class.getClassLoader(), new Class<?>[] { SceneRepository.class },
            (proxy, method, arguments) -> invocation.run(method.getName(), arguments));
    }
    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }

    @Test void queuedSceneEditsSnapshotParticipantsAndRunOnlyOnTheWorkerBeforeCleanThreadExit() throws Exception {
        String scene = UUID.randomUUID().toString(), actor = UUID.randomUUID().toString();
        var participant = new SceneParticipant(actor, "Synthetic Actor", EncounterRole.UNCERTAIN, null);
        var draft = new ArrayList<>(List.of(participant)); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var io = new AtomicReference<Thread>(); long caller = Thread.currentThread().threadId();
        var expected = new SceneDetail(new SceneSummary(scene, "Reviewed scene", "", SceneState.CONFIRMED, 1, 2), List.of(participant), new SceneStatistics(1, 1, 0, 0, 0L, 0L));
        var repository = repository((method, arguments) -> {
            assertNotEquals(caller, Thread.currentThread().threadId()); io.set(Thread.currentThread());
            if (method.equals("settings")) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return SceneSettings.DEFAULT; }
            if (method.equals("saveScene")) {
                assertEquals(List.of(participant), arguments[4]);
                assertThrows(UnsupportedOperationException.class, () -> ((List<?>) arguments[4]).clear());
                return expected;
            }
            throw new AssertionError("Unexpected synthetic scene operation " + method);
        });
        var service = new SceneService(repository);
        try {
            var blocked = service.settings(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            var saved = service.saveScene(scene, 1, "Reviewed scene", "", draft); draft.clear();
            assertFalse(saved.isDone()); release.countDown(); await(blocked); assertEquals(expected, await(saved));
            service.close(); assertNotNull(io.get()); assertFalse(io.get().isAlive(), "close must wait for actual scene I/O thread exit");
        } finally { release.countDown(); service.close(); }
    }

    @Test void failedSceneOperationCompletesItsFutureAndAllowsARealRetry() throws Exception {
        var calls = new AtomicInteger();
        var repository = repository((method, arguments) -> { if (calls.getAndIncrement() == 0) throw new IOException("Synthetic scene refresh failure"); return 3; });
        try (var service = new SceneService(repository)) {
            var failure = assertThrows(ExecutionException.class, () -> await(service.refreshSuggestions()));
            assertInstanceOf(IOException.class, failure.getCause()); assertEquals(3, await(service.refreshSuggestions()));
        }
    }

    @Test void overloadIsBoundedAndShutdownCompletesEveryAcceptedAbandonedFuture() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var repository = repository((method, arguments) -> { calls.incrementAndGet(); entered.countDown(); release.await(); return 0; });
        var service = new SceneService(repository); var accepted = new ArrayList<CompletableFuture<Integer>>();
        try {
            accepted.add(service.refreshSuggestions()); assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Integer> rejected = null;
            for (int count = 0; count < 1024; count++) {
                var request = service.refreshSuggestions(); if (request.isCompletedExceptionally()) { rejected = request; break; } accepted.add(request);
            }
            assertNotNull(rejected, "A blocked worker must not accept an unbounded queue"); var overflow = rejected;
            assertInstanceOf(RejectedExecutionException.class, assertThrows(ExecutionException.class, () -> await(overflow)).getCause());
            service.close(); assertTrue(accepted.stream().allMatch(CompletableFuture::isCompletedExceptionally));
            assertEquals(1, calls.get()); assertThrows(ExecutionException.class, () -> await(service.refreshSuggestions()));
        } finally { release.countDown(); service.close(); }
    }
}
