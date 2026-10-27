package dev.kat.lotcjournal.ingestion;

import dev.kat.lotcjournal.domain.*;
import dev.kat.lotcjournal.parsing.DialogueTextNormalizer;
import dev.kat.lotcjournal.storage.SqliteIdentityRepository;
import dev.kat.lotcjournal.storage.SqliteJournalStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MessageTagReviewImportTest {
    private static final String SPEAKER = "40000000-0000-0000-0000-000000000001", MENTION = "40000000-0000-0000-0000-000000000002", CORRECTION = "40000000-0000-0000-0000-000000000003";
    @TempDir Path directory;

    @Test @Timeout(25) void repeatedAndRenamedGzipImportsPreserveReviewedAndRejectedCoordinatesAfterAliasReindexAndRestart() throws Exception {
        Path archive = directory.resolve("archive.sqlite"), characters = directory.resolve("characters.sqlite");
        var store = new SqliteJournalStore(archive, characters, new DialogueTextNormalizer()); store.initialize();
        var repository = new SqliteIdentityRepository(archive, characters);
        repository.saveIdentity(new CharacterIdentity(SPEAKER, "Synthetic Speaker", "", Set.of(), ""));
        repository.saveIdentity(new CharacterIdentity(MENTION, "Synthetic Mention", "", Set.of(), ""));
        repository.saveIdentity(new CharacterIdentity(CORRECTION, "Synthetic Correction", "", Set.of(), ""));
        String content = String.join("\n", List.of(
            "[12:34:56] [Render thread/INFO]: [CHAT] [Quiet] Synthetic Speaker says: hello to Synthetic Mention.",
            "[12:34:56] [Render thread/INFO]: [CHAT] [Quiet] Synthetic Speaker says: hello to Synthetic Mention.",
            "[12:34:57] [Render thread/INFO]: [CHAT] [PM] SyntheticAccount -> You: TAG_PRIVATE_SENTINEL",
            "[12:34:58] [Render thread/INFO]: [CHAT] [OOC] TAG_OOC_SENTINEL")) + "\n";
        Path plain = Files.writeString(directory.resolve("synthetic.log"), content), gzip = directory.resolve("renamed-synthetic.log.gz");
        try (var output = new GZIPOutputStream(Files.newOutputStream(gzip))) { output.write(content.getBytes(StandardCharsets.UTF_8)); }
        MessageReference first, second; List<CharacterTag> corrected; TranscriptPage before;
        try (var importer = new LogImportService(store)) {
            var imported = importer.importFile(plain, Set.of()).get(10, TimeUnit.SECONDS);
            assertEquals(4, imported.processedLines()); assertEquals(2, imported.importedLines()); assertEquals(2, imported.excludedLines());
            String source = store.sources().getFirst().id(); first = new MessageReference(source, 1); second = new MessageReference(source, 2);
            corrected = repository.saveReviewedTags(first, 0, List.of(new CharacterTag(CORRECTION, CharacterEvidenceKind.SPEAKER), new CharacterTag(MENTION, CharacterEvidenceKind.MENTION))).effectiveTags();
            repository.saveReviewedTags(second, 0, List.of()); before = store.transcript(null, null, 0, 20);
            assertEquals(imported, importer.importFile(plain, Set.of()).get(10, TimeUnit.SECONDS));
            assertEquals(imported, importer.importFile(gzip, Set.of()).get(10, TimeUnit.SECONDS));
            repository.saveIdentity(new CharacterIdentity(SPEAKER, "Synthetic Speaker", "Administrative correction", Set.of("Synthetic Mention"), ""));
            assertEquals(corrected, repository.reviewTags(first).effectiveTags()); assertEquals(1, repository.reviewTags(first).revision());
            assertTrue(repository.reviewTags(second).overridden()); assertTrue(repository.reviewTags(second).effectiveTags().isEmpty());
        }
        var restarted = new SqliteJournalStore(archive, characters, new DialogueTextNormalizer()); restarted.initialize();
        var reopenedRepository = new SqliteIdentityRepository(archive, characters);
        try (var importer = new LogImportService(restarted)) { importer.importFile(gzip, Set.of()).get(10, TimeUnit.SECONDS); }
        assertEquals(corrected, reopenedRepository.reviewTags(first).effectiveTags()); assertEquals(1, reopenedRepository.reviewTags(first).revision());
        assertTrue(reopenedRepository.reviewTags(second).overridden()); assertTrue(reopenedRepository.reviewTags(second).effectiveTags().isEmpty());
        assertEquals(1, reopenedRepository.reviewTags(second).revision()); assertEquals(before, restarted.transcript(null, null, 0, 20));
        assertEquals(1, restarted.sources().size()); assertTrue(reopenedRepository.personas().isEmpty()); assertNull(reopenedRepository.activePersona());
        assertEquals(content, Files.readString(plain));
        for (Path database : List.of(archive, characters)) {
            String bytes = new String(Files.readAllBytes(database), StandardCharsets.ISO_8859_1);
            assertFalse(bytes.contains("TAG_PRIVATE_SENTINEL")); assertFalse(bytes.contains("TAG_OOC_SENTINEL"));
        }
    }
}
