package dev.kat.lotcjournal.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.UUID;
import org.sqlite.SQLiteConnection;

/** SQLite online backups from a single transaction holding read locks on BOTH rollback-journal files. */
final class NativePairBackup {
    private NativePairBackup() {}
    static void snapshot(Connection c,String schema,Path target) throws Exception {
        int result = c.unwrap(SQLiteConnection.class).getDatabase().backup(schema,target.toString(),null);
        if(result!=0) throw new SQLException("SQLite snapshot failed with code "+result);
        SplitJournalFiles.force(target);
    }
    static void legacy(Connection c,Path target) throws Exception {
        Files.createDirectories(target.getParent()); Files.createFile(target);
        boolean complete=false;
        try {
            c.setAutoCommit(false);
            try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM main.sqlite_master")) { r.next(); }
            snapshot(c,"main",target); c.commit(); complete=true;
        } finally { if(!c.getAutoCommit()) { c.rollback();c.setAutoCommit(true); } if(!complete) Files.deleteIfExists(target); }
    }
    static Path pair(Path archive,Path characters,Path target) throws Exception {
        Path companion=SplitJournalFiles.charactersFor(target),manifest=SplitJournalFiles.manifestFor(target);
        if(target.equals(archive)||target.equals(characters)||companion.equals(archive)||companion.equals(characters))
            throw new IllegalArgumentException("Backup paths must differ from live storage");
        Files.createDirectories(target.getParent());
        SplitJournalFiles.paths(target,companion);
        if(Files.exists(manifest,LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Backup manifest already exists");
        try {Files.createFile(target);}
        catch(java.nio.file.FileAlreadyExistsException exists) {throw new IllegalArgumentException("Backup already exists",exists);}
        boolean companionReserved=false,manifestOwned=false,complete=false;
        try {
            try {Files.createFile(companion);companionReserved=true;}
            catch(java.nio.file.FileAlreadyExistsException exists) {throw new IllegalArgumentException("Backup companion already exists",exists);}
            try(var c=SplitJournalFiles.openCompatible(archive,characters)) {
                c.setAutoCommit(false);
                // The one statement obtains SHARED locks on both databases. Under DELETE
                // journals no other connection can commit either member until these
                // snapshots finish; unlocked sequential VACUUM would not provide this.
                try(var s=c.createStatement();var r=s.executeQuery("SELECT a.pair_uuid,b.pair_uuid FROM main.storage_pair a CROSS JOIN characters.storage_pair b")) {
                    if(!r.next()) throw new SQLException("Missing storage pair");
                }
                try { snapshot(c,"main",target);snapshot(c,"characters",companion);c.commit(); }
                catch(Exception failure) {c.rollback();throw failure;}
            }
            String generation=UUID.randomUUID().toString(),pair;
            try(var copy=SplitJournalFiles.openCompatible(target,companion)) {
                copy.setAutoCommit(false);
                for(String schema:java.util.List.of("main","characters")) {
                    try(var q=copy.prepareStatement("UPDATE "+schema+".storage_pair SET generation=? WHERE id=1")) {q.setString(1,generation);q.executeUpdate();}
                }
                try(var s=copy.createStatement()) {s.executeUpdate("DELETE FROM main.journal_settings WHERE key='adopted_backup_generation'");}
                copy.commit();SplitJournalFiles.validatePair(copy,true);SplitJournalFiles.integrity(copy,"main");SplitJournalFiles.integrity(copy,"characters");
                IdentitySchema.validate(copy); pair=SplitJournalFiles.pair(copy,"main")[0];
            }
            SplitJournalFiles.force(target);SplitJournalFiles.force(companion);
            String json="{\"version\":1,\"pair\":\""+pair+"\",\"generation\":\""+generation+"\",\"archiveSha256\":\""+sha256(target)+"\",\"charactersSha256\":\""+sha256(companion)+"\"}\n";
            Files.createFile(manifest);manifestOwned=true;
            Files.writeString(manifest,json,StandardCharsets.UTF_8,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE);
            SplitJournalFiles.force(manifest);SplitJournalFiles.forceDirectory(target.getParent());complete=true;return target;
        } finally {
            if(!complete) {Files.deleteIfExists(target);if(companionReserved)Files.deleteIfExists(companion);if(manifestOwned)Files.deleteIfExists(manifest);}
        }
    }
    static void verifyForAdoption(Connection c,Path archive,Path characters) throws Exception {
        Path manifest=SplitJournalFiles.manifestFor(archive);
        if(!Files.exists(manifest,LinkOption.NOFOLLOW_LINKS)) return;
        if(!Files.isRegularFile(manifest,LinkOption.NOFOLLOW_LINKS)||Files.size(manifest)>1024) throw new SQLException("Invalid paired backup manifest");
        String generation=SplitJournalFiles.pair(c,"main")[1];
        try(var s=c.createStatement();var r=s.executeQuery("SELECT value FROM main.journal_settings WHERE key='adopted_backup_generation'")) {
            if(r.next()&&generation.equals(r.getString(1))) return;
        }
        String json=Files.readString(manifest,StandardCharsets.UTF_8);
        String pair=SplitJournalFiles.pair(c,"main")[0];
        String expected="{\"version\":1,\"pair\":\""+pair+"\",\"generation\":\""+generation+"\",\"archiveSha256\":\""+sha256(archive)+"\",\"charactersSha256\":\""+sha256(characters)+"\"}\n";
        if(!json.equals(expected)) throw new SQLException("Paired backup is incomplete or changed");
        try(var q=c.prepareStatement("INSERT INTO main.journal_settings(key,value) VALUES ('adopted_backup_generation',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            q.setString(1,generation);q.executeUpdate();
        }
    }
    private static String sha256(Path path) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(path)) {byte[] bytes=new byte[8192];for(int n;(n=input.read(bytes))!=-1;)digest.update(bytes,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
}
