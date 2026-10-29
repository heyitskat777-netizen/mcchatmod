package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Fixed synthetic workload; no production lookup supplies the expected counts or payload digest. */
final class ArchiveScaleFixture {
    static final int ROWS = 100_000, SOURCES = 100, IDENTITIES = 2_000, ACTIVE_ACTORS = 1_800;
    static final int PER_SOURCE = 1_000, PER_SCENE = 100, SCENES = 1_000, CANDIDATE_PAIRS = 100;
    static final String LITERAL = "SCALE_LITERAL_%_\\' Ω", COMMON = "SCALE_COMMON_TOKEN";
    static final String RETRO_ALIAS = "Synthetic Retrospective Alias";
    static final String PERSONA_FIRST = "5343414c-4500-0001-0000-000000000001", PERSONA_SECOND = "5343414c-4500-0001-0000-000000000002";
    static final Comparator<MessageReference> ORDER = Comparator.comparing(MessageReference::sourceId).thenComparingLong(MessageReference::sourceLine);
    record Source(ImportSource metadata, List<TranscriptLine> rows) {}
    final List<Source> sources;
    final List<MessageReference> allReferences, literalReferences, firstActorReferences, backfilledReferences;
    final String payloadDigest;

    ArchiveScaleFixture() throws Exception {
        var sourceList = new ArrayList<Source>(); var all = new ArrayList<MessageReference>(); var literal = new ArrayList<MessageReference>();
        var actor = new ArrayList<MessageReference>(); var backfilled = new ArrayList<MessageReference>();
        for (int sourceIndex = 0; sourceIndex < SOURCES; sourceIndex++) {
            var texts = new ArrayList<String>(); var clocks = new ArrayList<String>(); var bytes = new StringBuilder();
            for (int offset = 0; offset < PER_SOURCE; offset++) {
                int scene = sourceIndex * 10 + offset / PER_SCENE, position = offset % PER_SCENE;
                int actorIndex = (scene % 900) * 2 + position % 2;
                String text = name(actorIndex) + (position % 5 == 0 ? " waves. " : " says: ") + COMMON + " " + sceneToken(scene) + "."
                    + (position % 10 == 0 ? " " + LITERAL : "") + (position == 99 ? " " + RETRO_ALIAS + "." : "");
                String clock = LocalTime.ofSecondOfDay(36_000 + offset / PER_SCENE * 1_200L + position).format(java.time.format.DateTimeFormatter.ISO_LOCAL_TIME);
                texts.add(text); clocks.add(clock); bytes.append('[').append(clock).append("] [CHAT][Quiet] ").append(text).append('\n');
            }
            // Exclusions are accounted, never handed to the persistence port as payload.
            bytes.append("[12:00:00] [CHAT] [PM] SCALE_PRIVATE_SENTINEL\n[12:00:00] [CHAT] [OOC] SCALE_OOC_SENTINEL\n");
            String sourceId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toString().getBytes(StandardCharsets.UTF_8)));
            var rows = new ArrayList<TranscriptLine>();
            for (int offset = 0; offset < PER_SOURCE; offset++) {
                var line = new TranscriptLine(sourceId, offset + 1, clocks.get(offset), texts.get(offset), Channel.ROLEPLAY, null); rows.add(line);
                var reference = MessageReference.of(line); all.add(reference); int scene = sourceIndex * 10 + offset / PER_SCENE, position = offset % PER_SCENE;
                if (position % 10 == 0) literal.add(reference);
                if ((scene % 900) * 2 + position % 2 == 0) { actor.add(reference); backfilled.add(reference); }
                if (position == 99) backfilled.add(reference);
            }
            sourceList.add(new Source(new ImportSource(sourceId, "synthetic-scale-" + sourceIndex + ".log", "plain", PER_SOURCE + 2), List.copyOf(rows)));
        }
        sources = List.copyOf(sourceList); all.sort(ORDER); literal.sort(ORDER); actor.sort(ORDER); backfilled.sort(ORDER);
        allReferences = List.copyOf(all); literalReferences = List.copyOf(literal); firstActorReferences = List.copyOf(actor); backfilledReferences = List.copyOf(backfilled);
        var digest = MessageDigest.getInstance("SHA-256");
        for (var source : sources.stream().sorted(Comparator.comparing(value -> value.metadata().id())).toList()) for (var row : source.rows()) digest(digest, row);
        payloadDigest = HexFormat.of().formatHex(digest.digest());
    }

    static String id(int index) { return new UUID(0x5343414c45000000L, index + 1L).toString(); }
    static String name(int index) { return "Synthetic Scale Actor" + letters(index); }
    static String sharedAlias(int pair) { return "SCALE_SHARED_ALIAS_" + letters(pair); }
    static String sceneToken(int scene) { return "SCALE_SCENE_" + String.format(java.util.Locale.ROOT, "%04d", scene); }
    static CharacterIdentity identity(int index) {
        return new CharacterIdentity(id(index), name(index), "Synthetic scale identity", index < ACTIVE_ACTORS ? Set.of() : Set.of(sharedAlias((index - ACTIVE_ACTORS) / 2)), "");
    }
    private static String letters(int index) {
        char[] value = new char[4]; for (int position = 3; position >= 0; position--) { value[position] = (char) ('A' + index % 26); index /= 26; }
        return new String(value);
    }
    MessageReference reference(int source, int line) { return new MessageReference(sources.get(source).metadata().id(), line); }
    List<MessageReference> sceneReferences(int scene) {
        int first = scene % 10 * PER_SCENE + 1; String source = sources.get(scene / 10).metadata().id();
        return java.util.stream.LongStream.range(first, first + PER_SCENE).mapToObj(line -> new MessageReference(source, line)).toList();
    }
    Set<MessageReference> favorites() {
        return java.util.stream.IntStream.range(0, 20).mapToObj(source -> reference(source, 1)).collect(java.util.stream.Collectors.toSet());
    }
    static void digest(MessageDigest digest, TranscriptLine row) {
        for (String field : new String[] {row.sourceId(), Long.toString(row.sourceLine()), row.clock(), row.text(), row.channel().name(), row.observedSpeaker()}) {
            if (field == null) digest.update(ByteBuffer.allocate(4).putInt(-1).array());
            else { byte[] bytes = field.getBytes(StandardCharsets.UTF_8); digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes); }
        }
    }
}
