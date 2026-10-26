package dev.kat.lotcjournal.ingestion;

import dev.kat.lotcjournal.domain.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RoleplayIngestionTest {
    @TempDir Path directory;
    private static String line(String body) { return "[12:34:56] [Render thread/INFO]: [CHAT]" + body + "\n"; }
    @Test void trustedAndUnknownDialogueRemainSeparateWhileOperationalNoticesAreIgnored() throws Exception {
        String body = "Synthetic Visitor waves.";
        String content = line("[Quiet]" + body) + line("[Quiet]" + body) + line(body)
            + line("The key did not fit!") + line("Your Soulstone will recharge in 5 seconds.")
            + line("[Quiet][PM] PRIVATE_SENTINEL") + line("[OOC] OOC_SENTINEL") + "not a chat log\n";
        var repository = new LogImportServiceTest.MemoryCapture();
        Path path = Files.writeString(directory.resolve("mixed.log"), content);
        try (var service = new LogImportService(repository, 2, 4096, 1024, 1)) {
            var imported = service.importFile(path, Set.of()).get(5, TimeUnit.SECONDS);
            assertEquals(8, imported.processedLines());
            assertEquals(3, imported.importedLines());
            assertEquals(2, imported.excludedLines());
            assertEquals(3, imported.ignoredLines());
            assertEquals(List.of(Channel.ROLEPLAY, Channel.ROLEPLAY, Channel.UNKNOWN),
                repository.received.stream().map(TranscriptLine::channel).toList());
            assertEquals(List.of(1L, 2L, 3L), repository.received.stream().map(TranscriptLine::sourceLine).toList());
            assertTrue(repository.received.stream().allMatch(record -> body.equals(record.text())));
            assertTrue(repository.received.stream().allMatch(record -> record.observedSpeaker() == null));
            assertEquals(content, Files.readString(path));
        }
    }
    @Test void policyFourRefusesIncompletePolicyThreeWithoutChangingCheckpoint() throws Exception {
        var repository = new LogImportServiceTest.MemoryCapture();
        Path path = Files.writeString(directory.resolve("partial-policy-three.log"), line("[Quiet]Synthetic Visitor waves."));
        String id = hash(Files.readAllBytes(path));
        repository.beginSource(new ImportSource(id, "partial-policy-three.log", "plain", 1,
            hash("parser-policy-3\n".getBytes(StandardCharsets.UTF_8))));
        try (var service = new LogImportService(repository)) {
            assertThrows(java.util.concurrent.ExecutionException.class, () -> service.importFile(path, Set.of()).get(5, TimeUnit.SECONDS));
            assertEquals(0, repository.progress(id).processedLines());
            assertTrue(repository.received.isEmpty());
        }
    }
    @Test void policyThreeRefusesAnIncompletePolicyTwoSourceWithoutWritingRecords() throws Exception {
        var repository = new LogImportServiceTest.MemoryCapture();
        Path path = Files.writeString(directory.resolve("partial-policy-two.log"), line("[Quiet]Synthetic Visitor waves."));
        String id = hash(Files.readAllBytes(path));
        String oldPolicy = hash("parser-policy-2\n".getBytes(StandardCharsets.UTF_8));
        repository.beginSource(new ImportSource(id, "partial-policy-two.log", "plain", 1, oldPolicy));
        try (var service = new LogImportService(repository)) {
            assertNotEquals(oldPolicy, LogImportService.policyFingerprint(Set.of()));
            var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> service.importFile(path, Set.of()).get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, failed.getCause());
            assertEquals(0, repository.progress(id).processedLines());
            assertTrue(repository.received.isEmpty());
        }
    }
    @Test void aCompletedOldSourceRetainsItsClassificationsInsteadOfBeingReinterpreted() throws Exception {
        var repository = new LogImportServiceTest.MemoryCapture();
        String body = "Synthetic Visitor waves.";
        Path path = Files.writeString(directory.resolve("complete-policy-two.log"), line("[Quiet]" + body));
        String id = hash(Files.readAllBytes(path));
        String oldPolicy = hash("parser-policy-2\n".getBytes(StandardCharsets.UTF_8));
        repository.beginSource(new ImportSource(id, "complete-policy-two.log", "plain", 1, oldPolicy));
        repository.appendBatch(id, 0, 1, List.of(new TranscriptLine(id, 1, "12:34:56", body, Channel.UNKNOWN, null)), 0, 0, true);
        try (var service = new LogImportService(repository)) {
            var complete = service.importFile(path, Set.of()).get(5, TimeUnit.SECONDS);
            assertTrue(complete.complete());
            assertEquals(1, repository.received.size());
            assertEquals(Channel.UNKNOWN, repository.received.getFirst().channel());
            assertNull(repository.received.getFirst().observedSpeaker());
            assertEquals(oldPolicy, repository.sources().getFirst().policyFingerprint());
        }
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
