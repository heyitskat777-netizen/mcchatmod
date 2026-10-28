package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.MessageReference;
import dev.kat.lotcjournal.parsing.DialogueTextNormalizer;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static dev.kat.lotcjournal.storage.PairedDatabaseFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(35)
class PairedDatabaseCrashTest {
    @TempDir Path directory;

    @Test void actualProcessDeathBeforeCrossFileCommitRecoversBothFilesWithoutPartialUndo() throws Exception {
        var classpath = new LinkedHashSet<String>();
        for (Class<?> type : List.of(PairedDatabaseCrashProcess.class, SqliteJournalStore.class, MessageReference.class,
                DialogueTextNormalizer.class, Class.forName("org.sqlite.JDBC"))) {
            classpath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            for (var loader = type.getClassLoader(); loader != null; loader = loader.getParent())
                if (loader instanceof URLClassLoader urls) for (var url : urls.getURLs()) if (url.getProtocol().equals("file")) classpath.add(Path.of(url.toURI()).toString());
        }
        Path log = directory.resolve("child.log"), witness = directory.resolve("before-commit.txt");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
            String.join(File.pathSeparator, classpath), PairedDatabaseCrashProcess.class.getName(), directory.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(witness) && child.isAlive() && System.nanoTime() < deadline) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            assertTrue(Files.exists(witness), () -> { try { return Files.readString(log); } catch (Exception ignored) { return "Missing cross-file boundary witness"; } });
            child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS)); assertNotEquals(0, child.exitValue());
        } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
        Path archive = directory.resolve("archive.sqlite"), characters = directory.resolve("characters.sqlite");
        var store = open(archive, characters); var repository = new SqliteIdentityRepository(archive, characters);
        assertEquals(3, store.transcript(null, null, 0, 20).total()); assertEquals(Set.of(ref(1), ref(2)), store.transcript(SOURCE, null, 0, 20).favorites());
        assertNotNull(repository.assignment(ref(2), PERSONA_SECOND)); assertNotNull(repository.assignment(ref(1), PERSONA_FIRST));
        assertEquals("COMPLETE", store.batchSummary(BATCH).status()); assertEquals(0, store.removedRecords(SOURCE));
        assertEquals(1, scalar(archive, "SELECT count(*) FROM observation_owners"));
        assertEquals(2, scalar(characters, "SELECT count(*) FROM identity_decisions"));
        assertEquals(PERSONA_SECOND, repository.activePersona());
        assertEquals(1, store.undoBatch(BATCH)); assertNull(repository.assignment(ref(2), PERSONA_SECOND));
        assertEquals(Set.of(ref(1)), store.transcript(SOURCE, null, 0, 20).favorites());
        assertEquals(2, open(archive, characters).transcript(null, null, 0, 20).total());
    }
}
