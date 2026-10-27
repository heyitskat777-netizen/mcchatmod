package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.BackupService;
import dev.kat.lotcjournal.domain.BatchFile;
import dev.kat.lotcjournal.domain.BatchPlan;
import dev.kat.lotcjournal.domain.BatchRepository;
import dev.kat.lotcjournal.domain.BatchSummary;
import dev.kat.lotcjournal.domain.CaptureRepository;
import dev.kat.lotcjournal.domain.Channel;
import dev.kat.lotcjournal.domain.MessageReference;
import dev.kat.lotcjournal.domain.LiveRepository;
import dev.kat.lotcjournal.domain.LiveSession;
import dev.kat.lotcjournal.domain.LiveEvent;
import dev.kat.lotcjournal.domain.DialogueNormalizer;
import dev.kat.lotcjournal.domain.DialogueCleanup;
import dev.kat.lotcjournal.domain.ImportProgress;
import dev.kat.lotcjournal.domain.ImportSource;
import dev.kat.lotcjournal.domain.JournalSettings;
import dev.kat.lotcjournal.domain.SettingsRepository;
import dev.kat.lotcjournal.domain.TranscriptLine;
import dev.kat.lotcjournal.domain.TranscriptPage;
import dev.kat.lotcjournal.domain.WatchSettings;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.TreeSet;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** SQLite adapter. Callers use short-lived connections only on journal/import worker threads. */
public final class SqliteJournalStore implements SettingsRepository, BackupService, CaptureRepository, BatchRepository, LiveRepository {
    private static final int SCHEMA_VERSION = 9;
    private static final String SETTINGS_SQL = "CREATE TABLE journal_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)";
    private static final String SOURCES_SQL = """
        CREATE TABLE capture_sources (
          id TEXT PRIMARY KEY NOT NULL CHECK(length(id)=64 AND id NOT GLOB '*[^0-9a-f]*'),
          label TEXT NOT NULL CHECK(length(label)>0 AND length(label)<=255),
          format TEXT NOT NULL CHECK(format IN ('plain','gzip')),
          policy_fingerprint TEXT NOT NULL CHECK(length(policy_fingerprint)=64 AND policy_fingerprint NOT GLOB '*[^0-9a-f]*'),
          total_lines INTEGER NOT NULL CHECK(typeof(total_lines)='integer' AND total_lines>=0),
          processed_lines INTEGER NOT NULL DEFAULT 0 CHECK(typeof(processed_lines)='integer' AND processed_lines>=0 AND processed_lines<=total_lines),
          imported_lines INTEGER NOT NULL DEFAULT 0 CHECK(typeof(imported_lines)='integer' AND imported_lines>=0),
          excluded_lines INTEGER NOT NULL DEFAULT 0 CHECK(typeof(excluded_lines)='integer' AND excluded_lines>=0),
          ignored_lines INTEGER NOT NULL DEFAULT 0 CHECK(typeof(ignored_lines)='integer' AND ignored_lines>=0),
          complete INTEGER NOT NULL DEFAULT 0 CHECK(complete IN (0,1)),
          CHECK(imported_lines+excluded_lines+ignored_lines=processed_lines),
          CHECK(complete=0 OR processed_lines=total_lines)
        )
        """;
    private static final String BATCHES_SQL = """
        CREATE TABLE import_batches (
          id TEXT PRIMARY KEY NOT NULL,
          root TEXT NOT NULL,
          policy_fingerprint TEXT NOT NULL,
          reviewed_names TEXT NOT NULL,
          status TEXT NOT NULL CHECK(status IN ('PENDING','RUNNING','COMPLETE','PARTIAL','FAILED','CANCELLED','INTERRUPTED','UNDONE')),
          created_at TEXT NOT NULL,
          updated_at TEXT NOT NULL
        )
        """;
    private static final String BATCH_FILES_SQL = """
        CREATE TABLE batch_files (
          batch_id TEXT NOT NULL REFERENCES import_batches(id),
          ordinal INTEGER NOT NULL CHECK(typeof(ordinal)='integer' AND ordinal>=0 AND ordinal<10000),
          path TEXT NOT NULL,
          label TEXT NOT NULL,
          source_id TEXT,
          status TEXT NOT NULL CHECK(status IN ('PENDING','RUNNING','COMPLETE','DUPLICATE','FAILED','SKIPPED','CANCELLED','INTERRUPTED','UNDONE')),
          error_code TEXT,
          before_line INTEGER NOT NULL DEFAULT 0 CHECK(typeof(before_line)='integer' AND before_line>=0),
          after_line INTEGER NOT NULL DEFAULT 0 CHECK(typeof(after_line)='integer' AND after_line>=before_line),
          added INTEGER NOT NULL DEFAULT 0 CHECK(typeof(added)='integer' AND added>=0),
          excluded INTEGER NOT NULL DEFAULT 0 CHECK(typeof(excluded)='integer' AND excluded>=0),
          ignored INTEGER NOT NULL DEFAULT 0 CHECK(typeof(ignored)='integer' AND ignored>=0),
          CHECK(added+excluded+ignored=after_line-before_line),
          PRIMARY KEY(batch_id,ordinal)
        )
        """;
    private static final String OWNERS_SQL = """
        CREATE TABLE observation_owners (
          source_id TEXT NOT NULL,
          source_line INTEGER NOT NULL,
          batch_id TEXT NOT NULL,
          file_ordinal INTEGER NOT NULL,
          PRIMARY KEY(source_id,source_line),
          FOREIGN KEY(source_id,source_line) REFERENCES transcript_lines(source_id,source_line) ON DELETE CASCADE,
          FOREIGN KEY(batch_id,file_ordinal) REFERENCES batch_files(batch_id,ordinal)
        )
        """;
    private static final String REMOVED_SQL = """
        CREATE TABLE removed_observations (
          source_id TEXT NOT NULL REFERENCES capture_sources(id),
          source_line INTEGER NOT NULL CHECK(typeof(source_line)='integer' AND source_line>0),
          batch_id TEXT NOT NULL,
          file_ordinal INTEGER NOT NULL,
          PRIMARY KEY(source_id,source_line),
          FOREIGN KEY(batch_id,file_ordinal) REFERENCES batch_files(batch_id,ordinal)
        )
        """;
    private static final String WATCH_SQL = """
        CREATE TABLE watch_settings (
          id INTEGER PRIMARY KEY CHECK(id=1),
          folder TEXT,
          enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
          reviewed_names TEXT NOT NULL,
          CHECK(enabled=0 OR folder IS NOT NULL)
        )
        """;
    private static final String WATCH_ATTEMPTS_SQL = """
        CREATE TABLE watch_attempts (
          path TEXT PRIMARY KEY NOT NULL,
          fingerprint TEXT NOT NULL CHECK(length(fingerprint)=64 AND fingerprint NOT GLOB '*[^0-9a-f]*'),
          status TEXT NOT NULL CHECK(status IN ('ATTEMPTED','COMPLETE','FAILED','CANCELLED','INTERRUPTED')),
          attempted_at TEXT NOT NULL
        )
        """;
    private static final String LEGACY_TRANSCRIPT_SQL = """
        CREATE TABLE transcript_lines (
          source_id TEXT NOT NULL REFERENCES capture_sources(id),
          source_line INTEGER NOT NULL CHECK(typeof(source_line)='integer' AND source_line>0),
          clock TEXT,
          raw TEXT NOT NULL CHECK(length(raw)<=65536),
          text TEXT NOT NULL CHECK(length(text)<=65536),
          channel TEXT NOT NULL CHECK(channel IN ('ROLEPLAY','SYSTEM','UNKNOWN')),
          observed_speaker TEXT,
          PRIMARY KEY(source_id,source_line)
        )
        """;
    private static final String TRANSCRIPT_SQL = LEGACY_TRANSCRIPT_SQL.replace(
        "  raw TEXT NOT NULL CHECK(length(raw)<=65536),\n", "");
    private static final String FAVORITES_SQL = """
        CREATE TABLE message_favorites (
          source_id TEXT NOT NULL,
          source_line INTEGER NOT NULL,
          PRIMARY KEY(source_id,source_line),
          FOREIGN KEY(source_id,source_line) REFERENCES transcript_lines(source_id,source_line) ON DELETE CASCADE
        )
        """;
    private static final String LIVE_SESSIONS_SQL = """
        CREATE TABLE live_sessions (
          source_id TEXT PRIMARY KEY NOT NULL REFERENCES capture_sources(id),
          started_at TEXT NOT NULL,
          zone_id TEXT NOT NULL,
          closed INTEGER NOT NULL DEFAULT 0 CHECK(closed IN (0,1))
        )
        """;
    private static final String LIVE_EVENTS_SQL = """
        CREATE TABLE live_events (
          source_id TEXT NOT NULL REFERENCES live_sessions(source_id),
          sequence INTEGER NOT NULL CHECK(typeof(sequence)='integer' AND sequence>0),
          received_at TEXT NOT NULL,
          offset_seconds INTEGER NOT NULL CHECK(typeof(offset_seconds)='integer' AND offset_seconds BETWEEN -64800 AND 64800),
          kind TEXT NOT NULL CHECK(kind IN ('CHAT','GAME')),
          fingerprint TEXT NOT NULL CHECK(length(fingerprint)=64 AND fingerprint NOT GLOB '*[^0-9a-f]*'),
          PRIMARY KEY(source_id,sequence)
        )
        """;
    private final Path database, characters;
    private boolean startupRecovered;
    private final DialogueNormalizer normalizer;

