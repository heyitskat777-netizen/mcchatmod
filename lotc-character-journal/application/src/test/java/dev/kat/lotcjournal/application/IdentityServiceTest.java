package dev.kat.lotcjournal.application;

import dev.kat.lotcjournal.domain.CharacterIdentity;
import dev.kat.lotcjournal.domain.IdentityRepository;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class IdentityServiceTest {
    @FunctionalInterface private interface Invocation { Object run(String method, Object[] arguments) throws Exception; }
    private static IdentityRepository repository(Invocation invocation) {
        return (IdentityRepository) Proxy.newProxyInstance(IdentityRepository.class.getClassLoader(),
            new Class<?>[] { IdentityRepository.class }, (proxy, method, arguments) -> invocation.run(method.getName(), arguments));
    }
    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }

    @Test void repositoryCallsRunOffCallerThreadInSubmissionOrderWithoutBlockingTheCaller() throws Exception {
        long caller = Thread.currentThread().threadId();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var order = new ArrayList<String>();
        var repository = repository((method, arguments) -> {
            assertNotEquals(caller, Thread.currentThread().threadId());
            assertEquals("lotc-identity-io", Thread.currentThread().getName());
            String query = (String) arguments[0]; order.add(query);
            if (query.equals("first")) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }
            return List.of();
        });
        try (var service = new IdentityService(repository)) {
            var first = service.identities("first"); assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = service.identities("second"); assertFalse(first.isDone()); assertFalse(second.isDone());
            release.countDown(); await(first); await(second);
            assertEquals(List.of("first", "second"), order);
        } finally { release.countDown(); }
    }

    @Test void denialReviewAndReversalUseTheIdentityWorkerAndPreserveArguments() throws Exception {
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString(), decision = UUID.randomUUID().toString();
        var calls = new ArrayList<String>(); var caller = Thread.currentThread();
        var value = new dev.kat.lotcjournal.domain.IdentitySeparation(decision, first, second, true);
        var repository = repository((method, arguments) -> {
            assertNotSame(caller, Thread.currentThread()); assertEquals("lotc-identity-io", Thread.currentThread().getName()); calls.add(method);
            return switch (method) {
                case "denyIdentityLink" -> { assertArrayEquals(new Object[]{first,second}, arguments); yield decision; }
                case "separations" -> List.of(value);
                case "undoSeparation" -> { assertArrayEquals(new Object[]{decision}, arguments); yield null; }
                default -> throw new AssertionError("Unexpected identity operation");
            };
        });
        try (var service = new IdentityService(repository)) {
            assertEquals(decision, await(service.denyIdentityLink(first,second)));
            assertEquals(List.of(value), await(service.separations())); await(service.undoSeparation(decision));
        }
        assertEquals(List.of("denyIdentityLink","separations","undoSeparation"),calls);
    }

    @Test void failedOperationCompletesItsFutureWithoutPoisoningLaterIdentityWork() throws Exception {
        var identity = new CharacterIdentity(UUID.randomUUID().toString(), "Synthetic Identity", "", Set.of(), "");
        var calls = new AtomicInteger();
        var repository = repository((method, arguments) -> {
            if (calls.getAndIncrement() == 0) throw new IOException("Synthetic identity read failure");
            return List.of(identity);
        });
        try (var service = new IdentityService(repository)) {
            var failure = assertThrows(ExecutionException.class, () -> await(service.identities("first")));
            assertInstanceOf(IOException.class, failure.getCause());
            assertEquals(List.of(identity), await(service.identities("retry")));
        }
    }

    @Test void boundedQueueRejectsExcessAndCloseCompletesEveryAbandonedFuture() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var repository = repository((method, arguments) -> {
            calls.incrementAndGet(); entered.countDown(); release.await(); return List.of();
        });
        var service = new IdentityService(repository);
        var accepted = new ArrayList<CompletableFuture<List<CharacterIdentity>>>();
        try {
            accepted.add(service.identities("blocking")); assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int index = 0; index < 64; index++) accepted.add(service.identities("queued " + index));
            var rejected = assertThrows(ExecutionException.class, () -> await(service.identities("overflow")));
            assertInstanceOf(RejectedExecutionException.class, rejected.getCause());
            service.close();
            assertTrue(accepted.stream().allMatch(CompletableFuture::isDone), "Shutdown must never strand queued UI futures");
            assertTrue(accepted.stream().allMatch(CompletableFuture::isCompletedExceptionally));
            assertEquals(1, calls.get(), "Abandoned queued work must not run after shutdown");
            assertThrows(ExecutionException.class, () -> await(service.identities("after close")));
        } finally { release.countDown(); service.close(); }
    }
}
