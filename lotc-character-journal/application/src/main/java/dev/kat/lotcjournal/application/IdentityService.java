package dev.kat.lotcjournal.application;

import dev.kat.lotcjournal.domain.*;
import java.util.List;
import java.util.concurrent.*;

/** Bounded worker for explicit identity decisions; never guesses or calls a provider. */
public final class IdentityService implements AutoCloseable {
    private final IdentityRepository repository;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(64), task -> { var t = new Thread(task,"lotc-identity-io"); t.setDaemon(true); return t; },
        new ThreadPoolExecutor.AbortPolicy());
    private final java.util.Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    public IdentityService(IdentityRepository repository) { this.repository = repository; }
    public CompletableFuture<List<CharacterIdentity>> identities(String query) { return submit(() -> repository.identities(query)); }
    public CompletableFuture<Void> saveIdentity(CharacterIdentity value) { return submit(() -> {repository.saveIdentity(value);return null;}); }
    public CompletableFuture<List<Persona>> personas() { return submit(repository::personas); }
    public CompletableFuture<Void> createPersona(Persona value) { return submit(() -> {repository.createPersona(value);return null;}); }
    public CompletableFuture<String> activePersona() { return submit(repository::activePersona); }
    public CompletableFuture<Void> selectPersona(String id) { return submit(() -> {repository.selectPersona(id);return null;}); }
    public CompletableFuture<MemoryAssignment> assignment(MessageReference ref,String persona) { return submit(() -> repository.assignment(ref,persona)); }
    public CompletableFuture<Void> assign(MemoryAssignment value) { return submit(() -> {repository.assign(value);return null;}); }
    public CompletableFuture<Void> clearAssignment(MessageReference ref,String persona) { return submit(() -> {repository.clearAssignment(ref,persona);return null;}); }
    public CompletableFuture<TranscriptPage> transcript(String persona,String query,Channel channel,boolean favorites,int offset,int limit) {
        return submit(() -> repository.memoryTranscript(persona,query,channel,favorites,offset,limit));
    }
    public CompletableFuture<IdentityReviewData> reviewData() {return submit(repository::reviewData);}
    public CompletableFuture<IdentityReviewBatch> reviewQueue(IdentityReviewData snapshot) {return submit(()->IdentityReviewQueue.pending(snapshot,100));}
    public CompletableFuture<List<IdentityMerge>> merges() { return submit(repository::merges); }
    public CompletableFuture<TranscriptPage> characterTranscript(String identity,String query,Channel channel,CharacterEvidenceKind kind,boolean favorites,int offset,int limit) {
        return submit(() -> repository.characterTranscript(identity,query,channel,kind,favorites,offset,limit));
    }
    public CompletableFuture<String> mergeIdentity(String from,String to) { return submit(() -> repository.mergeIdentity(from,to)); }
    public CompletableFuture<Void> undoMerge(String decision) { return submit(() -> {repository.undoMerge(decision);return null;}); }
    public CompletableFuture<String> resolveIdentity(String id) { return submit(() -> repository.resolveIdentity(id)); }
    public CompletableFuture<List<IdentitySeparation>> separations() { return submit(repository::separations); }
    public CompletableFuture<String> denyIdentityLink(String first,String second) { return submit(() -> repository.denyIdentityLink(first,second)); }
    public CompletableFuture<Void> undoSeparation(String id) { return submit(() -> { repository.undoSeparation(id); return null; }); }
    public CompletableFuture<MessageTagReview> reviewTags(MessageReference message) { return submit(() -> repository.reviewTags(message)); }
    public CompletableFuture<MessageTagReview> saveReviewedTags(MessageReference message,long revision,List<CharacterTag> tags) {
        var snapshot=List.copyOf(tags);
        return submit(() -> repository.saveReviewedTags(message,revision,snapshot));
    }
    public CompletableFuture<MessageTagReview> restoreAutomaticTags(MessageReference message,long revision) {
        return submit(() -> repository.restoreAutomaticTags(message,revision));
    }
    private <T> CompletableFuture<T> submit(Operation<T> action) {
        var result = new CompletableFuture<T>(); pending.add(result); result.whenComplete((v,e) -> pending.remove(result));
        try {worker.execute(() -> {try {result.complete(action.run());} catch(Exception e) {result.completeExceptionally(e);}});}
        catch(RejectedExecutionException e) {result.completeExceptionally(e);} return result;
    }
    @Override public void close() {
        worker.shutdown();
        try {if(!worker.awaitTermination(3,TimeUnit.SECONDS)) worker.shutdownNow();}
        catch(InterruptedException e) {worker.shutdownNow();Thread.currentThread().interrupt();}
        for(var future:pending) future.completeExceptionally(new IllegalStateException("Identity service closed"));
    }
    @FunctionalInterface private interface Operation<T> {T run() throws Exception;}
}
