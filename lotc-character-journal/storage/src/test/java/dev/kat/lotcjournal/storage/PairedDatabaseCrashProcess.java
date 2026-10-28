package dev.kat.lotcjournal.storage;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/** Test-only driver interception is confined to this child JVM, never the test worker or production. */
public final class PairedDatabaseCrashProcess {
    private PairedDatabaseCrashProcess() {}
    public static void main(String[] arguments) throws Exception {
        Path profile = Path.of(arguments[0]), archive = profile.resolve("archive.sqlite"), characters = profile.resolve("characters.sqlite");
        var store = PairedDatabaseFixtures.seed(archive, characters);
        Driver delegate = new org.sqlite.JDBC();
        var drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) { var driver = drivers.nextElement(); if (driver.acceptsURL("jdbc:sqlite:" + archive)) DriverManager.deregisterDriver(driver); }
        DriverManager.registerDriver(new Driver() {
            @Override public Connection connect(String url, Properties info) throws SQLException {
                if (!acceptsURL(url)) return null;
                Connection connection = delegate.connect(url, info);
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                    if (method.getName().equals("commit") && !connection.getAutoCommit()
                        && value(connection, "SELECT count(*) FROM main.transcript_lines WHERE source_id='" + PairedDatabaseFixtures.SOURCE + "' AND source_line=2") == 0
                        && value(connection, "SELECT count(*) FROM characters.memory_assignments WHERE source_id='" + PairedDatabaseFixtures.SOURCE + "' AND source_line=2") == 0) {
                        if (value(connection, "SELECT count(*) FROM main.removed_observations") != 1
                            || value(connection, "SELECT count(*) FROM main.message_favorites WHERE source_line=2") != 0)
                            throw new AssertionError("Boundary is not after all coordinated undo writes");
                        for (String schema : java.util.List.of("main", "characters")) {
                            try (var s = connection.createStatement(); var r = s.executeQuery("PRAGMA " + schema + ".journal_mode")) {
                                if (!r.next() || !"delete".equals(r.getString(1))) throw new AssertionError("Paired crash test requires rollback journals");
                            }
                            if (value(connection, "PRAGMA " + schema + ".synchronous") != 2) throw new AssertionError("Paired commit requires FULL sync");
                        }
                        Path pending = profile.resolve("before-commit.pending");
                        Files.writeString(pending, "both database changes observed; commit not called\n");
                        Files.move(pending, profile.resolve("before-commit.txt"), StandardCopyOption.ATOMIC_MOVE);
                        new CountDownLatch(1).await();
                        throw new AssertionError("Parent must kill this child before commit");
                    }
                    try { return method.invoke(connection, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public boolean acceptsURL(String url) throws SQLException { return delegate.acceptsURL(url); }
            @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException { return delegate.getPropertyInfo(url, info); }
            @Override public int getMajorVersion() { return delegate.getMajorVersion(); }
            @Override public int getMinorVersion() { return delegate.getMinorVersion(); }
            @Override public boolean jdbcCompliant() { return delegate.jdbcCompliant(); }
            @Override public Logger getParentLogger() { return Logger.getLogger("synthetic-paired-crash"); }
        });
        store.undoBatch(PairedDatabaseFixtures.BATCH);
        throw new AssertionError("Expected intercepted cross-file commit");
    }
    private static long value(Connection connection, String query) throws SQLException {
        try (var s = connection.createStatement(); var r = s.executeQuery(query)) { if (!r.next()) throw new SQLException("Missing synthetic boundary query"); return r.getLong(1); }
    }
}
