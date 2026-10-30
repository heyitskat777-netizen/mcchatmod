package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.domain.LiveEvent;
import dev.kat.lotcjournal.ingestion.LiveCaptureService;
import java.time.Instant;
import java.util.concurrent.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/** Client-thread adapter: a bounded readiness buffer, no disk, SQL, network or attribution. */
final class LiveChatAdapter {
    private record Incoming(String text,Instant received,LiveEvent.Kind kind) {}
    private final LiveCaptureService capture;
    private final CompletableFuture<Void> ready;
    private final ArrayBlockingQueue<Incoming> waiting=new ArrayBlockingQueue<>(256);
    private boolean connected,started;
    private CompletableFuture<Void> starting;
    private long overflow;
    LiveChatAdapter(LiveCaptureService capture,CompletableFuture<Void> ready){this.capture=capture;this.ready=ready;}
    void register(){
        ClientPlayConnectionEvents.JOIN.register((handler,sender,client)->{connected=true;started=false;starting=null;waiting.clear();});
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{connected=false;started=false;starting=null;waiting.clear();capture.endSession();});
        ClientReceiveMessageEvents.CHAT.register((message,signed,sender,params,time)->accept(message.getString(),time,LiveEvent.Kind.CHAT));
        ClientReceiveMessageEvents.GAME.register((message,overlay)->{if(!overlay)accept(message.getString(),Instant.now(),LiveEvent.Kind.GAME);});
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(!connected || !ready.isDone() || ready.isCompletedExceptionally())return;
            // A rejected/failed start cannot license messages for an older session.
            if(!started){
                if(starting==null)starting=capture.start();
                if(!starting.isDone())return;
                if(starting.isCompletedExceptionally()||starting.isCancelled()){waiting.clear();return;}
                started=true;
            }
            Incoming value;while((value=waiting.poll())!=null)capture.receive(value.text(),value.received(),value.kind());
        });
    }
    private void accept(String text,Instant time,LiveEvent.Kind kind){
        if(!connected)return;
        if(text.length()>65536 || !waiting.offer(new Incoming(text,time,kind)))overflow++;
    }
    long overflow(){return overflow;}
}
