package dev.kat.lotcjournal.application;

import dev.kat.lotcjournal.domain.*;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;

/** Bounded scene I/O port. UI receives immutable snapshots and never opens SQL. */
public final class SceneService implements AutoCloseable {
    private final SceneRepository repository;
    private volatile Thread ioThread;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(64),task->{var thread=new Thread(task,"lotc-scene-io");thread.setDaemon(true);ioThread=thread;return thread;},
        new ThreadPoolExecutor.AbortPolicy());
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    public SceneService(SceneRepository repository) {this.repository=Objects.requireNonNull(repository);}
    public CompletableFuture<SceneSettings> settings() {return submit(repository::settings);}
    public CompletableFuture<Void> saveSettings(SceneSettings settings) {return submit(()->{repository.saveSettings(settings);return null;});}
    public CompletableFuture<Integer> refreshSuggestions() {return submit(repository::refreshSuggestions);}
    public CompletableFuture<ScenePage> scenes(String persona,boolean skipped,String query,int offset,int limit) {
        return submit(()->repository.scenes(persona,skipped,query,offset,limit));
    }
    public CompletableFuture<ScenePage> scenes(String persona,boolean skipped,boolean suggestedOnly,String query,int offset,int limit) {
        return submit(()->repository.scenes(persona,skipped,suggestedOnly,query,offset,limit));
    }
    public CompletableFuture<SceneDetail> scene(String id,String persona) {return submit(()->repository.scene(id,persona));}
    public CompletableFuture<TranscriptPage> transcript(String id,String persona,String query,int offset,int limit) {
        return submit(()->repository.transcript(id,persona,query,offset,limit));
    }
    public CompletableFuture<SceneDetail> saveScene(String id,long revision,String title,String location,List<SceneParticipant> participants) {
        var snapshot=List.copyOf(participants);
        return submit(()->repository.saveScene(id,revision,title,location,snapshot));
    }
    public CompletableFuture<Void> skipScene(String id,long revision) {return submit(()->{repository.skipScene(id,revision);return null;});}
    public CompletableFuture<List<String>> splitScene(String id,long revision,MessageReference firstOfSecond) {
        return submit(()->repository.splitScene(id,revision,firstOfSecond));
    }
    public CompletableFuture<String> combineScenes(String first,long firstRevision,String second,long secondRevision) {
        return submit(()->repository.combineScenes(first,firstRevision,second,secondRevision));
    }
    private <T> CompletableFuture<T> submit(Operation<T> operation) {
        var result=new CompletableFuture<T>();pending.add(result);result.whenComplete((v,e)->pending.remove(result));
        try {worker.execute(()->{try {result.complete(operation.run());}catch(Exception failure){result.completeExceptionally(failure);}});}
        catch(RejectedExecutionException failure){result.completeExceptionally(failure);}return result;
    }
    @Override public void close() {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);worker.shutdown();
        if(Thread.currentThread()!=ioThread)try {
            if(!worker.awaitTermination(3,TimeUnit.SECONDS))worker.shutdownNow();
            var thread=ioThread;long remaining=deadline-System.nanoTime();if(thread!=null && remaining>0)TimeUnit.NANOSECONDS.timedJoin(thread,remaining);
        }catch(InterruptedException interrupted){worker.shutdownNow();Thread.currentThread().interrupt();}
        else worker.shutdownNow();
        for(var future:pending)future.completeExceptionally(new IllegalStateException("Scene service closed"));
    }
    @FunctionalInterface private interface Operation<T> {T run() throws Exception;}
}
