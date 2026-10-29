package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import dev.kat.lotcjournal.parsing.DialogueTextNormalizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static dev.kat.lotcjournal.storage.ArchiveScaleFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in correctness measurement over the real paired repository/indexing paths, not a microbenchmark. */
@Tag("scale")
class ArchiveScaleTest {
    @TempDir Path directory;

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SAME_THREAD)
    void fixedArchiveWorkloadPreservesExactObservationsScopesAndInterpretationsWithinDeclaredCeilings() throws Throwable {
        long seed = Long.parseLong(System.getProperty("lotc.scale.seed", "20261028"));
        var report = new ScaleReport(Path.of(System.getProperty("lotc.scale.reportDir", "build/reports/scale")), seed);
        try {
            assertEquals(ROWS, Integer.parseInt(System.getProperty("lotc.scale.rows", Integer.toString(ROWS))), "Scale acceptance requires the complete fixed 100000-row workload");
            Path archive = directory.resolve("archive.sqlite"), characters = directory.resolve("characters.sqlite");
            var store = new SqliteJournalStore(archive, characters, new DialogueTextNormalizer());
            var identities = new SqliteIdentityRepository(archive, characters); var scenes = new SqliteSceneRepository(archive, characters);
            var fixture = report.measure("setup-presaved-characters", 120, () -> {
                store.initialize(); report.environment.put("sqliteVersion", text(archive, "SELECT sqlite_version()"));
                for (int index = 0; index < IDENTITIES; index++) identities.saveIdentity(identity(index));
                assertEquals(IDENTITIES, count(characters, "SELECT count(*) FROM character_identities"));
                assertEquals(0, count(archive, "SELECT count(*) FROM transcript_lines"));
                var value = new ArchiveScaleFixture(); assertEquals(ROWS, value.allReferences.size());
                assertEquals(10_000, value.literalReferences.size()); assertEquals(100, value.firstActorReferences.size()); assertEquals(1_100, value.backfilledReferences.size());
                report.counts.put("expectedPayloadSha256", value.payloadDigest); return value;
            });
            report.run("ingest-production-append-and-index", 300, () -> {
                var order = new ArrayList<>(fixture.sources); Collections.shuffle(order, new Random(seed));
                for (var source : order) {
                    checkInterrupted(); var start = store.beginSource(source.metadata()); assertEquals(0, start.processedLines());
                    // Both privacy wrappers fail before cursor/payload writes, even with UNKNOWN input.
                    if (source == order.getFirst()) {
                        for (String rejected : List.of("[PM] SCALE_PRIVATE_SENTINEL", "[OOC] SCALE_OOC_SENTINEL")) {
                            var unsafe = new TranscriptLine(source.metadata().id(), 1, "10:00:00", rejected, Channel.UNKNOWN, null);
                            assertThrows(IllegalArgumentException.class, () -> store.appendBatch(source.metadata().id(), 0, 1, List.of(unsafe), 0, 0, false));
                            assertEquals(start, store.progress(source.metadata().id()));
                        }
                    }
                    for (int offset = 0; offset < PER_SOURCE; offset += 250) {
                        checkInterrupted(); int end = offset + 250; boolean complete = end == PER_SOURCE;
                        store.appendBatch(source.metadata().id(), offset, complete ? PER_SOURCE + 2 : end, source.rows().subList(offset, end), complete ? 2 : 0, 0, complete);
                    }
                }
                assertArchiveCounts(store, archive, fixture); assertEquals(ROWS, count(characters, "SELECT count(*) FROM character_evidence"));
                assertEquals(IDENTITIES, count(characters, "SELECT count(*) FROM character_identities")); assertEquals(0, count(characters, "SELECT count(*) FROM memory_assignments"));
                assertEquals(fixture.sources.getFirst().rows().get(2).text(), fixture.sources.getFirst().rows().get(4).text(), "Fixture must contain repeated wording at distinct coordinates");
            });
            report.run("explicit-persona-and-favorite-setup", 60, () -> {
                identities.createPersona(new Persona(PERSONA_FIRST, id(0), "Synthetic first scope"));
                identities.createPersona(new Persona(PERSONA_SECOND, id(2), "Synthetic second scope"));
                for (int scene = 0; scene < 2; scene++) for (int position = 0; position < PER_SCENE; position++) {
                    identities.assign(new MemoryAssignment(fixture.sceneReferences(scene).get(position), scene == 0 ? PERSONA_FIRST : PERSONA_SECOND,
                        id(scene * 2 + position % 2), AttributionRole.SPEAKER));
                }
                for (var reference : fixture.favorites()) store.setFavorite(reference, true);
                assertEquals(200, count(characters, "SELECT count(*) FROM memory_assignments")); assertEquals(20, count(archive, "SELECT count(*) FROM message_favorites"));
            });
            report.run("scene-refresh-initial", 180, () -> {
                scenes.refreshSuggestions(); assertEquals(SCENES, scenes.scenes(null, false, "", 0, 100).total());
                assertEquals(SCENES, count(characters, "SELECT count(*) FROM scenes WHERE active=1 AND origin='AUTO' AND qualified=1 AND state='CONFIRMED'"));
                assertEquals(ROWS, count(characters, "SELECT count(*) FROM scene_members"));
            });
            String sceneState = sceneDigest(characters);
            report.run("scene-refresh-repeat", 180, () -> {
                assertEquals(0, scenes.refreshSuggestions()); assertEquals(sceneState, sceneDigest(characters));
                assertEquals(SCENES, scenes.scenes(null, true, "", 0, 100).total());
            });
            report.run("completed-source-repeat", 60, () -> {
                for (var source : fixture.sources) {
                    var progress = store.beginSource(source.metadata()); assertEquals(PER_SOURCE + 2, progress.processedLines());
                    assertEquals(progress, store.appendBatch(source.metadata().id(), PER_SOURCE + 2, PER_SOURCE + 2, List.of(), 0, 0, true));
                }
                assertArchiveCounts(store, archive, fixture); assertEquals(sceneState, sceneDigest(characters));
            });
            report.run("transcript-first-deep-and-literal-pages", 60, () -> {
                assertPage(store.transcript(null, COMMON, 0, 100), fixture.allReferences, 0, 100, fixture.favorites());
                assertPage(store.transcript(null, COMMON, 99_900, 100), fixture.allReferences, 99_900, 100, fixture.favorites());
                assertPage(store.transcript(null, LITERAL, 0, 100), fixture.literalReferences, 0, 100, fixture.favorites());
                assertPage(store.transcript(null, LITERAL, 9_900, 100), fixture.literalReferences, 9_900, 100, fixture.favorites());
                assertEquals(0, store.transcript(null, "SCALE_LITERAL_NOT_PRESENT_%_\\'", 0, 100).total());
            });
            var chosen = report.measure("scene-list-search-and-paging", 60, () -> {
                var ids = new java.util.HashSet<String>();
                for (int offset = 0; offset < SCENES; offset += 100) {
                    var page = scenes.scenes(null, false, "", offset, 100); assertEquals(SCENES, page.total()); assertEquals(100, page.entries().size());
                    for (var entry : page.entries()) { assertTrue(ids.add(entry.id())); assertEquals(PER_SCENE, entry.messageCount()); assertEquals(SceneState.CONFIRMED, entry.state()); assertEquals(SceneOrigin.AUTO, entry.origin()); }
                }
                assertEquals(SCENES, ids.size()); assertEquals(0, scenes.scenes(null, false, true, "", 0, 100).total());
                var targeted = scenes.scenes(null, false, sceneToken(321), 0, 100); assertEquals(1, targeted.total());
                assertEquals(fixture.sceneReferences(321), references(scenes.transcript(targeted.entries().getFirst().id(), null, "", 0, 100)));
                var first = scenes.scenes(null, false, sceneToken(0), 0, 100); assertEquals(1, first.total()); return first.entries().getFirst().id();
            });
            report.run("scene-detail-and-exact-provenance", 60, () -> {
                var detail = scenes.scene(chosen, null); assertEquals(100, detail.statistics().messages()); assertEquals(80, detail.statistics().speechObservations());
                assertEquals(20, detail.statistics().actionObservations()); assertEquals(0, detail.statistics().mentionObservations());
                assertEquals(99L, detail.statistics().elapsedSeconds()); assertEquals(99L, detail.statistics().estimatedActiveSeconds());
                assertEquals(Set.of(id(0), id(1)), detail.participants().stream().map(SceneParticipant::identityId).collect(java.util.stream.Collectors.toSet()));
                assertTrue(detail.assessment().highConfidence()); assertEquals(fixture.sceneReferences(0), references(scenes.transcript(chosen, null, "", 0, 100)));
            });
            report.run("identity-review-queue", 60, () -> assertQueue(identities));
            report.run("related-dialogue-before-backfill", 60, () -> {
                assertPage(identities.characterTranscript(id(0), "", null, null, false, 0, 100), fixture.firstActorReferences, 0, 100, fixture.favorites());
                assertEquals(20, identities.characterTranscript(id(0), LITERAL, Channel.ROLEPLAY, null, false, 0, 100).total());
                assertEquals(1, identities.characterTranscript(id(0), "", null, null, true, 0, 100).total());
            });
            report.run("persona-scoped-reads", 60, () -> assertPersona(identities, scenes, fixture, chosen));
            report.run("alias-save-full-retrospective-reindex", 300, () -> {
                var expected = new CharacterIdentity(id(0), name(0), "Synthetic alias backfill", Set.of(RETRO_ALIAS), "");
                identities.saveIdentity(expected); assertEquals(List.of(expected), identities.identities(name(0)), "Alias and metadata writes must actually persist");
                assertEquals(ROWS + SCENES, count(characters, "SELECT count(*) FROM character_evidence"));
                assertPage(identities.characterTranscript(id(0), "", null, null, false, 1_000, 100), fixture.backfilledReferences, 1_000, 100, fixture.favorites());
                assertEquals(1_000, identities.characterTranscript(id(0), RETRO_ALIAS, null, CharacterEvidenceKind.MENTION, false, 0, 100).total());
                assertEquals(fixture.payloadDigest, payloadDigest(archive)); assertEquals(200, count(characters, "SELECT count(*) FROM memory_assignments"));
                assertPersona(identities, scenes, fixture, chosen);
            });
            report.run("notes-only-save-after-backfill", 300, () -> {
                var expected = new CharacterIdentity(id(0), name(0), "Changed synthetic note only", Set.of(RETRO_ALIAS), "");
                identities.saveIdentity(expected); assertEquals(List.of(expected), identities.identities(name(0)), "A notes-only save cannot pass by silently dropping the metadata change");
                assertEquals(ROWS + SCENES, count(characters, "SELECT count(*) FROM character_evidence")); assertEquals(200, count(characters, "SELECT count(*) FROM memory_assignments"));
                assertEquals(fixture.payloadDigest, payloadDigest(archive)); assertEquals(sceneState, sceneDigest(characters)); assertQueue(identities);
            });
            var backup = report.measure("paired-backup", 60, () -> {
                Path result = store.createBackup(directory.resolve("backups/scale.sqlite"));
                assertTrue(Files.isRegularFile(result)); assertTrue(Files.isRegularFile(result.resolveSibling(result.getFileName() + ".characters.sqlite")));
                assertTrue(Files.isRegularFile(result.resolveSibling(result.getFileName() + ".pair.json")));
                report.counts.put("backupArchiveBytes", Files.size(result)); report.counts.put("backupCharactersBytes", Files.size(result.resolveSibling(result.getFileName() + ".characters.sqlite"))); return result;
            });
            report.run("original-and-backup-reopen", 180, () -> {
                var reopened = new SqliteJournalStore(archive, characters, new DialogueTextNormalizer()); reopened.initialize(); assertArchiveCounts(reopened, archive, fixture);
                var restored = new SqliteJournalStore(backup, new DialogueTextNormalizer()); restored.initialize(); assertArchiveCounts(restored, backup, fixture);
                var backupIdentities = new SqliteIdentityRepository(backup); var backupScenes = new SqliteSceneRepository(backup);
                assertEquals(SCENES, backupScenes.scenes(null, false, "", 0, 100).total()); assertEquals(sceneState, sceneDigest(backup.resolveSibling(backup.getFileName() + ".characters.sqlite")));
                assertQueue(backupIdentities); assertPersona(backupIdentities, backupScenes, fixture, chosen);
                assertEquals(ROWS + SCENES, count(backup.resolveSibling(backup.getFileName() + ".characters.sqlite"), "SELECT count(*) FROM character_evidence"));
                assertEquals(1_100, backupIdentities.characterTranscript(id(0), "", null, null, false, 0, 100).total());
            });
            report.counts.putAll(Map.of("historicalRows", ROWS, "sourceCount", SOURCES, "characters", IDENTITIES, "qualifyingScenes", SCENES,
                "queuePairs", CANDIDATE_PAIRS, "personaAssignments", 200, "favorites", 20, "evidenceAfterBackfill", ROWS + SCENES));
            report.finish(true);
        } catch (Throwable failure) {
            try { report.failure(failure); } catch (Exception reportFailure) { failure.addSuppressed(reportFailure); }
            throw failure;
        }
    }

    private static void checkInterrupted() throws InterruptedException { if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Scale suite deadline reached"); }
    private static List<MessageReference> references(TranscriptPage page) { return page.entries().stream().map(MessageReference::of).toList(); }
    private static void assertPage(TranscriptPage page, List<MessageReference> expected, int offset, int limit, Set<MessageReference> stars) {
        assertEquals(expected.size(), page.total()); assertEquals(expected.subList(offset, Math.min(expected.size(), offset + limit)), references(page));
        assertEquals(page.entries().stream().map(MessageReference::of).filter(stars::contains).collect(java.util.stream.Collectors.toSet()), page.favorites());
    }
    private static long count(Path path, String sql) throws Exception { return Long.parseLong(text(path, sql)); }
    private static String text(Path path, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getString(1);
        }
    }
    private static String payloadDigest(Path archive) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + archive); var query = connection.createStatement(); var rows = query.executeQuery("SELECT * FROM transcript_lines ORDER BY source_id,source_line")) {
            while (rows.next()) ArchiveScaleFixture.digest(digest, new TranscriptLine(rows.getString("source_id"), rows.getLong("source_line"), rows.getString("clock"), rows.getString("text"), Channel.valueOf(rows.getString("channel")), rows.getString("observed_speaker")));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String sceneDigest(Path characters) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + characters); var query = connection.createStatement();
             var rows = query.executeQuery("SELECT s.id,s.title,s.location,s.state,s.active,s.revision,s.participants_reviewed,s.origin,s.qualified,m.position,m.source_id,m.source_line FROM scenes s JOIN scene_members m ON m.scene_id=s.id ORDER BY s.id,m.position")) {
            while (rows.next()) for (int column = 1; column <= 12; column++) { digest.update(rows.getString(column).getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0); }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void assertArchiveCounts(SqliteJournalStore store, Path archive, ArchiveScaleFixture fixture) throws Exception {
        assertEquals(SOURCES, store.sources().size()); assertEquals(ROWS, count(archive, "SELECT count(*) FROM transcript_lines"));
        assertEquals(ROWS, count(archive, "SELECT count(*) FROM transcript_lines WHERE channel='ROLEPLAY' AND observed_speaker IS NULL"));
        assertEquals(0, count(archive, "SELECT count(*) FROM transcript_lines WHERE text LIKE '%SCALE_PRIVATE_SENTINEL%' OR text LIKE '%SCALE_OOC_SENTINEL%'"));
        assertEquals(0, count(archive, "SELECT count(*) FROM pragma_table_info('transcript_lines') WHERE name='raw'"));
        assertEquals(fixture.payloadDigest, payloadDigest(archive));
        for (var source : fixture.sources) assertEquals(new ImportProgress(source.metadata().id(), PER_SOURCE + 2, PER_SOURCE, 2, 0, true), store.progress(source.metadata().id()));
    }
    private static void assertQueue(SqliteIdentityRepository identities) throws Exception {
        var data = identities.reviewData(); assertEquals(IDENTITIES, data.identities().size()); var queue = IdentityReviewQueue.pending(data, 100);
        var expected = new java.util.HashSet<Set<String>>();
        for (int pair = 0; pair < CANDIDATE_PAIRS; pair++) expected.add(Set.of(id(ACTIVE_ACTORS + pair * 2), id(ACTIVE_ACTORS + pair * 2 + 1)));
        assertEquals(CANDIDATE_PAIRS, queue.pairs().size()); assertFalse(queue.hasMore());
        assertEquals(expected, queue.pairs().stream().map(pair -> Set.of(pair.firstIdentity(), pair.secondIdentity())).collect(java.util.stream.Collectors.toSet()));
    }
    private static void assertPersona(SqliteIdentityRepository identities, SqliteSceneRepository scenes, ArchiveScaleFixture fixture, String firstScene) throws Exception {
        assertPage(identities.memoryTranscript(PERSONA_FIRST, "", null, false, 0, 100), fixture.sceneReferences(0), 0, 100, fixture.favorites());
        assertPage(identities.memoryTranscript(PERSONA_SECOND, "", null, false, 0, 100), fixture.sceneReferences(1), 0, 100, fixture.favorites());
        assertEquals(10, identities.memoryTranscript(PERSONA_FIRST, LITERAL, Channel.ROLEPLAY, false, 0, 100).total());
        assertEquals(0, identities.memoryTranscript(PERSONA_FIRST, sceneToken(1), null, false, 0, 100).total());
        assertEquals(1, scenes.scenes(PERSONA_FIRST, false, "", 0, 100).total()); assertEquals(1, scenes.scenes(PERSONA_SECOND, false, "", 0, 100).total());
        var detail = scenes.scene(firstScene, PERSONA_FIRST); assertEquals("Assigned scene", detail.summary().title()); assertEquals("", detail.summary().location());
        assertEquals(SceneOrigin.MANUAL, detail.summary().origin()); assertEquals(SceneAssessment.unassessed(), detail.assessment());
        assertEquals(100, detail.statistics().messages()); assertEquals(80, detail.statistics().speechObservations()); assertEquals(20, detail.statistics().actionObservations()); assertEquals(0, detail.statistics().mentionObservations());
        assertEquals(Set.of(id(0), id(1)), detail.participants().stream().map(SceneParticipant::identityId).collect(java.util.stream.Collectors.toSet()));
    }

    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }
    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }
    private static final class ScaleReport {
        final Path path; final Map<String,Object> environment = new LinkedHashMap<>(), counts = new LinkedHashMap<>();
        final Map<String,Object> document = new LinkedHashMap<>(); final List<Map<String,Object>> stages = new ArrayList<>(); final List<String> failures = new ArrayList<>();
        ScaleReport(Path directory, long seed) throws Exception {
            path = directory.resolve("archive-scale.json"); environment.putAll(Map.of("javaVersion", System.getProperty("java.version"), "javaVendor", System.getProperty("java.vendor"),
                "vmName", System.getProperty("java.vm.name"), "osName", System.getProperty("os.name"), "osArch", System.getProperty("os.arch"),
                "processors", Runtime.getRuntime().availableProcessors(), "maxHeapBytes", Runtime.getRuntime().maxMemory()));
            document.putAll(Map.of("schemaVersion", 1, "startedAt", Instant.now().toString(), "complete", false, "passed", false,
                "workload", Map.of("rows", ROWS, "sources", SOURCES, "characters", IDENTITIES, "expectedScenes", SCENES, "expectedQueuePairs", CANDIDATE_PAIRS, "seed", seed)));
            document.put("environment", environment); document.put("counts", counts); document.put("stages", stages); document.put("failures", failures);
            document.put("limitations", List.of("All character identities are pre-saved; fresh-name discovery throughput is not measured", "Historical storage append/index throughput; parser and decompressor throughput are not measured",
                "Single-writer workload; live append contention during refresh or reindex is not measured", "Synthetic storage verification; Minecraft UI and real modpack latency are not measured"));
            document.put("deadlines", Map.of("suiteSeconds", 900, "stageBudgets", "Elapsed ceilings checked after each operation; JUnit interruption and enclosing Gradle timeout bound a hung run")); flush();
        }
        <T> T measure(String name, long budgetSeconds, CheckedSupplier<T> operation) throws Exception {
            var stage = new LinkedHashMap<String,Object>(); stage.put("name", name); stage.put("status", "RUNNING"); stage.put("budgetMillis", budgetSeconds * 1_000); stages.add(stage); flush();
            long start = System.nanoTime();
            try {
                T result = operation.get(); long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); stage.put("durationMillis", elapsed);
                assertTrue(elapsed <= budgetSeconds * 1_000, name + " exceeded its predeclared " + budgetSeconds + "s ceiling"); stage.put("status", "PASSED"); return result;
            } catch (Exception | Error failure) { stage.put("status", "FAILED"); stage.put("errorType", failure.getClass().getSimpleName()); throw failure; }
            finally { stage.put("durationMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)); flush(); }
        }
        void run(String name, long budgetSeconds, CheckedRunnable operation) throws Exception { measure(name, budgetSeconds, () -> { operation.run(); return null; }); }
        void failure(Throwable failure) throws Exception { failures.add(failure.getClass().getSimpleName()); finish(false); }
        void finish(boolean passed) throws Exception { document.put("complete", true); document.put("passed", passed); document.put("finishedAt", Instant.now().toString()); flush(); }
        void flush() throws Exception {
            Files.createDirectories(path.toAbsolutePath().getParent()); Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, json(document), StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        }
        private static String json(Object value) {
            if (value == null) return "null";
            if (value instanceof Number || value instanceof Boolean) return value.toString();
            if (value instanceof Map<?,?> map) return map.entrySet().stream().map(entry -> json(entry.getKey().toString()) + ":" + json(entry.getValue())).collect(java.util.stream.Collectors.joining(",", "{", "}"));
            if (value instanceof Iterable<?> items) { var values = new ArrayList<String>(); items.forEach(item -> values.add(json(item))); return String.join(",", values).transform(content -> "[" + content + "]"); }
            var result = new StringBuilder("\"");
            for (char character : value.toString().toCharArray()) switch (character) {
                case '"' -> result.append("\\\""); case '\\' -> result.append("\\\\"); case '\n' -> result.append("\\n"); case '\r' -> result.append("\\r"); case '\t' -> result.append("\\t");
                default -> { if (character < 0x20) result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character)); else result.append(character); }
            }
            return result.append('"').toString();
        }
    }
}
