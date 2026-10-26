package dev.kat.lotcjournal.storage;
import dev.kat.lotcjournal.domain.*;
import dev.kat.lotcjournal.parsing.DialogueTextNormalizer;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class TitledNoticeUpgradeTest {
    @TempDir Path directory;
    @Test void v6UnknownTitledNoticeCleanupIsBackedUpAndRemovesOnlyItsFavoriteAtomically() throws Exception {
        Path db=directory.resolve("journal.sqlite");String id="a".repeat(64);
        var old=new SqliteJournalStore(db,(text,channel)->new DialogueCleanup(text,DialogueCleanup.Disposition.DIALOGUE));old.initialize();
        old.beginSource(new ImportSource(id,"synthetic.log","plain",2));
        old.appendBatch(id,0,2,List.of(
            new TranscriptLine(id,1,"12:00:00","Synthetic Name, the Wanderer is a Persona of ExampleAccount",Channel.UNKNOWN,null),
            new TranscriptLine(id,2,"12:00:00","Synthetic Speaker waits.",Channel.ROLEPLAY,null)),0,0,true);
        old.setFavorite(new MessageReference(id,1),true);old.setFavorite(new MessageReference(id,2),true);
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement()) {LegacyJournalFixtures.removeLaterSchemas(c);s.execute("PRAGMA user_version=6");}
        var current=new SqliteJournalStore(db,new DialogueTextNormalizer());current.initialize();
        assertEquals(List.of(2L),current.transcript(id,null,0,20).entries().stream().map(TranscriptLine::sourceLine).toList());
        assertEquals(Set.of(new MessageReference(id,2)),current.transcript(id,null,0,20).favorites());
        assertEquals(2,current.progress(id).processedLines());assertEquals(1,current.progress(id).importedLines());assertEquals(1,current.progress(id).ignoredLines());
        List<Path> backups;try(var paths=Files.list(directory.resolve("backups"))){backups=paths.toList();}assertEquals(1,backups.size());
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+backups.getFirst());var q=c.createStatement();var r=q.executeQuery("SELECT count(*) FROM message_favorites")){assertEquals(2,r.getInt(1));}
    }
}