    public SqliteJournalStore(Path database, DialogueNormalizer normalizer) {
        this(database,SplitJournalFiles.charactersFor(database),normalizer);
    }
    public SqliteJournalStore(Path database,Path characters,DialogueNormalizer normalizer) {
        this.database=database.toAbsolutePath().normalize(); this.characters=characters.toAbsolutePath().normalize();
        this.normalizer=Objects.requireNonNull(normalizer);
    }
    private Connection open() throws SQLException { return SplitJournalFiles.open(database,characters); }

    private void bootstrapLegacyIfNeeded() throws Exception {
        if(!database.getFileName().toString().equals("archive.sqlite")||Files.exists(database,java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if(Files.exists(characters,java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new SQLException("Archive is missing from an established or unrelated pair");
        Path legacy=database.resolveSibling("journal.sqlite");
        if(!Files.exists(legacy,java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if(!Files.isRegularFile(legacy,java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(legacy)) throw new SQLException("Invalid native migration source");
        Path staged=database.resolveSibling(database.getFileName()+".bootstrap-"+UUID.randomUUID());
        boolean published=false;
        try {
            // Read-only SQLite snapshot includes committed WAL pages without changing the
            // old native database, its IDs, or the user's source files.
            try(var old=DriverManager.getConnection("jdbc:sqlite:"+legacy.toUri()+"?mode=ro")) {
                int version=version(old,"main");
                if(version<1||version>8) throw new SQLException("Unsupported native migration source");
                validateSchema(old,version);verifyIntegrity(old);NativePairBackup.legacy(old,staged);
            }
            try(var saved=SplitJournalFiles.archive(staged)) {validateSchema(saved,version(saved,"main"));verifyIntegrity(saved);}
            Files.move(staged,database,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            SplitJournalFiles.forceDirectory(database.getParent());published=true;
        } finally {if(!published) Files.deleteIfExists(staged);}
    }
    private static int version(Connection c,String schema) throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("PRAGMA "+schema+".user_version")) {return r.getInt(1);}
    }
    private static void requireEmpty(Connection c,String schema) throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT name FROM "+schema+".sqlite_master WHERE name NOT LIKE 'sqlite_%'")) {
            if(r.next()) throw new SQLException("Refusing to adopt unrelated storage");
        }
        if(version(c,schema)!=0) throw new SQLException("Unexpected empty database version");
    }

    @Override public synchronized void initialize() throws Exception {
        Files.createDirectories(database.getParent());SplitJournalFiles.paths(database,characters);
        try(var ownership=SplitJournalFiles.initializationLock(database)) {initializeOwned();}
    }
    private void initializeOwned() throws Exception {
        Files.createDirectories(database.getParent());SplitJournalFiles.paths(database,characters);bootstrapLegacyIfNeeded();
        if(!Files.exists(database,java.nio.file.LinkOption.NOFOLLOW_LINKS)&&Files.exists(characters,java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new SQLException("Archive is missing from a native pair");
        try(Connection connection=SplitJournalFiles.archive(database);var statement=connection.createStatement()) {
            int oldVersion=version(connection,"main");
            if(oldVersion<0||oldVersion>SCHEMA_VERSION) throw new SQLException("Unsupported journal database version");
            if(oldVersion==0) requireEmpty(connection,"main");
            else if(oldVersion<9) {
                validateSchema(connection,oldVersion);verifyIntegrity(connection);
                Path backup=database.getParent().resolve("backups").resolve(database.getFileName()+"-pre-schema9-"+UUID.randomUUID()+".sqlite");
                NativePairBackup.legacy(connection,backup);
                try(var saved=SplitJournalFiles.archive(backup)) {validateSchema(saved,oldVersion);verifyIntegrity(saved);}
            } else if(!Files.isRegularFile(characters,java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new SQLException("Characters file is missing from a native pair");
            SplitJournalFiles.attach(connection,characters);
            if(oldVersion<9) requireEmpty(connection,"characters");
            else {SplitJournalFiles.validatePair(connection);validateSchema(connection,9);validateCharacters(connection);verifyIntegrity(connection);SplitJournalFiles.integrity(connection,"characters");}
            // Only validated, backed-up legacy data can switch modes. Both databases
            // must use rollback journals and FULL sync before an attached write.
            SplitJournalFiles.configure(connection);
            if(oldVersion<9) {
                connection.setAutoCommit(false);
                try {
                    if(oldVersion==0) {statement.execute(SETTINGS_SQL);statement.execute("INSERT INTO journal_settings VALUES ('compact_layout','false')");}
                    if(oldVersion<2) createCaptureSchema(connection);
                    if(oldVersion<3) createWorkflowSchema(connection);
                    if(oldVersion>=2&&oldVersion<8) migrateDialogue(connection,oldVersion);
                    if(oldVersion<6) statement.execute(FAVORITES_SQL);
                    if(oldVersion<7) {statement.execute(LIVE_SESSIONS_SQL);statement.execute(LIVE_EVENTS_SQL);}
                    if(oldVersion==8) IdentitySchema.migrate(connection); else IdentitySchema.create(connection);
                    SplitJournalFiles.createPair(connection);
                    statement.execute("PRAGMA main.user_version=9");statement.execute("PRAGMA characters.user_version=1");
                    CharacterIndex.reindex(connection);
                    validateSchema(connection,9);validateCharacters(connection);verifyIntegrity(connection);SplitJournalFiles.integrity(connection,"characters");
                    connection.commit();
                } catch(Exception failure) {connection.rollback();throw failure;}
                finally {connection.setAutoCommit(true);}
            }
            NativePairBackup.verifyForAdoption(connection,database,characters);
            validateSchema(connection,9);validateCharacters(connection);verifyIntegrity(connection);SplitJournalFiles.integrity(connection,"characters");
            if(!startupRecovered) {
                SplitJournalFiles.beginWrite(connection);
                try {
                    statement.executeUpdate("UPDATE batch_files SET status='INTERRUPTED',error_code='PROCESS_INTERRUPTED' WHERE status IN ('PENDING','RUNNING')");
                    statement.executeUpdate("UPDATE watch_attempts SET status='INTERRUPTED' WHERE status='ATTEMPTED'");
                    try(var recovery=connection.prepareStatement("UPDATE import_batches SET status='INTERRUPTED',updated_at=? WHERE status IN ('PENDING','RUNNING')")) {
                        recovery.setString(1,Instant.now().toString());recovery.executeUpdate();
                    }
                    connection.commit();startupRecovered=true;
                } catch(SQLException failure) {connection.rollback();throw failure;}
                finally {connection.setAutoCommit(true);}
            }
        }
    }

    private static void createCaptureSchema(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(SOURCES_SQL);
            statement.execute(TRANSCRIPT_SQL);
        }
    }

    private static void createWorkflowSchema(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            for (String sql : List.of(BATCHES_SQL, BATCH_FILES_SQL, OWNERS_SQL, REMOVED_SQL, WATCH_SQL, WATCH_ATTEMPTS_SQL)) statement.execute(sql);
            statement.execute("INSERT INTO watch_settings VALUES (1,NULL,0,'')");
        }
    }

    /** Keyset chunks bound memory; deleting a row cannot invalidate a live scan cursor. */
    private void migrateDialogue(Connection connection, int previousVersion) throws SQLException {
        String lastSource = ""; long lastLine = 0;
        while (true) {
            var chunk = new ArrayList<LegacyDialogue>();
            try (var query = connection.prepareStatement("""
                SELECT t.*,o.batch_id,o.file_ordinal FROM transcript_lines t
                LEFT JOIN observation_owners o ON o.source_id=t.source_id AND o.source_line=t.source_line
                WHERE t.source_id>? OR (t.source_id=? AND t.source_line>?)
                ORDER BY t.source_id,t.source_line LIMIT 100
                """)) {
                query.setString(1, lastSource); query.setString(2, lastSource); query.setLong(3, lastLine);
                try (var row = query.executeQuery()) {
                    while (row.next()) chunk.add(new LegacyDialogue(row.getString("source_id"), row.getLong("source_line"),
                        row.getString("text"), Channel.valueOf(row.getString("channel")), row.getString("batch_id"), row.getInt("file_ordinal")));
                }
            }
            if (chunk.isEmpty()) break;
            for (var line : chunk) {
                var cleaned = normalizer.clean(line.text(), line.channel());
                if (cleaned.disposition() == DialogueCleanup.Disposition.DIALOGUE) {
                    try (var update = connection.prepareStatement("UPDATE transcript_lines SET text=? WHERE source_id=? AND source_line=?")) {
                        update.setString(1, cleaned.text()); update.setString(2, line.sourceId()); update.setLong(3, line.line()); update.executeUpdate();
                    }
                } else {
                    String counter = cleaned.disposition() == DialogueCleanup.Disposition.EXCLUDED ? "excluded" : "ignored";
                    if (line.batchId() != null) {
                        try (var update = connection.prepareStatement("UPDATE batch_files SET added=added-1," + counter + "=" + counter + "+1 WHERE batch_id=? AND ordinal=? AND added>0")) {
                            update.setString(1, line.batchId()); update.setInt(2, line.ordinal());
                            if (update.executeUpdate() != 1) throw new SQLException("Invalid legacy ownership contribution");
                        }
                    }
                    try (var update = connection.prepareStatement("UPDATE capture_sources SET imported_lines=imported_lines-1," + counter + "_lines=" + counter + "_lines+1 WHERE id=? AND imported_lines>0")) {
                        update.setString(1, line.sourceId());
                        if (update.executeUpdate() != 1) throw new SQLException("Invalid legacy source contribution");
                    }
                    if (previousVersion >= 9) removeCharacterReferences(connection,line.sourceId(),line.line());
                    try (var delete = connection.prepareStatement("DELETE FROM transcript_lines WHERE source_id=? AND source_line=?")) {
                        delete.setString(1, line.sourceId()); delete.setLong(2, line.line());
                        if (delete.executeUpdate() != 1) throw new SQLException("Legacy dialogue changed during migration");
                    }
                }
                lastSource = line.sourceId(); lastLine = line.line();
            }
        }
        if (previousVersion < 4) {
            try (var statement = connection.createStatement()) { statement.execute("ALTER TABLE transcript_lines DROP COLUMN raw"); }
        }
    }
    private record LegacyDialogue(String sourceId, long line, String text, Channel channel, String batchId, int ordinal) {}

    private static void verifyIntegrity(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA quick_check")) {
            if (!result.next() || !"ok".equals(result.getString(1)) || result.next())
                throw new SQLException("Journal integrity check failed");
        }
        try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (result.next()) throw new SQLException("Journal reference integrity check failed");
        }
    }

    /** A version number alone does not establish that this database belongs to us. */
    private void validateSchema(Connection connection, int version) throws SQLException {
        try (var statement = connection.createStatement()) {
            var expected = new java.util.HashMap<String, String>();
            expected.put("journal_settings", SETTINGS_SQL);
            if (version >= 2) { expected.put("capture_sources", SOURCES_SQL); expected.put("transcript_lines", version < 4 ? LEGACY_TRANSCRIPT_SQL : TRANSCRIPT_SQL); }
            if (version >= 3) {
                expected.put("import_batches", BATCHES_SQL); expected.put("batch_files", BATCH_FILES_SQL);
                expected.put("observation_owners", OWNERS_SQL); expected.put("removed_observations", REMOVED_SQL);
                expected.put("watch_settings", WATCH_SQL);
                expected.put("watch_attempts", WATCH_ATTEMPTS_SQL);
            }
            if (version >= 6) expected.put("message_favorites", FAVORITES_SQL);
            if (version >= 7) {
                expected.put("live_sessions", LIVE_SESSIONS_SQL);
                expected.put("live_events", LIVE_EVENTS_SQL);
            }
            if (version == 8) expected.putAll(IdentitySchema.LEGACY_TABLES);
            if (version >= 9) expected.put("storage_pair", SplitJournalFiles.PAIR_SQL);
            int found = 0;
            try (var objects = statement.executeQuery(
                "SELECT name, type, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")) {
                while (objects.next()) {
                    String sql = expected.get(objects.getString(1));
                    if (sql == null || !"table".equals(objects.getString(2))
                        || !normalizeSql(sql).equals(normalizeSql(objects.getString(3))))
                        throw new SQLException("Refusing an unrelated journal schema");
                    found++;
                }
                if (found != expected.size()) throw new SQLException("Incomplete journal schema");
            }
            try (var columns = statement.executeQuery("PRAGMA table_info(journal_settings)")) {
                if (!columns.next() || !"key".equals(columns.getString("name"))
                    || !"TEXT".equalsIgnoreCase(columns.getString("type")) || columns.getInt("pk") != 1
                    || !columns.next() || !"value".equals(columns.getString("name"))
                    || !"TEXT".equalsIgnoreCase(columns.getString("type"))
                    || columns.getInt("notnull") != 1 || columns.getInt("pk") != 0 || columns.next())
                    throw new SQLException("Invalid journal settings schema");
            }
            try (var values = statement.executeQuery(
                "SELECT value FROM journal_settings WHERE key='compact_layout'")) {
                if (values.next()) {
                    String value = values.getString(1);
                    if (!"true".equals(value) && !"false".equals(value))
                        throw new SQLException("Invalid saved layout setting");
                }
            }
        }
        if (version >= 2) validateCaptureData(connection, version >= 3, version >= 4, version >= 8);
        if (version >= 3) validateWorkflowData(connection);
        if (version >= 7) validateLiveData(connection);
        if (version == 8) IdentitySchema.validateLegacy(connection);
        if (version >= 9) {SplitJournalFiles.validatePair(connection);IdentitySchema.validate(connection);}
    }

    private static String normalizeSql(String sql) {
        if (sql == null) return "";
        var normalized = new StringBuilder();
        boolean literal = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                normalized.append(c);
                if (literal && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    normalized.append(sql.charAt(++i));
                } else literal = !literal;
            } else if (literal) normalized.append(c);
            else if (!Character.isWhitespace(c)) normalized.append(Character.toUpperCase(c));
        }
        return normalized.toString();
    }

    private void validateCaptureData(Connection connection, boolean withRemoved, boolean dialogueOnly, boolean currentPolicy) throws SQLException {
        try (var statement = connection.createStatement(); var sources = statement.executeQuery(
            "SELECT s.*, (SELECT count(*) FROM transcript_lines t WHERE t.source_id=s.id)"
                + (withRemoved ? "+(SELECT count(*) FROM removed_observations r WHERE r.source_id=s.id)" : "")
                + " AS stored_count FROM capture_sources s")) {
            while (sources.next()) {
                try {
                    var source = readSource(sources);
                    var progress = readProgress(sources);
                    if (progress.processedLines() > source.totalLines() || (progress.complete() && progress.processedLines() != source.totalLines())
                        || progress.importedLines() != sources.getLong("stored_count"))
                        throw new SQLException("Inconsistent stored import checkpoint");
                } catch (IllegalArgumentException | ArithmeticException invalid) {
                    throw new SQLException("Invalid stored import metadata", invalid);
                }
            }
        }
        try (var statement = connection.createStatement(); var lines = statement.executeQuery(
            "SELECT t.*, s.processed_lines FROM transcript_lines t JOIN capture_sources s ON s.id=t.source_id")) {
            while (lines.next()) {
                try {
                    long coordinate = lines.getLong("source_line");
                    if (coordinate < 1 || coordinate > lines.getLong("processed_lines")) throw new SQLException("Transcript beyond saved checkpoint");
                    if (dialogueOnly) {
                        var line = readLine(lines);
                        // An old owned schema may contain notices recognized only by
                        // the new policy. Validate its structure before backing up;
                        // require current canonical payloads only after migration.
                        if (currentPolicy) {
                            var cleaned = normalizer.clean(line.text(), line.channel());
                            if (cleaned.disposition() != DialogueCleanup.Disposition.DIALOGUE || !line.text().equals(cleaned.text()))
                                throw new SQLException("Stored dialogue is not canonical");
                        }
                    } else {
                        requireSourceId(lines.getString("source_id"));
                        String text = lines.getString("text"), raw = lines.getString("raw");
                        Channel channel = Channel.valueOf(lines.getString("channel"));
                        if (text == null || raw == null || text.length() > 65536 || raw.length() > 65536
                            || channel == Channel.PRIVATE_MESSAGE || channel == Channel.OOC)
                            throw new SQLException("Invalid legacy transcript");
                    }
                } catch (IllegalArgumentException invalid) { throw new SQLException("Invalid stored transcript", invalid); }
            }
        }
    }

    private static ImportSource readSource(ResultSet row) throws SQLException {
        return new ImportSource(row.getString("id"), row.getString("label"), row.getString("format"), row.getLong("total_lines"),
            row.getString("policy_fingerprint"));
    }

    private static ImportProgress readProgress(ResultSet row) throws SQLException {
        return new ImportProgress(row.getString("id"), row.getLong("processed_lines"), row.getLong("imported_lines"),
            row.getLong("excluded_lines"), row.getLong("ignored_lines"), row.getInt("complete") != 0);
    }

    private static TranscriptLine readLine(ResultSet row) throws SQLException {
        return new TranscriptLine(row.getString("source_id"), row.getLong("source_line"), row.getString("clock"),
            row.getString("text"), Channel.valueOf(row.getString("channel")), row.getString("observed_speaker"));
    }

    @Override public JournalSettings load() throws SQLException {
        try (Connection connection = open();
             var query = connection.prepareStatement("SELECT value FROM journal_settings WHERE key=?")) {
            query.setString(1, "compact_layout");
            try (var result = query.executeQuery()) {
                if (!result.next()) return JournalSettings.defaults();
                String value = result.getString(1);
                if (!value.equals("true") && !value.equals("false"))
                    throw new SQLException("Invalid saved layout setting");
                return new JournalSettings(Boolean.parseBoolean(value));
            }
        }
    }

    @Override public void save(JournalSettings value) throws SQLException {
        try (Connection connection = open(); var update = connection.prepareStatement(
            "INSERT INTO journal_settings(key,value) VALUES (?,?) "
                + "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            update.setString(1, "compact_layout");
            update.setString(2, Boolean.toString(value.compactLayout()));
            update.executeUpdate();
        }
    }

    private static void rejectLiveSource(Connection connection, String sourceId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT 1 FROM live_sessions WHERE source_id=?")) {
            query.setString(1, sourceId);
            try (var row = query.executeQuery()) {
                if (row.next()) throw new SQLException("Live provenance cannot be mutated by historical import");
            }
        }
    }

    @Override public void beginLiveSession(LiveSession session) throws SQLException {
        Objects.requireNonNull(session);
        try (var connection = open()) {
            SplitJournalFiles.beginWrite(connection);
            try {
                try (var insert = connection.prepareStatement("INSERT INTO capture_sources(id,label,format,total_lines,policy_fingerprint) VALUES (?,?,'plain',0,?) ON CONFLICT(id) DO NOTHING")) {
                    insert.setString(1, session.id());
                    insert.setString(2, "Live " + session.startedAt().atZone(java.time.ZoneId.of(session.zoneId())).toLocalDate() + " " + session.id().substring(0, 12));
                    insert.setString(3, session.policyFingerprint());
                    int created = insert.executeUpdate();
                    if (created == 1) {
                        try (var live = connection.prepareStatement("INSERT INTO live_sessions(source_id,started_at,zone_id) VALUES (?,?,?)")) {
                            live.setString(1, session.id()); live.setString(2, session.startedAt().toString()); live.setString(3, session.zoneId()); live.executeUpdate();
                        }
                    }
                }
                requireLiveSession(connection, session);
                connection.commit();
            } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
        }
    }

    private static void requireLiveSession(Connection connection, LiveSession session) throws SQLException {
        try (var query = connection.prepareStatement("SELECT l.*,s.policy_fingerprint FROM live_sessions l JOIN capture_sources s ON s.id=l.source_id WHERE l.source_id=?")) {
            query.setString(1, session.id());
            try (var row = query.executeQuery()) {
                if (!row.next() || !session.startedAt().toString().equals(row.getString("started_at"))
                    || !session.zoneId().equals(row.getString("zone_id")) || !session.policyFingerprint().equals(row.getString("policy_fingerprint")))
                    throw new SQLException("Conflicting live session provenance");
            }
        }
    }

    @Override public boolean appendLiveEvent(LiveSession session, LiveEvent event) throws SQLException {
        Objects.requireNonNull(session); Objects.requireNonNull(event);
        if (!session.id().equals(event.sessionId())
            || java.time.ZoneId.of(session.zoneId()).getRules().getOffset(event.receivedAt()).getTotalSeconds() != event.offsetSeconds())
            throw new IllegalArgumentException("Conflicting live event provenance");
        try (var connection = open()) {
            SplitJournalFiles.beginWrite(connection);
            try {
                try (var lock = connection.prepareStatement("UPDATE live_sessions SET closed=closed WHERE source_id=?")) {
                    lock.setString(1, session.id());
                    if (lock.executeUpdate() != 1) throw new SQLException("Unknown live session");
                }
                requireLiveSession(connection, session);
                try (var old = connection.prepareStatement("SELECT fingerprint FROM live_events WHERE source_id=? AND sequence=?")) {
                    old.setString(1, session.id()); old.setLong(2, event.sequence());
                    try (var row = old.executeQuery()) {
                        if (row.next()) {
                            if (!event.fingerprint().equals(row.getString(1))) throw new SQLException("Conflicting live replay receipt");
                            connection.commit(); return false;
                        }
                    }
                }
                var canonical = normalizer.clean(event.text(), event.channel());
                if (canonical.disposition() != DialogueCleanup.Disposition.DIALOGUE || !event.text().equals(canonical.text()))
                    throw new IllegalArgumentException("Only canonical live dialogue may be persisted");
                try (var advance = connection.prepareStatement("UPDATE capture_sources SET total_lines=?,processed_lines=?,imported_lines=imported_lines+1 WHERE id=? AND processed_lines=? AND complete=0")) {
                    advance.setLong(1, event.sequence()); advance.setLong(2, event.sequence()); advance.setString(3, session.id()); advance.setLong(4, event.sequence() - 1);
                    if (advance.executeUpdate() != 1) throw new SQLException("Live checkpoint is closed or out of sequence");
                }
                try (var insert = connection.prepareStatement("INSERT INTO transcript_lines(source_id,source_line,clock,text,channel,observed_speaker) VALUES (?,?,?,?,?,?)")) {
                    insert.setString(1, session.id()); insert.setLong(2, event.sequence());
                    insert.setString(3, event.receivedAt().atOffset(java.time.ZoneOffset.ofTotalSeconds(event.offsetSeconds())).toLocalTime().withNano(0).toString());
                    insert.setString(4, event.text()); insert.setString(5, event.channel().name()); insert.setString(6, event.observedSpeaker()); insert.executeUpdate();
                }
                try (var receipt = connection.prepareStatement("INSERT INTO live_events VALUES (?,?,?,?,?,?)")) {
                    receipt.setString(1, session.id()); receipt.setLong(2, event.sequence()); receipt.setString(3, event.receivedAt().toString());
                    receipt.setInt(4, event.offsetSeconds()); receipt.setString(5, event.kind().name()); receipt.setString(6, event.fingerprint()); receipt.executeUpdate();
                }
                CharacterIndex.indexNew(connection,List.of(new TranscriptLine(event.sessionId(),event.sequence(),null,event.text(),event.channel(),event.observedSpeaker())));
                connection.commit(); return true;
            } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
        }
    }

    @Override public void closeLiveSession(String sessionId) throws SQLException {
        requireSourceId(sessionId);
        try (var connection = open()) {
            SplitJournalFiles.beginWrite(connection);
            try {
                try (var update = connection.prepareStatement("UPDATE live_sessions SET closed=1 WHERE source_id=?")) {
                    update.setString(1, sessionId);
                    if (update.executeUpdate() != 1) throw new SQLException("Unknown live session");
                }
                try (var update = connection.prepareStatement("UPDATE capture_sources SET complete=1 WHERE id=?")) { update.setString(1, sessionId); update.executeUpdate(); }
                connection.commit();
            } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
        }
    }

    @Override public boolean livePaused() throws SQLException {
        try (var connection = open(); var query = connection.createStatement(); var row = query.executeQuery("SELECT value FROM journal_settings WHERE key='live_paused'")) {
            if (!row.next()) return false;
            String value = row.getString(1);
            if (!"true".equals(value) && !"false".equals(value)) throw new SQLException("Invalid live pause setting");
            return Boolean.parseBoolean(value);
        }
    }

    @Override public void saveLivePaused(boolean paused) throws SQLException {
        try (var connection = open(); var update = connection.prepareStatement("INSERT INTO journal_settings(key,value) VALUES ('live_paused',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            update.setString(1, Boolean.toString(paused)); update.executeUpdate();
        }
    }

    private static void validateLiveData(Connection connection) throws SQLException {
        try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT l.*,s.*,(SELECT count(*) FROM live_events e WHERE e.source_id=l.source_id) AS receipts,(SELECT max(sequence) FROM live_events e WHERE e.source_id=l.source_id) AS last_event FROM live_sessions l JOIN capture_sources s ON s.id=l.source_id")) {
            while (rows.next()) {
                try {
                    new LiveSession(rows.getString("source_id"), Instant.parse(rows.getString("started_at")), rows.getString("zone_id"), rows.getString("policy_fingerprint"));
                    if (rows.getLong("total_lines") != rows.getLong("processed_lines")
                        || rows.getLong("processed_lines") != rows.getLong("receipts") || rows.getLong("processed_lines") != rows.getLong("last_event")
                        || rows.getBoolean("closed") != rows.getBoolean("complete") || !"plain".equals(rows.getString("format")))
                        throw new SQLException("Inconsistent live checkpoint");
                } catch (RuntimeException invalid) { throw new SQLException("Invalid live session metadata", invalid); }
            }
        }
        try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT e.*,l.zone_id,t.text,t.channel,t.observed_speaker,t.clock FROM live_events e JOIN live_sessions l ON l.source_id=e.source_id LEFT JOIN transcript_lines t ON t.source_id=e.source_id AND t.source_line=e.sequence")) {
            while (rows.next()) {
                try {
                    requireSourceId(rows.getString("fingerprint")); LiveEvent.Kind.valueOf(rows.getString("kind"));
                    var receivedAt = Instant.parse(rows.getString("received_at"));
                    if (java.time.ZoneId.of(rows.getString("zone_id")).getRules().getOffset(receivedAt).getTotalSeconds() != rows.getInt("offset_seconds"))
                        throw new SQLException("Invalid live receipt offset");
                    // Receipts remain after an explicit payload removal; active payloads
                    // must still match their acknowledged immutable event and clock.
                    if (rows.getString("text") != null) {
                        var event = new LiveEvent(rows.getString("source_id"), rows.getLong("sequence"), receivedAt,
                            rows.getInt("offset_seconds"), LiveEvent.Kind.valueOf(rows.getString("kind")), rows.getString("text"),
                            Channel.valueOf(rows.getString("channel")), rows.getString("observed_speaker"));
                        String clock = receivedAt.atOffset(java.time.ZoneOffset.ofTotalSeconds(event.offsetSeconds())).toLocalTime().withNano(0).toString();
                        if (!event.fingerprint().equals(rows.getString("fingerprint")) || !clock.equals(rows.getString("clock")))
                            throw new SQLException("Live observation differs from its durable receipt");
                    }
                } catch (RuntimeException invalid) { throw new SQLException("Invalid live event receipt", invalid); }
            }
        }
        try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT value FROM journal_settings WHERE key='live_paused'")) {
            if (rows.next() && !Set.of("true", "false").contains(rows.getString(1))) throw new SQLException("Invalid live pause setting");
        }
    }

