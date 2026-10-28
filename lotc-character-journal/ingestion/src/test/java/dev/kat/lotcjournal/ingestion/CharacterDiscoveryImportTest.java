package dev.kat.lotcjournal.ingestion;

import dev.kat.lotcjournal.domain.CharacterEvidenceKind;
import dev.kat.lotcjournal.domain.Channel;
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

class CharacterDiscoveryImportTest {
    @TempDir Path directory;

    @Test @Timeout(20) void repeatedPlainAndGzipImportsCreateOnlyEligibleObservedCharactersAndKeepDistinctRepeatedSpeech() throws Exception {
        Path archive = directory.resolve("archive.sqlite"), characters = directory.resolve("characters.sqlite");
        var store = new SqliteJournalStore(archive, characters, new DialogueTextNormalizer()); store.initialize();
        var identities = new SqliteIdentityRepository(archive, characters);
        String content = String.join("\n", List.of(
            "[12:34:56] [Render thread/INFO]: [CHAT] [Quiet] Synthetic Visitor says: hello.",
            "[12:34:56] [Render thread/INFO]: [CHAT] [Quiet] Synthetic Visitor says: hello.",
            "[12:34:57] [Render thread/INFO]: [CHAT] [Quiet] Synthetic Visitor waves.",
            "[12:34:58] [Render thread/INFO]: [CHAT] [PM] Private Synthetic -> You: DISCOVERY_PRIVATE_SENTINEL",
            "[12:34:58] [Render thread/INFO]: [CHAT] [OOC] Ooc Synthetic says: DISCOVERY_OOC_SENTINEL",
            "[12:34:59] [Render thread/INFO]: [CHAT] You are now Roleplaying as Synthetic Persona",
            "[12:35:00] [Render thread/INFO]: [CHAT] Unknown Stranger waves.",
            "[12:35:01] [Render thread/INFO]: Synthetic non-chat diagnostic")) + "\n";
        Path plain = Files.writeString(directory.resolve("synthetic.log"), content);
        Path gzip = directory.resolve("synthetic-copy.log.gz");
        try (var output = new GZIPOutputStream(Files.newOutputStream(gzip))) { output.write(content.getBytes(StandardCharsets.UTF_8)); }
        try (var importer = new LogImportService(store)) {
            var progress = importer.importFile(plain, Set.of()).get(10, TimeUnit.SECONDS);
            assertEquals(8, progress.processedLines()); assertEquals(4, progress.importedLines());
            assertEquals(2, progress.excludedLines()); assertEquals(2, progress.ignoredLines()); assertTrue(progress.complete());
            var discovered = identities.identities(""); assertEquals(1, discovered.size());
            var character = discovered.getFirst(); assertEquals("Synthetic Visitor", character.name()); assertEquals("", character.accountObservation());
            assertEquals(List.of(1L, 2L), identities.characterTranscript(character.id(), "", Channel.ROLEPLAY, CharacterEvidenceKind.SPEAKER, false, 0, 20)
                .entries().stream().map(line -> line.sourceLine()).toList());
            assertEquals(List.of(3L), identities.characterTranscript(character.id(), "", Channel.ROLEPLAY, CharacterEvidenceKind.ACTION, false, 0, 20)
                .entries().stream().map(line -> line.sourceLine()).toList());
            var before = store.transcript(null, null, 0, 20);
            assertEquals(progress, importer.importFile(plain, Set.of()).get(10, TimeUnit.SECONDS));
            assertEquals(progress, importer.importFile(gzip, Set.of()).get(10, TimeUnit.SECONDS));
            assertEquals(discovered, identities.identities("")); assertEquals(before, store.transcript(null, null, 0, 20));
            assertTrue(identities.personas().isEmpty()); assertNull(identities.activePersona());
        }
        for (Path database : List.of(archive, characters)) {
            String bytes = new String(Files.readAllBytes(database), StandardCharsets.ISO_8859_1);
            assertFalse(bytes.contains("DISCOVERY_PRIVATE_SENTINEL")); assertFalse(bytes.contains("DISCOVERY_OOC_SENTINEL"));
            assertFalse(bytes.contains("Private Synthetic")); assertFalse(bytes.contains("Ooc Synthetic"));
        }
        assertEquals(content, Files.readString(plain));
    }
}
