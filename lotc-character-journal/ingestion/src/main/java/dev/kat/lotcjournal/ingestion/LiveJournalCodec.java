package dev.kat.lotcjournal.ingestion;

import dev.kat.lotcjournal.domain.Channel;
import dev.kat.lotcjournal.domain.LiveEvent;
import dev.kat.lotcjournal.domain.LiveSession;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Small strict codec for the flat, versioned owned journal format; no component/hover JSON is retained. */
final class LiveJournalCodec {
    static final int MAX_RECORD_BYTES = 512 * 1024;
    private LiveJournalCodec() {}

    static byte[] header(LiveSession s) {
        return encode("type", "session", "sessionId", s.id(), "startedAt", s.startedAt().toString(), "zoneId", s.zoneId(), "policy", s.policyFingerprint());
    }
    static byte[] event(LiveEvent e) {
        return encode("type", "event", "sessionId", e.sessionId(), "sequence", e.sequence(), "receivedAt", e.receivedAt().toString(),
            "offsetSeconds", e.offsetSeconds(), "kind", e.kind().name(), "text", e.text(), "channel", e.channel().name(), "observedSpeaker", e.observedSpeaker());
    }
    static byte[] end(String sessionId, long sequence) { return encode("type", "end", "sessionId", sessionId, "sequence", sequence); }

    private static byte[] encode(Object... pairs) {
        var out = new StringBuilder("{\"version\":1");
        for (int i = 0; i < pairs.length; i += 2) {
            out.append(','); string(out, (String) pairs[i]); out.append(':');
            Object value = pairs[i + 1];
            if (value == null) out.append("null");
            else if (value instanceof Number) out.append(value);
            else string(out, (String) value);
        }
        out.append("}\n");
        try {
            var buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(out));
            if (buffer.remaining() > MAX_RECORD_BYTES) throw new IllegalArgumentException("Live record is too large");
            byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes;
        } catch (CharacterCodingException invalid) { throw new IllegalArgumentException("Invalid dialogue Unicode", invalid); }
    }
    private static void string(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        out.append('"');
    }

    record Record(String type, LiveSession session, LiveEvent event, long endSequence) {}
    static Record decode(byte[] bytes) {
        final String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException invalid) { throw new IllegalArgumentException("Invalid journal encoding", invalid); }
        Map<String, Object> fields = new Reader(text).read();
        if (!Long.valueOf(1).equals(fields.get("version"))) throw new IllegalArgumentException("Unsupported journal version");
        String type = value(fields, "type");
        return switch (type) {
            case "session" -> {
                keys(fields, "version", "type", "sessionId", "startedAt", "zoneId", "policy");
                yield new Record(type, new LiveSession(value(fields, "sessionId"), Instant.parse(value(fields, "startedAt")), value(fields, "zoneId"), value(fields, "policy")), null, 0);
            }
            case "event" -> {
                keys(fields, "version", "type", "sessionId", "sequence", "receivedAt", "offsetSeconds", "kind", "text", "channel", "observedSpeaker");
                Object speaker = fields.get("observedSpeaker");
                if (speaker != null && !(speaker instanceof String)) throw new IllegalArgumentException("Invalid observed speaker");
                yield new Record(type, null, new LiveEvent(value(fields, "sessionId"), number(fields, "sequence"), Instant.parse(value(fields, "receivedAt")),
                    Math.toIntExact(number(fields, "offsetSeconds")), LiveEvent.Kind.valueOf(value(fields, "kind")), value(fields, "text"), Channel.valueOf(value(fields, "channel")), (String) speaker), 0);
            }
            case "end" -> {
                keys(fields, "version", "type", "sessionId", "sequence");
                // Validate the ID even for an empty session's footer.
                if (!value(fields, "sessionId").matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid session ID");
                long sequence = number(fields, "sequence");
                if (sequence < 0) throw new IllegalArgumentException("Invalid ending sequence");
                yield new Record(type + ":" + value(fields, "sessionId"), null, null, sequence);
            }
            default -> throw new IllegalArgumentException("Unsupported journal record");
        };
    }
    private static void keys(Map<String, Object> fields, String... expected) {
        if (!fields.keySet().equals(Set.of(expected))) throw new IllegalArgumentException("Unexpected journal fields");
    }
    private static String value(Map<String, Object> fields, String name) {
        if (!(fields.get(name) instanceof String value)) throw new IllegalArgumentException("Missing journal text field");
        return value;
    }
    private static long number(Map<String, Object> fields, String name) {
        if (!(fields.get(name) instanceof Long value)) throw new IllegalArgumentException("Missing journal integer field");
        return value;
    }

    private static final class Reader {
        private final String text; private int cursor;
        Reader(String text) { this.text = text; }
        Map<String, Object> read() {
            var fields = new LinkedHashMap<String, Object>(); take('{');
            if (!peek('}')) do {
                String key = string(); take(':'); Object value;
                if (peek('"')) value = string();
                else if (text.startsWith("null", cursor)) { cursor += 4; value = null; }
                else {
                    int start = cursor; if (peek('-')) cursor++;
                    while (cursor < text.length() && text.charAt(cursor) >= '0' && text.charAt(cursor) <= '9') cursor++;
                    String token = text.substring(start, cursor);
                    if (!token.matches("-?(?:0|[1-9][0-9]*)")) throw bad();
                    value = Long.parseLong(token);
                }
                if (fields.containsKey(key)) throw bad(); fields.put(key, value);
                if (!peek(',')) break; cursor++;
                if (peek('}')) throw bad();
            } while (true);
            take('}'); if (cursor != text.length()) throw bad(); return fields;
        }
        String string() {
            take('"'); var out = new StringBuilder();
            while (cursor < text.length()) {
                char c = text.charAt(cursor++);
                if (c == '"') {
                    try { StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(out)); }
                    catch (CharacterCodingException invalid) { throw bad(); }
                    return out.toString();
                }
                if (c < 32) throw bad();
                if (c != '\\') { out.append(c); continue; }
                if (cursor >= text.length()) throw bad(); char escaped = text.charAt(cursor++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'n' -> out.append('\n'); case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                    case 'b' -> out.append('\b'); case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (cursor + 4 > text.length()) throw bad();
                        String digits = text.substring(cursor, cursor + 4);
                        if (!digits.matches("[0-9a-fA-F]{4}")) throw bad();
                        out.append((char) Integer.parseInt(digits, 16)); cursor += 4;
                    }
                    default -> throw bad();
                }
            }
            throw bad();
        }
        boolean peek(char c) { return cursor < text.length() && text.charAt(cursor) == c; }
        void take(char c) { if (!peek(c)) throw bad(); cursor++; }
        IllegalArgumentException bad() { return new IllegalArgumentException("Malformed live journal record"); }
    }
}
