package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import dev.kat.lotcjournal.parsing.DialogueTextNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class PairedDatabaseFixtures {
    private static final List<String> NATIVE_EIGHT_IDENTITY_TABLES = List.of("character_identities", "character_aliases", "personas", "identity_state", "memory_assignments", "identity_merges", "identity_decisions");
    static final String SOURCE = "a".repeat(64), LIVE = "b".repeat(64), POLICY = "0".repeat(64);
    static final String FIRST = "10000000-0000-0000-0000-000000000001", SECOND = "10000000-0000-0000-0000-000000000002";
    static final String PERSONA_FIRST = "20000000-0000-0000-0000-000000000001", PERSONA_SECOND = "20000000-0000-0000-0000-000000000002";
    static final String BATCH = "30000000-0000-0000-0000-000000000001";
    private PairedDatabaseFixtures() {}
    static MessageReference ref(long coordinate) { return new MessageReference(SOURCE, coordinate); }
    static SqliteJournalStore open(Path archive, Path characters) throws Exception {
        var store = new SqliteJournalStore(archive, characters, new DialogueTextNormalizer()); store.initialize(); return store;
    }
    static SqliteJournalStore seed(Path archive, Path characters) throws Exception {
        var store = open(archive, characters); store.save(new JournalSettings(true));
        store.saveWatchSettings(new WatchSettings(archive.getParent().toString(), true, Set.of("Synthetic Reviewed")));
        store.recordWatchAttempt(archive.getParent().resolve("synthetic.log").toString(), "f".repeat(64), "FAILED");
        store.beginSource(new ImportSource(SOURCE, "synthetic.log", "plain", 3, POLICY));
        store.appendBatch(SOURCE, 0, 1, List.of(new TranscriptLine(SOURCE, 1, "23:59:59", "An older synthetic observation.", Channel.UNKNOWN, null)), 0, 0, false);
        store.createBatch(new BatchPlan(BATCH, archive.getParent().toString(), POLICY, Instant.parse("2026-10-25T12:00:00Z").toString(), Set.of(), List.of(
            new BatchFile(BATCH, 0, archive.getParent().resolve("synthetic.log").toString(), "synthetic.log", SOURCE, "PENDING", null, 0, 0, 0, 0, 0))));
        store.finishBatch(BATCH, "RUNNING"); store.updateFile(BATCH, 0, "RUNNING", SOURCE, null);
        store.appendBatchOwned(BATCH, 0, SOURCE, 1, 3,
            List.of(new TranscriptLine(SOURCE, 2, "00:00:01", "A synthetic owned observation.", Channel.ROLEPLAY, null)), 1, 0, true);
        store.updateFile(BATCH, 0, "COMPLETE", SOURCE, null); store.finishBatch(BATCH, "COMPLETE");
        var live = new LiveSession(LIVE, Instant.parse("2026-10-25T12:00:00Z"), "UTC", POLICY);
        store.beginLiveSession(live); store.appendLiveEvent(live,
            new LiveEvent(LIVE, 1, live.startedAt(), 0, LiveEvent.Kind.GAME, "A synthetic live observation.", Channel.ROLEPLAY, null));
        store.closeLiveSession(LIVE); store.saveLivePaused(true);
        var identities = new SqliteIdentityRepository(archive, characters);
        identities.saveIdentity(new CharacterIdentity(FIRST, "Synthetic First", "First manual note", Set.of("Shared Alias", "Literal ;\\ Ω"), "SyntheticAccount"));
        identities.saveIdentity(new CharacterIdentity(SECOND, "Synthetic Second", "Second manual note", Set.of("Shared Alias"), ""));
        identities.createPersona(new Persona(PERSONA_FIRST, FIRST, "Synthetic first persona"));
        identities.createPersona(new Persona(PERSONA_SECOND, SECOND, "Synthetic second persona"));
        identities.selectPersona(PERSONA_SECOND);
        identities.assign(new MemoryAssignment(ref(1), PERSONA_FIRST, FIRST, AttributionRole.REFERENCED));
        identities.assign(new MemoryAssignment(ref(2), PERSONA_SECOND, SECOND, AttributionRole.SPEAKER));
        identities.mergeIdentity(FIRST, SECOND);
        store.setFavorite(ref(1), true); store.setFavorite(ref(2), true);
        return store;
    }
    static long scalar(Path path, String query) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path); var s = c.createStatement(); var r = s.executeQuery(query)) {
            if (!r.next()) throw new AssertionError("Missing synthetic query result"); return r.getLong(1);
        }
    }
    static String text(Path path, String query) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path); var s = c.createStatement(); var r = s.executeQuery(query)) {
            if (!r.next()) throw new AssertionError("Missing synthetic query result"); return r.getString(1);
        }
    }
    static Set<String> tables(Path path) throws Exception {
        var tables = new java.util.HashSet<String>();
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path); var s = c.createStatement(); var r = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            while (r.next()) tables.add(r.getString(1));
        }
        return Set.copyOf(tables);
    }
    /** Reconstruct an exact earlier character member, rather than changing only its version number. */
    static void characterVersion(Path characters, int version) throws Exception {
        if (version < 1 || version > 2) throw new IllegalArgumentException("Expected an older synthetic character schema");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + characters); var s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS message_tag_choices"); s.execute("DROP TABLE IF EXISTS message_tag_reviews");
            if (version == 1) s.execute("DROP TABLE IF EXISTS identity_separations");
            s.execute("PRAGMA user_version=" + version);
        }
    }
    /** Exact native v8 layout, including the original assignment FK to the same-file archive. */
    static void convertToNativeEight(Path archive, Path characters) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + archive); var s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=OFF");
            try (var attach = c.prepareStatement("ATTACH DATABASE ? AS legacy_characters")) { attach.setString(1, characters.toString()); attach.execute(); }
            c.setAutoCommit(false);
            try {
                for (String table : NATIVE_EIGHT_IDENTITY_TABLES) {
                    s.execute(IdentitySchema.LEGACY_TABLES.get(table));
                    s.executeUpdate("INSERT INTO main." + table + " SELECT * FROM legacy_characters." + table);
                }
                s.execute("DROP TABLE main.storage_pair"); s.execute("PRAGMA main.user_version=8"); c.commit();
            } catch (Exception failure) { c.rollback(); throw failure; }
            c.setAutoCommit(true);
            try (var rows = s.executeQuery("PRAGMA main.foreign_key_check")) { if (rows.next()) throw new AssertionError("Invalid native-v8 fixture"); }
            s.execute("DETACH DATABASE legacy_characters");
        }
        Files.delete(characters);
    }
}
