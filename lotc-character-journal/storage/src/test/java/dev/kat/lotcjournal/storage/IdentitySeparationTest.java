package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static dev.kat.lotcjournal.storage.PairedDatabaseFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class IdentitySeparationTest {
    @TempDir Path directory;
    private Path archive() { return directory.resolve("archive.sqlite"); }
    private Path characters() { return directory.resolve("characters.sqlite"); }
    private SqliteIdentityRepository repository() { return new SqliteIdentityRepository(archive(), characters()); }
    private static String id() { return UUID.randomUUID().toString(); }
    private SqliteIdentityRepository fresh(String... identities) throws Exception {
        open(archive(), characters()); var repository = repository();
        for (int index = 0; index < identities.length; index++)
            repository.saveIdentity(new CharacterIdentity(identities[index], "Synthetic Identity " + index, "Manual note", Set.of("Shared Alias"), ""));
        return repository;
    }
    private void sql(Path path, String command) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path); var s = c.createStatement()) { s.execute(command); }
    }

    @Test void unorderedDenialIsIdempotentPersistentAndReversibleWithoutImplicitlyMerging() throws Exception {
        String first = id(), second = id(); var repository = fresh(first, second);
        String decision = repository.denyIdentityLink(first, second);
        assertEquals(decision, repository.denyIdentityLink(second, first)); assertEquals(decision, repository.denyIdentityLink(first, second));
        assertEquals(1, repository.separations().size());
        var separation = repository.separations().getFirst(); assertEquals(decision, separation.id()); assertTrue(separation.active());
        assertEquals(Set.of(first, second), Set.of(separation.firstIdentity(), separation.secondIdentity()));
        assertThrows(IllegalArgumentException.class, () -> repository.mergeIdentity(first, second));
        assertThrows(IllegalArgumentException.class, () -> repository.mergeIdentity(second, first));
        open(archive(), characters()); assertEquals(List.of(separation), repository().separations());
        repository().undoSeparation(decision); repository().undoSeparation(decision);
        assertFalse(repository().separations().getFirst().active());
        assertEquals(first, repository().resolveIdentity(first)); assertEquals(second, repository().resolveIdentity(second));
        String merge = repository().mergeIdentity(first, second); assertEquals(second, repository().resolveIdentity(first));
        repository().undoMerge(merge);
        String newDecision = repository().denyIdentityLink(second, first);
        assertNotEquals(decision, newDecision, "Re-denying after undo retains the old decision history");
        assertEquals(2, repository().separations().size()); assertEquals(1, repository().separations().stream().filter(IdentitySeparation::active).count());
    }

    @Test void denialBlocksBridgesBetweenResolvedComponentsInBothDirectionsAndTracksLinkUndo() throws Exception {
        String first = id(), second = id(), left = id(), right = id(); var repository = fresh(first, second, left, right);
        repository.denyIdentityLink(first, second);
        String firstLink = repository.mergeIdentity(first, left), secondLink = repository.mergeIdentity(second, right);
        assertThrows(IllegalArgumentException.class, () -> repository.mergeIdentity(left, right));
        assertThrows(IllegalArgumentException.class, () -> repository.mergeIdentity(right, left));
        assertEquals(left, repository.resolveIdentity(first)); assertEquals(right, repository.resolveIdentity(second));
        repository.undoMerge(firstLink);
        String unrelated = repository.mergeIdentity(left, right); // left no longer represents the denied first identity.
        assertEquals(right, repository.resolveIdentity(left));
        assertThrows(IllegalArgumentException.class, () -> repository.mergeIdentity(first, left));
        repository.undoMerge(unrelated); repository.undoMerge(secondLink);
        assertThrows(IllegalArgumentException.class, () -> repository.mergeIdentity(first, second));
        assertEquals(first, repository.resolveIdentity(first)); assertEquals(second, repository.resolveIdentity(second));
    }

    @Test void selfMissingAndAlreadyLinkedIdentityDecisionsAreRejectedWithoutChangingHistory() throws Exception {
        String first = id(), second = id(), missing = id(); var repository = fresh(first, second);
        assertThrows(IllegalArgumentException.class, () -> repository.denyIdentityLink(first, first));
        assertThrows(SQLException.class, () -> repository.denyIdentityLink(first, missing));
        assertThrows(SQLException.class, () -> repository.denyIdentityLink(missing, second));
        assertThrows(IllegalArgumentException.class, () -> repository.denyIdentityLink("not-an-id", second));
        assertTrue(repository.separations().isEmpty());
        String link = repository.mergeIdentity(first, second);
        assertThrows(IllegalArgumentException.class, () -> repository.denyIdentityLink(first, second));
        assertThrows(IllegalArgumentException.class, () -> repository.denyIdentityLink(second, first));
        assertTrue(repository.separations().isEmpty()); assertEquals(second, repository.resolveIdentity(first));
        repository.undoMerge(link); String decision = repository.denyIdentityLink(first, second);
        assertThrows(SQLException.class, () -> repository.undoSeparation(missing));
        assertThrows(IllegalArgumentException.class, () -> repository.undoSeparation("not-an-id"));
        assertEquals(decision, repository.separations().getFirst().id()); assertTrue(repository.separations().getFirst().active());
    }

    @Test void aliasReindexAndDenialNeverRewriteMessagesFavoritesOrExplicitPersonaKnowledge() throws Exception {
        var store = seed(archive(), characters()); var repository = repository(); repository.undoMerge(repository.merges().getFirst().id());
        var before = store.transcript(null, null, 0, 20); var identities = repository.identities(""); var personas = repository.personas();
        var firstMemory = repository.assignment(ref(1), PERSONA_FIRST); var secondMemory = repository.assignment(ref(2), PERSONA_SECOND);
        var firstPage = repository.memoryTranscript(PERSONA_FIRST, "", null, false, 0, 20);
        var secondPage = repository.memoryTranscript(PERSONA_SECOND, "", null, false, 0, 20);
        String decision = repository.denyIdentityLink(FIRST, SECOND);
        var first = identities.stream().filter(value -> value.id().equals(FIRST)).findFirst().orElseThrow();
        repository.saveIdentity(new CharacterIdentity(first.id(), first.name(), first.notes(), Set.of("Replacement Alias", "Shared Alias"), first.accountObservation()));
        assertThrows(IllegalArgumentException.class, () -> repository.mergeIdentity(FIRST, SECOND));
        assertEquals(decision, repository.separations().getFirst().id()); assertEquals(personas, repository.personas());
        assertEquals(PERSONA_SECOND, repository.activePersona()); assertEquals(firstMemory, repository.assignment(ref(1), PERSONA_FIRST));
        assertEquals(secondMemory, repository.assignment(ref(2), PERSONA_SECOND)); assertEquals(before, store.transcript(null, null, 0, 20));
        assertEquals(firstPage, repository.memoryTranscript(PERSONA_FIRST, "", null, false, 0, 20));
        assertEquals(secondPage, repository.memoryTranscript(PERSONA_SECOND, "", null, false, 0, 20));
        assertEquals(2, scalar(characters(), "SELECT count(*) FROM identity_decisions"));
        assertEquals(0, scalar(characters(), "SELECT count(*) FROM pragma_table_info('identity_separations') WHERE name IN ('text','raw','dialogue')"));
    }

    @Test void pairedBackupRestoresActiveAndUndoneDenialsIndependentlyOfLaterLiveEdits() throws Exception {
        String first = id(), second = id(), third = id(), fourth = id(); var repository = fresh(first, second, third, fourth);
        String active = repository.denyIdentityLink(first, second); String undone = repository.denyIdentityLink(third, fourth); repository.undoSeparation(undone);
        var expected = repository.separations();
        Path backup = open(archive(), characters()).createBackup(directory.resolve("backups/denials.sqlite"));
        repository.undoSeparation(active); repository.mergeIdentity(first, second);
        var restored = new SqliteJournalStore(backup, new dev.kat.lotcjournal.parsing.DialogueTextNormalizer()); restored.initialize();
        var restoredRepository = new SqliteIdentityRepository(backup); assertEquals(expected, restoredRepository.separations());
        assertThrows(IllegalArgumentException.class, () -> restoredRepository.mergeIdentity(first, second));
        assertEquals(first, restoredRepository.resolveIdentity(first)); assertEquals(second, restoredRepository.resolveIdentity(second));
        assertTrue(restoredRepository.personas().isEmpty()); assertNull(restoredRepository.activePersona());
    }

    @Test void genuineCharacterSchemaOneUpgradesWithAPreUpgradePairBackupAndPreservesEveryExistingRecord() throws Exception {
        var store = seed(archive(), characters()); var repository = repository();
        var before = store.transcript(null, null, 0, 20); var identities = repository.identities(""); var personas = repository.personas();
        var merges = repository.merges(); var progress = store.progress(SOURCE); var sources = store.sources();
        characterVersion(characters(), 1);
        assertEquals(1, scalar(characters(), "PRAGMA user_version"));
        var upgraded = open(archive(), characters()); var upgradedRepository = repository();
        assertEquals(9, scalar(archive(), "PRAGMA user_version")); assertEquals(3, scalar(characters(), "PRAGMA user_version"));
        assertEquals(before, upgraded.transcript(null, null, 0, 20)); assertEquals(identities, upgradedRepository.identities(""));
        assertEquals(personas, upgradedRepository.personas()); assertEquals(merges, upgradedRepository.merges());
        assertEquals(progress, upgraded.progress(SOURCE)); assertEquals(sources, upgraded.sources());
        assertEquals(PERSONA_SECOND, upgradedRepository.activePersona()); assertNotNull(upgradedRepository.assignment(ref(2), PERSONA_SECOND));
        assertTrue(upgradedRepository.separations().isEmpty());
        List<Path> backups;
        try (var files = Files.list(directory.resolve("backups"))) { backups = files.filter(path -> path.getFileName().toString().endsWith(".sqlite") && !path.getFileName().toString().endsWith(".characters.sqlite")).toList(); }
        assertEquals(1, backups.size()); Path backup = backups.getFirst(), backupCharacters = backup.resolveSibling(backup.getFileName() + ".characters.sqlite");
        assertTrue(Files.isRegularFile(backup.resolveSibling(backup.getFileName() + ".pair.json")));
        assertEquals(9, scalar(backup, "PRAGMA user_version")); assertEquals(1, scalar(backupCharacters, "PRAGMA user_version"));
        assertFalse(tables(backupCharacters).contains("identity_separations"));
        assertEquals(2, scalar(backupCharacters, "SELECT count(*) FROM memory_assignments"));
        assertEquals(2, scalar(backupCharacters, "SELECT count(*) FROM identity_decisions"));
        assertEquals(1, scalar(backupCharacters, "SELECT count(*) FROM identity_merges WHERE active=1"));
        assertEquals(2, scalar(backup, "SELECT count(*) FROM message_favorites"));
        open(archive(), characters());
        try (var files = Files.list(directory.resolve("backups"))) { assertEquals(3, files.count(), "One complete archive/character/manifest snapshot, not another upgrade"); }
    }

    @Test void failedUpgradeBackupLeavesBothOriginalMembersAtTheOldSchemaWithoutPartialDenialState() throws Exception {
        seed(archive(), characters()); characterVersion(characters(), 1);
        byte[] beforeArchive = Files.readAllBytes(archive()), beforeCharacters = Files.readAllBytes(characters());
        Path obstruction = Files.writeString(directory.resolve("backups"), "synthetic obstruction");
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> open(archive(), characters()));
        assertArrayEquals(beforeArchive, Files.readAllBytes(archive())); assertArrayEquals(beforeCharacters, Files.readAllBytes(characters()));
        assertEquals(1, scalar(characters(), "PRAGMA user_version")); assertFalse(tables(characters()).contains("identity_separations"));
        Files.delete(obstruction); open(archive(), characters()); assertEquals(3, scalar(characters(), "PRAGMA user_version"));
    }

    @Test void failedDenialWriteRollsBackWithoutAffectingOtherDecisions() throws Exception {
        String first = id(), second = id(); var repository = fresh(first, second);
        sql(characters(), "CREATE TRIGGER synthetic_denial_failure BEFORE INSERT ON identity_separations BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        try {
            assertThrows(SQLException.class, () -> repository.denyIdentityLink(first, second));
            assertTrue(repository.separations().isEmpty()); assertTrue(repository.merges().isEmpty());
            assertEquals(first, repository.resolveIdentity(first)); assertEquals(second, repository.resolveIdentity(second));
        } finally { sql(characters(), "DROP TRIGGER synthetic_denial_failure"); }
        assertNotNull(repository.denyIdentityLink(first, second));
    }

    @Test void previousCharacterSchemaBackupRestoresAndUpgradesWithoutChangingTheBackupTrio() throws Exception {
        var source = seed(archive(), characters());
        var original = source.transcript(null, null, 0, 20);
        characterVersion(characters(), 1);
        open(archive(), characters());
        Path backup;
        try (var files = Files.list(directory.resolve("backups"))) {
            backup = files.filter(path -> path.getFileName().toString().endsWith(".sqlite") && !path.getFileName().toString().endsWith(".characters.sqlite")).findFirst().orElseThrow();
        }
        Path companion = backup.resolveSibling(backup.getFileName()+".characters.sqlite"), manifest = backup.resolveSibling(backup.getFileName()+".pair.json");
        byte[] savedArchive = Files.readAllBytes(backup), savedCharacters = Files.readAllBytes(companion), savedManifest = Files.readAllBytes(manifest);
        Path profile = Files.createDirectory(directory.resolve("restored")), restoredArchive = profile.resolve("archive.sqlite"), restoredCharacters = profile.resolve("characters.sqlite");
        Files.copy(backup, restoredArchive); Files.copy(companion, restoredCharacters); Files.copy(manifest, profile.resolve("archive.sqlite.pair.json"));
        var restored = open(restoredArchive, restoredCharacters);
        var identities = new SqliteIdentityRepository(restoredArchive, restoredCharacters);
        assertEquals(original, restored.transcript(null, null, 0, 20));
        assertEquals(3, scalar(restoredCharacters, "PRAGMA user_version"));
        assertNotNull(identities.assignment(ref(2), PERSONA_SECOND)); assertEquals(PERSONA_SECOND, identities.activePersona());
        identities.undoMerge(identities.merges().getFirst().id()); identities.denyIdentityLink(FIRST, SECOND);
        open(restoredArchive, restoredCharacters);
        assertThrows(IllegalArgumentException.class, () -> identities.mergeIdentity(FIRST, SECOND));
        assertArrayEquals(savedArchive, Files.readAllBytes(backup)); assertArrayEquals(savedCharacters, Files.readAllBytes(companion)); assertArrayEquals(savedManifest, Files.readAllBytes(manifest));
    }

    @Test void startupRejectsExternallyContradictoryLinksAndDenialsWithoutMutatingEitherMember() throws Exception {
        String first = id(), second = id(); var repository = fresh(first, second);
        repository.denyIdentityLink(first, second);
        sql(characters(), "INSERT INTO identity_merges VALUES('"+id()+"','"+first+"','"+second+"',1,'2026-10-27T12:00:00Z')");
        byte[] oldArchive = Files.readAllBytes(archive()), oldCharacters = Files.readAllBytes(characters());
        assertThrows(SQLException.class, () -> open(archive(), characters()));
        assertArrayEquals(oldArchive, Files.readAllBytes(archive())); assertArrayEquals(oldCharacters, Files.readAllBytes(characters()));
    }

    private record Attempt(String decision, Exception failure) {}
    private static Attempt attempt(Callable<String> action) { try { return new Attempt(action.call(), null); } catch (Exception failure) { return new Attempt(null, failure); } }
    @Test @Timeout(20) void concurrentDenialAndMergeSerializeToOneConsistentDecision() throws Exception {
        String first = id(), second = id(); var repository = fresh(first, second); var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var denial = workers.submit(() -> { release.await(); return attempt(() -> repository.denyIdentityLink(first, second)); });
            var merge = workers.submit(() -> { release.await(); return attempt(() -> repository.mergeIdentity(first, second)); });
            release.countDown(); var denied = denial.get(10, TimeUnit.SECONDS); var linked = merge.get(10, TimeUnit.SECONDS);
            assertNotEquals(denied.failure() == null, linked.failure() == null, "Exactly one mutually exclusive decision must commit");
            if (denied.failure() == null) {
                assertInstanceOf(IllegalArgumentException.class, linked.failure());
                assertEquals(1, repository.separations().stream().filter(IdentitySeparation::active).count()); assertTrue(repository.merges().isEmpty());
                assertEquals(first, repository.resolveIdentity(first));
            } else {
                assertInstanceOf(IllegalArgumentException.class, denied.failure());
                assertTrue(repository.separations().isEmpty()); assertEquals(1, repository.merges().stream().filter(IdentityMerge::active).count());
                assertEquals(second, repository.resolveIdentity(first));
            }
            open(archive(), characters());
        } finally { release.countDown(); }
    }
}
