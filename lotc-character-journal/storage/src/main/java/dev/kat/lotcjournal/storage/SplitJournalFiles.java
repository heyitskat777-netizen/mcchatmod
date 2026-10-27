package dev.kat.lotcjournal.storage;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** One attached connection is the only transaction boundary for a native storage pair. */
final class SplitJournalFiles {
    static final String PAIR_SQL = "CREATE TABLE storage_pair (id INTEGER PRIMARY KEY CHECK(id=1),pair_uuid TEXT NOT NULL,generation TEXT NOT NULL)";
    static final int ARCHIVE_VERSION = 9, CHARACTERS_VERSION = 1;
    private SplitJournalFiles() {}
    static Path charactersFor(Path archive) { return archive.resolveSibling(archive.getFileName() + ".characters.sqlite"); }
    static Path manifestFor(Path archive) { return archive.resolveSibling(archive.getFileName() + ".pair.json"); }

    static void paths(Path archive, Path characters) throws SQLException {
        if (archive.equals(characters) || !archive.getParent().equals(characters.getParent())) throw new SQLException("Storage files must have one parent directory");
        for (Path path : java.util.List.of(archive, characters)) {
            for (Path ancestor = path; ancestor != null; ancestor = ancestor.getParent())
                if (Files.isSymbolicLink(ancestor)) throw new SQLException("Linked storage paths are not supported");
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                throw new SQLException("Storage path is not a regular file");
            try {
                if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&!Files.getFileStore(path).equals(Files.getFileStore(path.getParent())))
                    throw new SQLException("Storage pair must reside on one filesystem");
            } catch(java.io.IOException failure) {throw new SQLException("Cannot establish storage filesystem",failure);}
        }
    }
    record InitializationLock(FileChannel channel,FileLock lock) implements AutoCloseable {
        @Override public void close() throws java.io.IOException {try {lock.release();} finally {channel.close();}}
    }
    static InitializationLock initializationLock(Path archive) throws Exception {
        var channel=FileChannel.open(archive.resolveSibling(archive.getFileName()+".initialize.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try {
            var lock=channel.tryLock();
            if(lock==null) throw new SQLException("Storage initialization is already running");
            return new InitializationLock(channel,lock);
        } catch(Exception failure) {channel.close();throw failure;}
    }

    static Connection archive(Path archive) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + archive);
        try (var s = c.createStatement()) { s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000"); }
        catch (SQLException failure) { c.close(); throw failure; }
        return c;
    }
    static void attach(Connection c, Path characters) throws SQLException {
        try (var q = c.prepareStatement("ATTACH DATABASE ? AS characters")) { q.setString(1, characters.toString()); q.execute(); }
    }
    static Connection open(Path archive, Path characters) throws SQLException {
        paths(archive, characters);
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(characters, LinkOption.NOFOLLOW_LINKS))
            throw new SQLException("Both native storage files are required");
        Connection c = archive(archive);
        try {
            attach(c, characters); validatePair(c); configure(c); return c;
        } catch (SQLException | RuntimeException failure) { c.close(); throw failure; }
    }
    static void configure(Connection c) throws SQLException {
        if (!c.getAutoCommit()) throw new SQLException("Journal mode must be set outside a transaction");
        for (String schema : java.util.List.of("main", "characters")) {
            try (var s = c.createStatement(); var r = s.executeQuery("PRAGMA " + schema + ".journal_mode=DELETE")) {
                if (!r.next() || !"delete".equalsIgnoreCase(r.getString(1))) throw new SQLException("Rollback journals are required for paired commits");
            }
            try (var s = c.createStatement()) { s.execute("PRAGMA " + schema + ".synchronous=FULL"); }
            try (var s = c.createStatement(); var r = s.executeQuery("PRAGMA " + schema + ".synchronous")) {
                if (!r.next() || r.getInt(1) != 2) throw new SQLException("Full synchronization is required for paired commits");
            }
        }
    }
    static void beginWrite(Connection c) throws SQLException {
        c.setAutoCommit(false);
        // Every paired writer reserves the archive first, then touches characters.
        // A consistent order avoids an identity-edit/import lock inversion.
        try (var s=c.createStatement()) { s.executeUpdate("UPDATE main.journal_settings SET value=value WHERE key='compact_layout'"); }
    }
    static void createPair(Connection c) throws SQLException {
        String pair = UUID.randomUUID().toString(), generation = UUID.randomUUID().toString();
        try (var s = c.createStatement()) { s.execute(PAIR_SQL); s.execute(PAIR_SQL.replace("CREATE TABLE ", "CREATE TABLE characters.")); }
        for (String schema : java.util.List.of("main", "characters")) {
            try (var q = c.prepareStatement("INSERT INTO " + schema + ".storage_pair VALUES (1,?,?)")) {
                q.setString(1, pair); q.setString(2, generation); q.executeUpdate();
            }
        }
    }
    static String[] pair(Connection c, String schema) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT pair_uuid,generation FROM " + schema + ".storage_pair WHERE id=1")) {
            if (!r.next()) throw new SQLException("Missing storage pair binding");
            String pair = r.getString(1), generation = r.getString(2);
            try {
                if (!UUID.fromString(pair).toString().equals(pair) || !UUID.fromString(generation).toString().equals(generation))
                    throw new IllegalArgumentException();
            } catch (RuntimeException invalid) { throw new SQLException("Invalid storage pair binding", invalid); }
            if (r.next()) throw new SQLException("Ambiguous storage pair binding");
            return new String[] {pair, generation};
        }
    }
    static void validatePair(Connection c) throws SQLException {
        var main = pair(c, "main"); var chars = pair(c, "characters");
        if (!Objects.equals(main[0], chars[0]) || !Objects.equals(main[1], chars[1]))
            throw new SQLException("Archive and characters files are from different storage snapshots");
        try (var s = c.createStatement(); var r = s.executeQuery("PRAGMA main.user_version")) {
            if (!r.next() || r.getInt(1) != ARCHIVE_VERSION) throw new SQLException("Unsupported archive schema");
        }
        try (var s = c.createStatement(); var r = s.executeQuery("PRAGMA characters.user_version")) {
            if (!r.next() || r.getInt(1) != CHARACTERS_VERSION) throw new SQLException("Unsupported character schema");
        }
    }
    static void integrity(Connection c, String schema) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("PRAGMA " + schema + ".quick_check")) {
            if (!r.next() || !"ok".equals(r.getString(1)) || r.next()) throw new SQLException("Storage integrity check failed");
        }
        try (var s = c.createStatement(); var r = s.executeQuery("PRAGMA " + schema + ".foreign_key_check")) {
            if (r.next()) throw new SQLException("Storage reference integrity check failed");
        }
    }
    static void force(Path file) throws java.io.IOException {
        try (var c = FileChannel.open(file, StandardOpenOption.WRITE)) { c.force(true); }
    }
    static void forceDirectory(Path directory) throws java.io.IOException {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return;
        try (var c = FileChannel.open(directory, StandardOpenOption.READ)) { c.force(true); }
    }
}
