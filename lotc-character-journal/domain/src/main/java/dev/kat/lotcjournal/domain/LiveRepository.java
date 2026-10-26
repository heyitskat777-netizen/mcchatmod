package dev.kat.lotcjournal.domain;

/** Live-only mutable source/checkpoint port. All calls belong on a background worker. */
public interface LiveRepository {
    void beginLiveSession(LiveSession session) throws Exception;
    /** Atomically insert observation and replay receipt; false means the identical event already committed. */
    boolean appendLiveEvent(LiveSession session, LiveEvent event) throws Exception;
    void closeLiveSession(String sessionId) throws Exception;
    boolean livePaused() throws Exception;
    void saveLivePaused(boolean paused) throws Exception;
}
