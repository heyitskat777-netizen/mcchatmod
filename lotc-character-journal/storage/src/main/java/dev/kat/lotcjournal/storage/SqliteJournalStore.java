package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.BackupService;
import dev.kat.lotcjournal.domain.CaptureRepository;
import dev.kat.lotcjournal.domain.Channel;
import dev.kat.lotcjournal.domain.ImportProgress;
import dev.kat.lotcjournal.domain.ImportSource;
import dev.kat.lotcjournal.domain.JournalSettings;
import dev.kat.lotcjournal.domain.SettingsRepository;
import dev.kat.lotcjournal.domain.TranscriptLine;
import dev.kat.lotcjournal.domain.TranscriptPage;
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

/** SQLite adapter. Callers use short-lived connections only on journal/import worker threads. */
public final class SqliteJournalStore implements SettingsRepository, BackupService, CaptureRepository {
    private static final int SCHEMA_VERSION = 2;
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
    private static final String TRANSCRIPT_SQL = """
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
    private final Path database;

    public SqliteJournalStore(Path database) { this.database = database.toAbsolutePath(); }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=2000");
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
        return connection;
    }

    @Override public void initialize() throws Exception {
        Files.createDirectories(database.getParent());
        try (Connection connection = open(); var statement = connection.createStatement()) {
            int version;
            try (var result = statement.executeQuery("PRAGMA user_version")) {
                version = result.getInt(1);
            }
            if (version < 0 || version > SCHEMA_VERSION) throw new SQLException("Unsupported journal database version");
            if (version == 0) {
                try (var tables = statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")) {
                    if (tables.next()) throw new SQLException("Refusing to adopt an unrelated database");
                }
                connection.setAutoCommit(false);
                try {
                    statement.execute(SETTINGS_SQL);
                    statement.execute("INSERT INTO journal_settings VALUES ('compact_layout','false')");
                    createCaptureSchema(connection);
                    statement.execute("PRAGMA user_version=2");
                    validateSchema(connection, SCHEMA_VERSION);
                    verifyIntegrity(connection);
                    connection.commit();
                } catch (SQLException error) {
                    connection.rollback();
                    throw error;
                } finally { connection.setAutoCommit(true); }
            } else if (version == 1) {
                validateSchema(connection, 1);
                verifyIntegrity(connection);
                // No schema or WAL mutation until a complete, owned v1 snapshot exists.
                Path backup = createBackup(database.getParent().resolve("backups").resolve(
                    database.getFileName() + "-pre-schema2-" + UUID.randomUUID() + ".sqlite"));
                try (var saved = DriverManager.getConnection("jdbc:sqlite:" + backup)) {
                    validateSchema(saved, 1);
                    verifyIntegrity(saved);
                }
                connection.setAutoCommit(false);
                try {
                    createCaptureSchema(connection);
                    statement.execute("PRAGMA user_version=2");
                    validateSchema(connection, SCHEMA_VERSION);
                    verifyIntegrity(connection);
                    connection.commit();
                } catch (SQLException error) {
                    connection.rollback();
                    throw error;
                } finally { connection.setAutoCommit(true); }
            }
            validateSchema(connection, SCHEMA_VERSION);
            verifyIntegrity(connection);
            statement.execute("PRAGMA journal_mode=WAL");
        }
    }

    private static void createCaptureSchema(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(SOURCES_SQL);
            statement.execute(TRANSCRIPT_SQL);
        }
    }

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
            Map<String, String> expected = version == 1 ? Map.of("journal_settings", SETTINGS_SQL)
                : Map.of("journal_settings", SETTINGS_SQL, "capture_sources", SOURCES_SQL, "transcript_lines", TRANSCRIPT_SQL);
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
        if (version == 2) validateCaptureData(connection);
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

    private static void validateCaptureData(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var sources = statement.executeQuery(
            "SELECT s.*, (SELECT count(*) FROM transcript_lines t WHERE t.source_id=s.id) AS stored_count FROM capture_sources s")) {
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
                    var line = readLine(lines);
                    if (line.sourceLine() > lines.getLong("processed_lines")) throw new SQLException("Transcript beyond saved checkpoint");
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
            row.getString("raw"), row.getString("text"), Channel.valueOf(row.getString("channel")), row.getString("observed_speaker"));
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

    @Override public ImportProgress beginSource(ImportSource source) throws SQLException {
        Objects.requireNonNull(source, "source");
        try (var connection = open(); var insert = connection.prepareStatement(
            "INSERT INTO capture_sources(id,label,format,total_lines,policy_fingerprint) VALUES (?,?,?,?,?) ON CONFLICT(id) DO NOTHING")) {
            connection.setAutoCommit(false);
            try {
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
        requireSourceId(sourceId);
        entries = List.copyOf(entries);
        if (expectedProcessed < 0 || nextProcessed < expectedProcessed || entries.size() > 500
            || nextProcessed - expectedProcessed > 500 || excludedDelta < 0 || ignoredDelta < 0
            || Math.addExact(Math.addExact(entries.size(), excludedDelta), ignoredDelta) != nextProcessed - expectedProcessed)
            throw new IllegalArgumentException("Invalid import batch accounting");
        long previous = expectedProcessed;
        for (var line : entries) {
            if (!sourceId.equals(line.sourceId()) || line.sourceLine() <= previous || line.sourceLine() > nextProcessed
                || line.channel() == Channel.OOC || line.channel() == Channel.PRIVATE_MESSAGE)
                throw new IllegalArgumentException("Invalid import batch coordinates or channel");
            previous = line.sourceLine();
        }
        try (var connection = open()) {
            connection.setAutoCommit(false);
            try {
                ImportProgress current;
                long total;
                try (var query = connection.prepareStatement("SELECT * FROM capture_sources WHERE id=?")) {
                    query.setString(1, sourceId);
                    try (var rows = query.executeQuery()) {
                        if (!rows.next()) throw new SQLException("Unknown import source");
                        current = readProgress(rows);
                        total = rows.getLong("total_lines");
                    }
                }
                if (current.processedLines() != expectedProcessed || nextProcessed > total
                    || (complete && nextProcessed != total)) throw new SQLException("Conflicting import checkpoint");
                if (current.complete()) {
                    if (nextProcessed != expectedProcessed || !entries.isEmpty() || excludedDelta != 0 || ignoredDelta != 0 || !complete)
                        throw new SQLException("Completed import source is immutable");
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
                    INSERT INTO transcript_lines(source_id,source_line,clock,raw,text,channel,observed_speaker)
                    VALUES (?,?,?,?,?,?,?)
                    """)) {
                    for (var line : entries) {
                        insert.setString(1, sourceId);
                        insert.setLong(2, line.sourceLine());
                        insert.setString(3, line.clock());
                        insert.setString(4, line.raw());
                        insert.setString(5, line.text());
                        insert.setString(6, line.channel().name());
                        insert.setString(7, line.observedSpeaker());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
                return next;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
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
        if (sourceId != null) requireSourceId(sourceId);
        if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("Invalid transcript page bounds");
        String filter = query == null ? "" : query;
        if (filter.length() > 256) throw new IllegalArgumentException("Transcript query is too long");
        String pattern = "%" + filter.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String where = " WHERE (? IS NULL OR source_id=?) AND (text LIKE ? ESCAPE '\\' OR observed_speaker LIKE ? ESCAPE '\\')";
        try (var connection = open()) {
            connection.setAutoCommit(false); // Count and entries use the same read snapshot.
            long total;
            try (var count = connection.prepareStatement("SELECT count(*) FROM transcript_lines" + where)) {
                bindFilter(count, sourceId, pattern);
                try (var rows = count.executeQuery()) { total = rows.getLong(1); }
            }
            var entries = new ArrayList<TranscriptLine>();
            try (var select = connection.prepareStatement("SELECT * FROM transcript_lines" + where
                + " ORDER BY source_id,source_line LIMIT ? OFFSET ?")) {
                bindFilter(select, sourceId, pattern);
                select.setInt(5, limit);
                select.setInt(6, offset);
                try (var rows = select.executeQuery()) {
                    while (rows.next()) entries.add(readLine(rows));
                }
            }
            connection.commit();
            return new TranscriptPage(entries, total, offset, limit);
        }
    }

    private static void bindFilter(java.sql.PreparedStatement statement, String sourceId, String pattern) throws SQLException {
        statement.setString(1, sourceId);
        statement.setString(2, sourceId);
        statement.setString(3, pattern);
        statement.setString(4, pattern);
    }

    private static void requireSourceId(String sourceId) {
        if (sourceId == null || !sourceId.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid source digest");
    }

    @Override public Path createBackup(Path destination) throws Exception {
        Path target = destination.toAbsolutePath().normalize();
        if (target.equals(database)) throw new IllegalArgumentException("Backup must use a different path");
        if (Files.exists(target)) throw new IllegalArgumentException("Backup already exists");
        Files.createDirectories(target.getParent());
        // Atomically reserve the path. VACUUM accepts the zero-byte file, and a second
        // writer cannot pass the reservation even if it raced the existence check.
        try { Files.createFile(target); }
        catch (FileAlreadyExistsException exists) {
            throw new IllegalArgumentException("Backup already exists", exists);
        }
        boolean complete = false;
        try {
            try (Connection connection = open();
                 var backup = connection.prepareStatement("VACUUM INTO ?")) {
                backup.setString(1, target.toString());
                backup.execute();
            }
            complete = true;
        } finally {
            if (!complete) Files.deleteIfExists(target);
        }
        return target;
    }
}

