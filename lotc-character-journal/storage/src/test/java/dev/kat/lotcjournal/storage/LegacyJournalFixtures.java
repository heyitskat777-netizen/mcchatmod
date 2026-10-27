package dev.kat.lotcjournal.storage;

import java.sql.Connection;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reconstructs the exact historical transcript DDL, including its redundant raw column. */
final class LegacyJournalFixtures {
    static void removeLaterSchemas(Connection connection) throws Exception {
        try(var statement=connection.createStatement()) {
            for(String table:java.util.List.of("identity_decisions","identity_merges","memory_assignments","identity_state","personas","character_aliases","character_identities","live_events","live_sessions","storage_pair"))
                statement.execute("DROP TABLE IF EXISTS " + table);
        }
        // Historical single-file fixtures must not retain a modern pair binding or an unrelated stale companion.
        String url = connection.getMetaData().getURL();
        if (!url.startsWith("jdbc:sqlite:")) throw new IllegalArgumentException("Expected a synthetic SQLite fixture");
        Path archive = Path.of(url.substring("jdbc:sqlite:".length()));
        Files.deleteIfExists(archive.resolveSibling(archive.getFileName() + ".characters.sqlite"));
    }
    static void transcript(Connection connection, int version) throws Exception {
        String sql;
        try (var query = connection.createStatement(); var row = query.executeQuery("SELECT sql FROM sqlite_master WHERE name='transcript_lines'")) {
            sql = row.getString(1).replace("  text TEXT", "  raw TEXT NOT NULL CHECK(length(raw)<=65536),\n  text TEXT");
        }
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.execute("PRAGMA legacy_alter_table=ON");
            connection.setAutoCommit(false);
            try {
                removeLaterSchemas(connection);
                statement.execute("DROP TABLE IF EXISTS message_favorites");
                statement.execute("ALTER TABLE transcript_lines RENAME TO legacy_conversion");
                statement.execute(sql);
                statement.execute("INSERT INTO transcript_lines SELECT source_id,source_line,clock,'[00:00:01] [Render thread/INFO]: [CHAT] '||text,text,channel,observed_speaker FROM legacy_conversion");
                statement.execute("DROP TABLE legacy_conversion");
                statement.execute("PRAGMA user_version=" + version);
                connection.commit();
            } catch (Exception failure) { connection.rollback(); throw failure; }
            finally { connection.setAutoCommit(true); }
            statement.execute("PRAGMA legacy_alter_table=OFF"); statement.execute("PRAGMA foreign_keys=ON");
            try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (rows.next()) throw new AssertionError("Historical fixture must preserve foreign keys");
            }
        }
    }
}
