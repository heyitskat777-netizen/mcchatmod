package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.BackupService;
import dev.kat.lotcjournal.domain.JournalSettings;
import dev.kat.lotcjournal.domain.SettingsRepository;
import java.nio.file.Files;
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
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
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
            try (var result = statement.executeQuery("PRAGMA quick_check")) {
                if (!result.next() || !"ok".equals(result.getString(1)))
                    throw new SQLException("Journal integrity check failed");
            }
            statement.execute("PRAGMA journal_mode=WAL");
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
        Path target = destination.toAbsolutePath();
        if (target.equals(database)) throw new IllegalArgumentException("Backup must use a different path");
        if (Files.exists(target)) throw new IllegalArgumentException("Backup already exists");
        Files.createDirectories(target.getParent());
        try (Connection connection = open();
             var backup = connection.prepareStatement("VACUUM INTO ?")) {
            backup.setString(1, target.toString());
            backup.execute();
        }
        return target;
    }
}

