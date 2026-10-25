package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.BackupService;
import dev.kat.lotcjournal.domain.JournalSettings;
import dev.kat.lotcjournal.domain.SettingsRepository;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** SQLite adapter. Short-lived connections are used only by the application I/O worker. */
public final class SqliteJournalStore implements SettingsRepository, BackupService {
    private static final int SCHEMA_VERSION = 1;
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
            if (version > SCHEMA_VERSION) throw new SQLException("Database requires a newer journal");
            if (version == 0) {
                try (var tables = statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")) {
                    if (tables.next()) throw new SQLException("Refusing to adopt an unrelated database");
                }
                connection.setAutoCommit(false);
                try {
                    statement.execute("CREATE TABLE journal_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                    statement.execute("INSERT INTO journal_settings VALUES ('compact_layout','false')");
                    statement.execute("PRAGMA user_version=1");
                    connection.commit();
                } catch (SQLException error) {
                    connection.rollback();
                    throw error;
                } finally { connection.setAutoCommit(true); }
            }
            validateSchema(connection);
            try (var result = statement.executeQuery("PRAGMA quick_check")) {
                if (!result.next() || !"ok".equals(result.getString(1)))
                    throw new SQLException("Journal integrity check failed");
            }
            statement.execute("PRAGMA journal_mode=WAL");
        }
    }

    /** A version number alone does not establish that this database belongs to us. */
    private void validateSchema(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            try (var objects = statement.executeQuery(
                "SELECT name, type FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")) {
                if (!objects.next() || !"journal_settings".equals(objects.getString(1))
                    || !"table".equals(objects.getString(2)) || objects.next())
                    throw new SQLException("Refusing an unrelated journal schema");
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

