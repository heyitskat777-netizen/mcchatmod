package dev.kat.lotcjournal.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;

/** One cleaned received observation. A sequence counts eligible events, never excluded payloads. */
public record LiveEvent(String sessionId, long sequence, Instant receivedAt, int offsetSeconds,
                        Kind kind, String text, Channel channel, String observedSpeaker) {
    public enum Kind { CHAT, GAME }
    public LiveEvent {
        ImportSource.requireId(sessionId);
        if (sequence < 1) throw new IllegalArgumentException("Invalid live event sequence");
        Objects.requireNonNull(receivedAt);
        ZoneOffset.ofTotalSeconds(offsetSeconds);
        Objects.requireNonNull(kind);
        new TranscriptLine(sessionId, sequence, null, text, channel, observedSpeaker);
        if (observedSpeaker != null && (observedSpeaker.isBlank() || observedSpeaker.length() > 128))
            throw new IllegalArgumentException("Invalid observed speaker");
    }

    /** Payload-free receipt digest binds all event fields, including repeated dialogue's coordinates. */
    public String fingerprint() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[] {sessionId, Long.toString(sequence), receivedAt.toString(),
                    Integer.toString(offsetSeconds), kind.name(), text, channel.name(), observedSpeaker}) {
                if (value == null) digest.update(ByteBuffer.allocate(4).putInt(-1).array());
                else {
                    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
}
