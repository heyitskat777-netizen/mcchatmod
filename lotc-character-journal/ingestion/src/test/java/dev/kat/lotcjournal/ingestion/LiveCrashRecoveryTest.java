package dev.kat.lotcjournal.ingestion;

import dev.kat.lotcjournal.domain.LiveEvent;
import dev.kat.lotcjournal.domain.MessageReference;
import dev.kat.lotcjournal.parsing.DialogueTextNormalizer;
import dev.kat.lotcjournal.storage.SqliteJournalStore;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static dev.kat.lotcjournal.ingestion.LiveTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
class LiveCrashRecoveryTest {
    @TempDir Path profile;

    private Process launch(String mode) throws Exception {
        var locations = new LinkedHashSet<String>();
        // Gradle's java.class.path can contain only its worker bootstrap, so include the actual test/runtime classloaders.
        for (Class<?> type : List.of(LiveCrashProcess.class, LiveCaptureService.class, LiveEvent.class,
                DialogueTextNormalizer.class, SqliteJournalStore.class, Class.forName("org.sqlite.JDBC"))) {
            locations.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            for (var loader = type.getClassLoader(); loader != null; loader = loader.getParent())
                if (loader instanceof URLClassLoader urls) for (var url : urls.getURLs())
                    if (url.getProtocol().equals("file")) locations.add(Path.of(url.toURI()).toString());
        }
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", String.join(File.pathSeparator, locations), LiveCrashProcess.class.getName(), mode, profile.toString())
            .redirectErrorStream(true).redirectOutput(profile.resolve(mode + ".process.log").toFile()).start();
    }
    private void finished(Process process, int expectedExit, String mode) throws Exception {
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Synthetic subprocess did not finish: " + mode);
            assertEquals(expectedExit, process.exitValue(), () -> {
                try { return Files.readString(profile.resolve(mode + ".process.log")); }
                catch (Exception failedRead) { return "Cannot read synthetic subprocess diagnostics"; }
            });
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
    }
    private MessageReference marker() throws Exception {
        var values = Files.readAllLines(profile.resolve("boundary.txt"));
        return new MessageReference(values.getFirst(), Long.parseLong(values.get(1)));
    }
    private long scalar(String query) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + profile.resolve("journal.sqlite"));
             var statement = connection.createStatement(); var row = statement.executeQuery(query)) { return row.getLong(1); }
    }

    @Test void forciblyKilledJvmAfterJournalForceBeforeSqlCommitReplaysExactlyOnce() throws Exception {
        Process process = launch("before-db");
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(profile.resolve("boundary.txt")) && process.isAlive() && System.nanoTime() < deadline)
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            assertTrue(Files.exists(profile.resolve("boundary.txt")), () -> {
                try { return Files.readString(profile.resolve("before-db.process.log")); }
                catch (Exception failedRead) { return "No durable boundary witness"; }
            });
            assertEquals(0, scalar("SELECT count(*) FROM live_events"));
            assertTrue(contains(profile.resolve("live"), "Synthetic Visitor waves."));
            process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertNotEquals(0, process.exitValue());
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
        var expected = marker();
        assertEquals(0, scalar("SELECT count(*) FROM live_events"));
        assertEquals(0, scalar("SELECT closed FROM live_sessions"), "Process death cannot invent a clean session ending");
        finished(launch("recover"), 0, "recover");
        var repository = store(profile.resolve("journal.sqlite"));
        var page = repository.transcript(null, null, 0, 20);
        assertEquals(1, page.total()); assertEquals(expected, MessageReference.of(page.entries().getFirst()));
        assertEquals("Synthetic Visitor waves.", page.entries().getFirst().text());
        finished(launch("recover"), 0, "recover");
        assertEquals(page, repository.transcript(null, null, 0, 20));
        assertEquals(1, scalar("SELECT count(*) FROM live_events"));
        assertEquals(0, scalar("SELECT closed FROM live_sessions"));
    }

    @Test void abruptHaltAfterAcknowledgementPreservesRepeatedEmotesAndFavoriteAcrossReplay() throws Exception {
        finished(launch("after-ack"), 23, "after-ack");
        var last = marker(); assertEquals(2, last.sourceLine());
        assertEquals(2, scalar("SELECT count(*) FROM live_events"));
        assertEquals(0, scalar("SELECT closed FROM live_sessions"));
        finished(launch("recover"), 0, "recover");
        var repository = store(profile.resolve("journal.sqlite"));
        var page = repository.transcript(null, null, 0, 20);
        assertEquals(2, page.total());
        assertEquals(List.of(1L, 2L), page.entries().stream().map(line -> line.sourceLine()).toList());
        assertEquals(page.entries().getFirst().text(), page.entries().getLast().text());
        var first = new MessageReference(last.sourceId(), 1); repository.setFavorite(first, true);
        finished(launch("recover"), 0, "recover");
        var replayed = repository.transcript(null, null, 0, 20);
        assertEquals(page.entries(), replayed.entries()); assertEquals(Set.of(first), replayed.favorites());
        assertEquals(2, scalar("SELECT count(*) FROM live_events"));
    }
}