    @Override public ImportProgress beginSource(ImportSource source) throws SQLException {
        Objects.requireNonNull(source, "source");
        try (var connection = open(); var insert = connection.prepareStatement(
            "INSERT INTO capture_sources(id,label,format,total_lines,policy_fingerprint) VALUES (?,?,?,?,?) ON CONFLICT(id) DO NOTHING")) {
            SplitJournalFiles.beginWrite(connection);
            try {
                rejectLiveSource(connection, source.id());
                insert.setString(1, source.id());
                insert.setString(2, source.label());
                insert.setString(3, source.format());
                insert.setLong(4, source.totalLines());
                insert.setString(5, source.policyFingerprint());
                insert.executeUpdate();
                try (var query = connection.prepareStatement("SELECT * FROM capture_sources WHERE id=?")) {
                    query.setString(1, source.id());
                    try (var rows = query.executeQuery()) {
                        if (!rows.next() || rows.getLong("total_lines") != source.totalLines())
                            throw new SQLException("Conflicting source metadata");
                        var progress = readProgress(rows);
                        if (!progress.complete() && !source.policyFingerprint().equals(rows.getString("policy_fingerprint")))
                            throw new SQLException("Incomplete source must resume with its original reviewed-name policy");
                        connection.commit();
                        return progress;
                    }
                }
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    @Override public ImportProgress progress(String sourceId) throws SQLException {
        requireSourceId(sourceId);
        try (var connection = open(); var query = connection.prepareStatement("SELECT * FROM capture_sources WHERE id=?")) {
            query.setString(1, sourceId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Unknown import source");
                return readProgress(rows);
            }
        }
    }

    @Override public ImportProgress appendBatch(String sourceId, long expectedProcessed, long nextProcessed,
        List<TranscriptLine> entries, long excludedDelta, long ignoredDelta, boolean complete) throws SQLException {
        return append(sourceId, expectedProcessed, nextProcessed, entries, excludedDelta, ignoredDelta, complete, null, -1);
    }

    @Override public ImportProgress appendBatchOwned(String batchId, int ordinal, String sourceId, long expectedProcessed, long nextProcessed,
        List<TranscriptLine> entries, long excludedDelta, long ignoredDelta, boolean complete) throws SQLException {
        requireBatchId(batchId);
        return append(sourceId, expectedProcessed, nextProcessed, entries, excludedDelta, ignoredDelta, complete, batchId, ordinal);
    }

    private ImportProgress append(String sourceId, long expectedProcessed, long nextProcessed,
        List<TranscriptLine> entries, long excludedDelta, long ignoredDelta, boolean complete, String batchId, int ordinal) throws SQLException {
        requireSourceId(sourceId);
        entries = List.copyOf(entries);
        if (expectedProcessed < 0 || nextProcessed < expectedProcessed || entries.size() > 500
            || nextProcessed - expectedProcessed > 500 || excludedDelta < 0 || ignoredDelta < 0
            || Math.addExact(Math.addExact(entries.size(), excludedDelta), ignoredDelta) != nextProcessed - expectedProcessed)
            throw new IllegalArgumentException("Invalid import batch accounting");
        long previous = expectedProcessed;
        for (var line : entries) {
            var canonical = normalizer.clean(line.text(), line.channel());
            if (canonical.disposition() != DialogueCleanup.Disposition.DIALOGUE || !line.text().equals(canonical.text()))
                throw new IllegalArgumentException("Only canonical dialogue may be appended");
            if (!sourceId.equals(line.sourceId()) || line.sourceLine() <= previous || line.sourceLine() > nextProcessed
                || line.channel() == Channel.OOC || line.channel() == Channel.PRIVATE_MESSAGE)
                throw new IllegalArgumentException("Invalid import batch coordinates or channel");
            previous = line.sourceLine();
        }
        try (var connection = open()) {
            SplitJournalFiles.beginWrite(connection);
            try {
                rejectLiveSource(connection, sourceId);
                BatchFile ownedFile = null;
                String batchPolicy = null;
                if (batchId != null) {
                    try (var query = connection.prepareStatement("SELECT f.*,b.status AS batch_status,b.policy_fingerprint FROM batch_files f JOIN import_batches b ON b.id=f.batch_id WHERE f.batch_id=? AND f.ordinal=?")) {
                        query.setString(1, batchId); query.setInt(2, ordinal);
                        try (var row = query.executeQuery()) {
                            if (!row.next() || !"RUNNING".equals(row.getString("batch_status")) || !"RUNNING".equals(row.getString("status")))
                                throw new SQLException("Batch and file must be running before appending");
                            ownedFile = readBatchFile(row);
                            batchPolicy = row.getString("policy_fingerprint");
                            if (ownedFile.sourceId() != null && !sourceId.equals(ownedFile.sourceId())) throw new SQLException("Batch file source changed after preview");
                            if (ownedFile.afterLine() != ownedFile.beforeLine() && ownedFile.afterLine() != expectedProcessed)
                                throw new SQLException("Batch file contribution cursor changed");
                        }
                    }
                }
                ImportProgress current;
                long total;
                try (var query = connection.prepareStatement("SELECT * FROM capture_sources WHERE id=?")) {
                    query.setString(1, sourceId);
                    try (var rows = query.executeQuery()) {
                        if (!rows.next()) throw new SQLException("Unknown import source");
                        current = readProgress(rows);
                        total = rows.getLong("total_lines");
                        if (batchPolicy != null && !batchPolicy.equals(rows.getString("policy_fingerprint")))
                            throw new SQLException("Batch classification policy differs from source policy");
                    }
                }
                if (current.processedLines() != expectedProcessed || nextProcessed > total
                    || (complete && nextProcessed != total)) throw new SQLException("Conflicting import checkpoint");
                if (current.complete()) {
                    if (nextProcessed != expectedProcessed || !entries.isEmpty() || excludedDelta != 0 || ignoredDelta != 0 || !complete)
                        throw new SQLException("Completed import source is immutable");
                    if (ownedFile != null) updateContribution(connection, ownedFile, sourceId, expectedProcessed, nextProcessed, 0, 0, 0);
                    connection.commit();
                    return current;
                }
                var next = new ImportProgress(sourceId, nextProcessed, Math.addExact(current.importedLines(), entries.size()),
                    Math.addExact(current.excludedLines(), excludedDelta), Math.addExact(current.ignoredLines(), ignoredDelta), complete);
                try (var update = connection.prepareStatement("""
                    UPDATE capture_sources SET processed_lines=?,imported_lines=?,excluded_lines=?,ignored_lines=?,complete=?
                    WHERE id=? AND processed_lines=? AND complete=0
                    """)) {
                    update.setLong(1, next.processedLines());
                    update.setLong(2, next.importedLines());
                    update.setLong(3, next.excludedLines());
                    update.setLong(4, next.ignoredLines());
                    update.setInt(5, complete ? 1 : 0);
                    update.setString(6, sourceId);
                    update.setLong(7, expectedProcessed);
                    if (update.executeUpdate() != 1) throw new SQLException("Concurrent import checkpoint changed");
                }
                try (var insert = connection.prepareStatement("""
                    INSERT INTO transcript_lines(source_id,source_line,clock,text,channel,observed_speaker)
                    VALUES (?,?,?,?,?,?)
                    """)) {
                    for (var line : entries) {
                        insert.setString(1, sourceId);
                        insert.setLong(2, line.sourceLine());
                        insert.setString(3, line.clock());
                        insert.setString(4, line.text());
                        insert.setString(5, line.channel().name());
                        insert.setString(6, line.observedSpeaker());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                if (ownedFile != null) {
                    try (var owner = connection.prepareStatement("INSERT INTO observation_owners VALUES (?,?,?,?)")) {
                        for (var line : entries) {
                            owner.setString(1, sourceId); owner.setLong(2, line.sourceLine());
                            owner.setString(3, batchId); owner.setInt(4, ordinal); owner.addBatch();
                        }
                        owner.executeBatch();
                    }
                    updateContribution(connection, ownedFile, sourceId, expectedProcessed, nextProcessed, entries.size(), excludedDelta, ignoredDelta);
                }
                CharacterIndex.indexNew(connection,entries);
                connection.commit();
                return next;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void updateContribution(Connection connection, BatchFile file, String sourceId, long before, long after,
        long added, long excluded, long ignored) throws SQLException {
        long first = file.beforeLine() == file.afterLine() ? before : file.beforeLine();
        try (var update = connection.prepareStatement("UPDATE batch_files SET source_id=?,before_line=?,after_line=?,added=added+?,excluded=excluded+?,ignored=ignored+? WHERE batch_id=? AND ordinal=? AND status='RUNNING'")) {
            update.setString(1, sourceId); update.setLong(2, first); update.setLong(3, after);
            update.setLong(4, added); update.setLong(5, excluded); update.setLong(6, ignored);
            update.setString(7, file.batchId()); update.setInt(8, file.ordinal());
            if (update.executeUpdate() != 1) throw new SQLException("Batch file stopped before contribution commit");
        }
    }

    @Override public List<ImportSource> sources() throws SQLException {
        var result = new ArrayList<ImportSource>();
        try (var connection = open(); var query = connection.createStatement(); var rows = query.executeQuery(
            "SELECT id,label,format,total_lines,policy_fingerprint FROM capture_sources ORDER BY label COLLATE NOCASE,id")) {
            while (rows.next()) result.add(readSource(rows));
        }
        return List.copyOf(result);
    }

    @Override public TranscriptPage transcript(String sourceId, String query, int offset, int limit) throws SQLException {
        return transcript(sourceId, query, null, offset, limit);
    }

    @Override public TranscriptPage transcript(String sourceId, String query, Channel channel, int offset, int limit) throws SQLException {
        return transcript(sourceId, query, channel, false, offset, limit);
    }

    @Override public TranscriptPage transcript(String sourceId, String query, Channel channel, boolean favoritesOnly, int offset, int limit) throws SQLException {
        if (sourceId != null) requireSourceId(sourceId);
        if (channel == Channel.PRIVATE_MESSAGE || channel == Channel.OOC) throw new IllegalArgumentException("Excluded channels are not stored");
        if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("Invalid transcript page bounds");
        String filter = query == null ? "" : query;
        if (filter.length() > 256) throw new IllegalArgumentException("Transcript query is too long");
        String pattern = "%" + filter.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String where = " WHERE (? IS NULL OR source_id=?) AND (text LIKE ? ESCAPE '\\' OR observed_speaker LIKE ? ESCAPE '\\') AND (? IS NULL OR channel=?)";
        if (favoritesOnly) where += " AND EXISTS (SELECT 1 FROM message_favorites f WHERE f.source_id=transcript_lines.source_id AND f.source_line=transcript_lines.source_line)";
        try (var connection = open()) {
            connection.setAutoCommit(false); // Count, entries and favorite state share the read snapshot.
            long total;
            try (var count = connection.prepareStatement("SELECT count(*) FROM transcript_lines" + where)) {
                bindFilter(count, sourceId, pattern, channel);
                try (var rows = count.executeQuery()) { total = rows.getLong(1); }
            }
            var entries = new ArrayList<TranscriptLine>();
            var favorites = new java.util.HashSet<MessageReference>();
            try (var select = connection.prepareStatement("SELECT *, EXISTS (SELECT 1 FROM message_favorites f WHERE f.source_id=transcript_lines.source_id AND f.source_line=transcript_lines.source_line) AS favorite FROM transcript_lines" + where
                + " ORDER BY source_id,source_line LIMIT ? OFFSET ?")) {
                bindFilter(select, sourceId, pattern, channel);
                select.setInt(7, limit);
                select.setInt(8, offset);
                try (var rows = select.executeQuery()) {
                    while (rows.next()) {
                        var line = readLine(rows); entries.add(line);
                        if (rows.getBoolean("favorite")) favorites.add(MessageReference.of(line));
                    }
                }
            }
            connection.commit();
            return new TranscriptPage(entries, total, offset, limit, favorites);
        }
    }

    @Override public void setFavorite(MessageReference message, boolean favorite) throws SQLException {
        Objects.requireNonNull(message);
        try (var connection = open()) {
            SplitJournalFiles.beginWrite(connection);
            try {
                // Acquire the write lock before existence check: undo cannot race this decision.
                try (var lock = connection.createStatement()) {
                    lock.executeUpdate("UPDATE journal_settings SET value=value WHERE key='compact_layout'");
                }
                try (var query = connection.prepareStatement("SELECT 1 FROM transcript_lines WHERE source_id=? AND source_line=?")) {
                    query.setString(1, message.sourceId()); query.setLong(2, message.sourceLine());
                    try (var result = query.executeQuery()) {
                        if (!result.next()) throw new SQLException("Message is no longer available");
                    }
                }
                try (var update = connection.prepareStatement(favorite
                    ? "INSERT INTO message_favorites(source_id,source_line) VALUES (?,?) ON CONFLICT DO NOTHING"
                    : "DELETE FROM message_favorites WHERE source_id=? AND source_line=?")) {
                    update.setString(1, message.sourceId()); update.setLong(2, message.sourceLine()); update.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
        }
    }

    private static void bindFilter(java.sql.PreparedStatement statement, String sourceId, String pattern, Channel channel) throws SQLException {
        statement.setString(1, sourceId);
        statement.setString(2, sourceId);
        statement.setString(3, pattern);
        statement.setString(4, pattern);
        statement.setString(5, channel == null ? null : channel.name());
        statement.setString(6, channel == null ? null : channel.name());
    }

    private static void requireSourceId(String sourceId) {
        if (sourceId == null || !sourceId.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid source digest");
    }

    private static void requireBatchId(String batchId) {
        if (batchId == null || !UUID.fromString(batchId).toString().equals(batchId)) throw new IllegalArgumentException("Invalid batch identity");
    }

    private static String encodeNames(Set<String> names) {
        return BatchPlan.checkedNames(names).stream().map(name -> Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8)))
            .collect(java.util.stream.Collectors.joining(","));
    }

    private static Set<String> decodeNames(String encoded) throws SQLException {
        if (encoded == null || encoded.length() > 140_000) throw new SQLException("Invalid saved reviewed-name policy");
        var names = new TreeSet<String>();
        if (encoded.isEmpty()) return Set.of();
        try {
            for (String item : encoded.split(",", -1)) {
                String name = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Base64.getDecoder().decode(item))).toString();
                if (!names.add(name)) throw new SQLException("Duplicate saved reviewed name");
            }
            return BatchPlan.checkedNames(names);
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException failure) {
            throw new SQLException("Invalid saved reviewed-name policy", failure);
        }
    }

    private static BatchFile readBatchFile(ResultSet row) throws SQLException {
        return new BatchFile(row.getString("batch_id"), row.getInt("ordinal"), row.getString("path"), row.getString("label"),
            row.getString("source_id"), row.getString("status"), row.getString("error_code"), row.getLong("before_line"),
            row.getLong("after_line"), row.getLong("added"), row.getLong("excluded"), row.getLong("ignored"));
    }

    private static List<BatchFile> batchFiles(Connection connection, String batchId) throws SQLException {
        var files = new ArrayList<BatchFile>();
        try (var query = connection.prepareStatement("SELECT * FROM batch_files WHERE batch_id=? ORDER BY ordinal")) {
            query.setString(1, batchId);
            try (var rows = query.executeQuery()) { while (rows.next()) files.add(readBatchFile(rows)); }
        }
        return List.copyOf(files);
    }

    private static BatchPlan batchPlan(Connection connection, String batchId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT * FROM import_batches WHERE id=?")) {
            query.setString(1, batchId);
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new SQLException("Unknown import batch");
                return new BatchPlan(batchId, row.getString("root"), row.getString("policy_fingerprint"), row.getString("created_at"),
                    decodeNames(row.getString("reviewed_names")), batchFiles(connection, batchId));
            }
        }
    }

    @Override public void createBatch(BatchPlan plan) throws SQLException {
        Objects.requireNonNull(plan, "plan");
        for (var file : plan.files()) {
            if (!Set.of("PENDING", "DUPLICATE", "SKIPPED", "FAILED").contains(file.status()) || file.beforeLine() != file.afterLine() || file.added() != 0 || file.excluded() != 0 || file.ignored() != 0)
                throw new IllegalArgumentException("New batch must not claim existing observations or progress");
        }
        try (var connection = open()) {
            SplitJournalFiles.beginWrite(connection);
            try {
                try (var insert = connection.prepareStatement("INSERT INTO import_batches VALUES (?,?,?,?,?,?,?)")) {
                    insert.setString(1, plan.id()); insert.setString(2, plan.root()); insert.setString(3, plan.policyFingerprint());
                    insert.setString(4, encodeNames(plan.reviewedNames())); insert.setString(5, "PENDING");
                    insert.setString(6, plan.createdAt()); insert.setString(7, plan.createdAt()); insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO batch_files(batch_id,ordinal,path,label,source_id,status,error_code,before_line,after_line) VALUES (?,?,?,?,?,?,?,?,?)")) {
                    for (var file : plan.files()) {
                        insert.setString(1, plan.id()); insert.setInt(2, file.ordinal()); insert.setString(3, file.path()); insert.setString(4, file.label());
                        insert.setString(5, file.sourceId()); insert.setString(6, file.status()); insert.setString(7, file.errorCode());
                        insert.setLong(8, file.beforeLine()); insert.setLong(9, file.afterLine()); insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
        }
    }

    @Override public BatchPlan batchPlan(String batchId) throws SQLException {
        requireBatchId(batchId);
        try (var connection = open()) { return batchPlan(connection, batchId); }
    }

    @Override public List<BatchFile> batchFiles(String batchId) throws SQLException {
        requireBatchId(batchId);
        try (var connection = open()) { return batchFiles(connection, batchId); }
    }

    private static BatchSummary readSummary(ResultSet row) throws SQLException {
        return new BatchSummary(row.getString("id"), row.getString("root"), row.getString("status"),
            row.getString("created_at"), row.getString("updated_at"), row.getInt("file_count"));
    }

    @Override public List<BatchSummary> batches(int limit) throws SQLException {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("Invalid batch history limit");
        var result = new ArrayList<BatchSummary>();
        try (var connection = open(); var query = connection.prepareStatement("SELECT b.*,(SELECT count(*) FROM batch_files f WHERE f.batch_id=b.id) AS file_count FROM import_batches b ORDER BY created_at DESC,id LIMIT ?")) {
            query.setInt(1, limit);
            try (var rows = query.executeQuery()) { while (rows.next()) result.add(readSummary(rows)); }
        }
        return List.copyOf(result);
    }

    @Override public BatchSummary batchSummary(String batchId) throws SQLException {
        requireBatchId(batchId);
        try (var connection = open(); var query = connection.prepareStatement("SELECT b.*,(SELECT count(*) FROM batch_files f WHERE f.batch_id=b.id) AS file_count FROM import_batches b WHERE id=?")) {
            query.setString(1, batchId);
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new SQLException("Unknown import batch");
                return readSummary(row);
            }
        }
    }

    @Override public void updateFile(String batchId, int ordinal, String status, String sourceId, String errorCode) throws SQLException {
        requireBatchId(batchId);
        try (var connection = open()) {
            SplitJournalFiles.beginWrite(connection);
            try {
                BatchFile file;
                try (var query = connection.prepareStatement("SELECT f.*,b.status AS batch_status FROM batch_files f JOIN import_batches b ON b.id=f.batch_id WHERE f.batch_id=? AND f.ordinal=?")) {
                    query.setString(1, batchId); query.setInt(2, ordinal);
                    try (var row = query.executeQuery()) {
                        if (!row.next() || !"RUNNING".equals(row.getString("batch_status"))) throw new SQLException("Batch is not running");
                        file = readBatchFile(row);
                    }
                }
                if (!BatchFile.STATUSES.contains(status) || "UNDONE".equals(status) || "PENDING".equals(status)) throw new IllegalArgumentException("Invalid file transition");
                if (Set.of("COMPLETE", "DUPLICATE", "SKIPPED", "UNDONE").contains(file.status()) && !file.status().equals(status))
                    throw new SQLException("Completed file cannot be retried or changed");
                if (sourceId == null) sourceId = file.sourceId();
                if (file.sourceId() != null && !file.sourceId().equals(sourceId)) throw new SQLException("Preview source identity changed");
                new BatchFile(batchId, ordinal, file.path(), file.label(), sourceId, status, errorCode, file.beforeLine(), file.afterLine(), file.added(), file.excluded(), file.ignored());
                try (var update = connection.prepareStatement("UPDATE batch_files SET status=?,source_id=?,error_code=? WHERE batch_id=? AND ordinal=?")) {
                    update.setString(1, status); update.setString(2, sourceId); update.setString(3, errorCode); update.setString(4, batchId); update.setInt(5, ordinal);
                    if (update.executeUpdate() != 1) throw new SQLException("Unknown batch file");
                }
                touchBatch(connection, batchId);
                connection.commit();
            } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
        }
    }

    private static void touchBatch(Connection connection, String batchId) throws SQLException {
        try (var update = connection.prepareStatement("UPDATE import_batches SET updated_at=? WHERE id=?")) {
            update.setString(1, Instant.now().toString()); update.setString(2, batchId); update.executeUpdate();
        }
    }

    @Override public void finishBatch(String batchId, String status) throws SQLException {
        requireBatchId(batchId);
        if (!BatchSummary.STATUSES.contains(status) || "PENDING".equals(status) || "UNDONE".equals(status)) throw new IllegalArgumentException("Invalid batch transition");
        try (var connection = open()) {
            SplitJournalFiles.beginWrite(connection);
            try {
                String old;
                try (var query = connection.prepareStatement("SELECT status FROM import_batches WHERE id=?")) {
                    query.setString(1, batchId);
                    try (var row = query.executeQuery()) {
                        if (!row.next()) throw new SQLException("Unknown import batch");
                        old = row.getString(1);
                    }
                }
                if ("UNDONE".equals(old) || ("COMPLETE".equals(old) && !old.equals(status))) throw new SQLException("Finished batch cannot restart");
                if (!"RUNNING".equals(status)) {
                    try (var query = connection.prepareStatement("SELECT count(*) FROM batch_files WHERE batch_id=? AND (status IN ('RUNNING','PENDING') OR (?='COMPLETE' AND status IN ('FAILED','CANCELLED','INTERRUPTED')))")) {
                        query.setString(1, batchId); query.setString(2, status);
                        try (var rows = query.executeQuery()) {
                            if (rows.getLong(1) != 0 && Set.of("COMPLETE", "PARTIAL").contains(status)) throw new SQLException("Batch has unfinished or unsuccessful files");
                        }
                    }
                    if (Set.of("CANCELLED", "FAILED", "INTERRUPTED").contains(status)) {
                        try (var update = connection.prepareStatement("UPDATE batch_files SET status=?,error_code=? WHERE batch_id=? AND status IN ('PENDING','RUNNING')")) {
                            update.setString(1, status); update.setString(2, "BATCH_" + status); update.setString(3, batchId); update.executeUpdate();
                        }
                    }
                }
                try (var update = connection.prepareStatement("UPDATE import_batches SET status=?,updated_at=? WHERE id=?")) {
                    update.setString(1, status); update.setString(2, Instant.now().toString()); update.setString(3, batchId); update.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
        }
    }

    @Override public long undoBatch(String batchId) throws SQLException {
        requireBatchId(batchId);
        try (var connection = open()) {
            SplitJournalFiles.beginWrite(connection);
            try {
                String status;
                try (var query = connection.prepareStatement("SELECT status FROM import_batches WHERE id=?")) {
                    query.setString(1, batchId);
                    try (var row = query.executeQuery()) {
                        if (!row.next()) throw new SQLException("Unknown import batch");
                        status = row.getString(1);
                    }
                }
                if ("UNDONE".equals(status)) { connection.commit(); return 0; }
                if (Set.of("PENDING", "RUNNING").contains(status)) throw new SQLException("Stop a batch before undoing its committed observations");
                try (var update = connection.prepareStatement("UPDATE import_batches SET status='UNDONE',updated_at=? WHERE id=? AND status=?")) {
                    update.setString(1, Instant.now().toString()); update.setString(2, batchId); update.setString(3, status);
                    if (update.executeUpdate() != 1) throw new SQLException("Batch status changed during undo");
                }
                long removed;
                try (var ledger = connection.prepareStatement("INSERT INTO removed_observations SELECT source_id,source_line,batch_id,file_ordinal FROM observation_owners WHERE batch_id=?")) {
                    ledger.setString(1, batchId); removed = ledger.executeUpdate();
                }
                for(String table:List.of("memory_assignments","character_evidence")) {
                    try(var delete=connection.prepareStatement("DELETE FROM characters."+table+" WHERE (source_id,source_line) IN (SELECT source_id,source_line FROM main.observation_owners WHERE batch_id=?)")) {
                        delete.setString(1,batchId);delete.executeUpdate();
                    }
                }
                try (var delete = connection.prepareStatement("DELETE FROM transcript_lines WHERE (source_id,source_line) IN (SELECT source_id,source_line FROM observation_owners WHERE batch_id=?)")) {
                    delete.setString(1, batchId);
                    if (delete.executeUpdate() != removed) throw new SQLException("Undo ownership count changed");
                }
                try (var update = connection.prepareStatement("UPDATE batch_files SET status='UNDONE' WHERE batch_id=?")) { update.setString(1, batchId); update.executeUpdate(); }
                connection.commit();
                return removed;
            } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
        }
    }

    @Override public long removedRecords(String sourceId) throws SQLException {
        if (sourceId != null) requireSourceId(sourceId);
        try (var connection = open(); var query = connection.prepareStatement("SELECT count(*) FROM removed_observations WHERE (? IS NULL OR source_id=?)")) {
            query.setString(1, sourceId); query.setString(2, sourceId);
            try (var row = query.executeQuery()) { return row.getLong(1); }
        }
    }

    @Override public long unknownRecords(String batchId) throws SQLException {
        requireBatchId(batchId);
        try (var connection = open(); var query = connection.prepareStatement("SELECT count(*) FROM transcript_lines t JOIN observation_owners o ON o.source_id=t.source_id AND o.source_line=t.source_line WHERE o.batch_id=? AND t.channel='UNKNOWN'")) {
            query.setString(1, batchId);
            try (var row = query.executeQuery()) { return row.getLong(1); }
        }
    }

    private static WatchSettings watchSettings(Connection connection) throws SQLException {
        try (var query = connection.createStatement(); var row = query.executeQuery("SELECT * FROM watch_settings WHERE id=1")) {
            if (!row.next()) throw new SQLException("Missing watch settings");
            return new WatchSettings(row.getString("folder"), row.getInt("enabled") != 0, decodeNames(row.getString("reviewed_names")));
        }
    }

    @Override public WatchSettings watchSettings() throws SQLException {
        try (var connection = open()) { return watchSettings(connection); }
    }

    @Override public void saveWatchSettings(WatchSettings settings) throws SQLException {
        Objects.requireNonNull(settings, "settings");
        try (var connection = open(); var update = connection.prepareStatement("UPDATE watch_settings SET folder=?,enabled=?,reviewed_names=? WHERE id=1")) {
            update.setString(1, settings.folder()); update.setInt(2, settings.enabled() ? 1 : 0); update.setString(3, encodeNames(settings.reviewedNames()));
            if (update.executeUpdate() != 1) throw new SQLException("Missing watch settings");
        }
    }

    @Override public boolean watchAttempted(String path, String fingerprint) throws SQLException {
        validateWatchAttempt(path, fingerprint, "ATTEMPTED");
        try (var connection = open(); var query = connection.prepareStatement("SELECT 1 FROM watch_attempts WHERE path=? AND fingerprint=?")) {
            query.setString(1, path); query.setString(2, fingerprint);
            try (var rows = query.executeQuery()) { return rows.next(); }
        }
    }

    @Override public void recordWatchAttempt(String path, String fingerprint, String status) throws SQLException {
        validateWatchAttempt(path, fingerprint, status);
        try (var connection = open(); var update = connection.prepareStatement("INSERT INTO watch_attempts VALUES (?,?,?,?) ON CONFLICT(path) DO UPDATE SET fingerprint=excluded.fingerprint,status=excluded.status,attempted_at=excluded.attempted_at")) {
            update.setString(1, path); update.setString(2, fingerprint); update.setString(3, status); update.setString(4, Instant.now().toString()); update.executeUpdate();
        }
    }

    private static void validateWatchAttempt(String path, String fingerprint, String status) {
        if (path == null || path.isBlank() || path.length() > 4096) throw new IllegalArgumentException("Invalid watched path");
        requireSourceId(fingerprint);
        if (!Set.of("ATTEMPTED", "COMPLETE", "FAILED", "CANCELLED", "INTERRUPTED").contains(status)) throw new IllegalArgumentException("Invalid watched attempt status");
    }

    private static void validateWorkflowData(Connection connection) throws SQLException {
        try {
            watchSettings(connection);
            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT * FROM watch_attempts")) {
                while (rows.next()) { validateWatchAttempt(rows.getString("path"), rows.getString("fingerprint"), rows.getString("status")); Instant.parse(rows.getString("attempted_at")); }
            }
            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT b.*,(SELECT count(*) FROM batch_files f WHERE f.batch_id=b.id) AS file_count FROM import_batches b")) {
                while (rows.next()) { var summary = readSummary(rows); batchPlan(connection, summary.id()); }
            }
            try (var query = connection.createStatement(); var rows = query.executeQuery("""
                SELECT 1 FROM removed_observations r JOIN transcript_lines t ON t.source_id=r.source_id AND t.source_line=r.source_line
                UNION ALL
                SELECT 1 FROM removed_observations r JOIN capture_sources s ON s.id=r.source_id WHERE r.source_line>s.processed_lines
                UNION ALL
                SELECT 1 FROM observation_owners o JOIN batch_files f ON f.batch_id=o.batch_id AND f.ordinal=o.file_ordinal
                  WHERE o.source_id IS NOT f.source_id OR o.source_line<=f.before_line OR o.source_line>f.after_line
                UNION ALL
                SELECT 1 FROM removed_observations r JOIN batch_files f ON f.batch_id=r.batch_id AND f.ordinal=r.file_ordinal
                  JOIN import_batches b ON b.id=f.batch_id
                  WHERE r.source_id IS NOT f.source_id OR r.source_line<=f.before_line OR r.source_line>f.after_line OR b.status!='UNDONE'
                UNION ALL
                SELECT 1 FROM batch_files f WHERE f.added!=(SELECT count(*) FROM observation_owners o WHERE o.batch_id=f.batch_id AND o.file_ordinal=f.ordinal)
                  +(SELECT count(*) FROM removed_observations r WHERE r.batch_id=f.batch_id AND r.file_ordinal=f.ordinal)
                LIMIT 1
                """)) {
                if (rows.next()) throw new SQLException("Inconsistent observation ownership or removal ledger");
            }
        } catch (IllegalArgumentException | ArithmeticException failure) { throw new SQLException("Invalid stored batch metadata", failure); }
    }

    private static void removeCharacterReferences(Connection connection,String source,long line) throws SQLException {
        for(String table:List.of("memory_assignments","character_evidence")) {
            try(var delete=connection.prepareStatement("DELETE FROM characters."+table+" WHERE source_id=? AND source_line=?")) {
                delete.setString(1,source);delete.setLong(2,line);delete.executeUpdate();
            }
        }
    }
    private static void validateCharacters(Connection connection) throws SQLException {
        var expected=new java.util.HashMap<>(IdentitySchema.TABLES);expected.putAll(CharacterIndex.TABLES);expected.putAll(CharacterIndex.INDEXES);expected.put("storage_pair",SplitJournalFiles.PAIR_SQL);
        int found=0;
        try(var s=connection.createStatement();var rows=s.executeQuery("SELECT name,type,sql FROM characters.sqlite_master WHERE name NOT LIKE 'sqlite_%'")) {
            while(rows.next()) {
                String sql=expected.get(rows.getString(1));
                String type=CharacterIndex.INDEXES.containsKey(rows.getString(1))?"index":"table";
                if(sql==null||!type.equals(rows.getString(2))||!normalizeSql(sql).equals(normalizeSql(rows.getString(3)))) throw new SQLException("Refusing unrelated character storage");
                found++;
            }
        }
        if(found!=expected.size()) throw new SQLException("Incomplete character storage");
        IdentitySchema.validate(connection);
    }

    @Override public Path createBackup(Path destination) throws Exception {
        return NativePairBackup.pair(database,characters,destination.toAbsolutePath().normalize());
    }
}
