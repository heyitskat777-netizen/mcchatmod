package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Explicit decisions over immutable observations. Use only from an identity I/O worker. */
public final class SqliteIdentityRepository implements IdentityRepository {
    private final Path archive, characters;
    public SqliteIdentityRepository(Path archive) { this(archive,SplitJournalFiles.charactersFor(archive)); }
    public SqliteIdentityRepository(Path archive,Path characters) {
        this.archive=archive.toAbsolutePath().normalize(); this.characters=characters.toAbsolutePath().normalize();
    }
    SqliteIdentityRepository(String url) { this(Path.of(url.substring("jdbc:sqlite:".length()))); }
    private Connection open() throws SQLException { return SplitJournalFiles.open(archive,characters); }
    private static void uuid(String id) {
        if(id==null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid identity reference");
    }
    private static void exists(Connection c,String table,String id) throws SQLException {
        uuid(id);
        try(var q=c.prepareStatement("SELECT 1 FROM " + table + " WHERE id=?")) {
            q.setString(1,id);try(var r=q.executeQuery()) {if(!r.next()) throw new SQLException("Unknown identity or persona");}
        }
    }
    private static void messageExists(Connection c,MessageReference ref) throws SQLException {
        try(var q=c.prepareStatement("SELECT 1 FROM transcript_lines WHERE source_id=? AND source_line=?")) {
            q.setString(1,ref.sourceId());q.setLong(2,ref.sourceLine());
            try(var r=q.executeQuery()) {if(!r.next()) throw new SQLException("Message is no longer available");}
        }
    }
    private <T> T write(Operation<T> operation) throws Exception {
        try(var c=open()) {
            SplitJournalFiles.beginWrite(c);
            try {
                try(var lock=c.createStatement()) {lock.executeUpdate("UPDATE characters.identity_state SET active_persona=active_persona WHERE id=1");}
                T result=operation.run(c);c.commit();return result;
            } catch(Exception e) {c.rollback();throw e;}
        }
    }
    @Override public List<CharacterIdentity> identities(String query) throws Exception {
        String filter=pattern(query);
        try(var c=open();var q=c.prepareStatement("SELECT DISTINCT i.* FROM characters.character_identities i LEFT JOIN characters.character_aliases a ON a.identity_id=i.id WHERE i.name LIKE ? ESCAPE '\\' OR a.alias LIKE ? ESCAPE '\\' OR i.account_observation LIKE ? ESCAPE '\\' ORDER BY i.name,i.id LIMIT 1000")) {
            q.setString(1,filter);q.setString(2,filter);q.setString(3,filter);
            var result=new ArrayList<CharacterIdentity>();
            try(var r=q.executeQuery()) {while(r.next()) result.add(readIdentity(c,r));} return List.copyOf(result);
        }
    }
    static CharacterIdentity readIdentity(Connection c,ResultSet row) throws SQLException { return readIdentity(c,row,"characters"); }
    static CharacterIdentity readIdentity(Connection c,ResultSet row,String schema) throws SQLException {
        var aliases=new HashSet<String>();String id=row.getString("id");
        try(var q=c.prepareStatement("SELECT alias FROM "+schema+".character_aliases WHERE identity_id=?")) {
            q.setString(1,id);try(var r=q.executeQuery()) {while(r.next()) aliases.add(r.getString(1));}
        }
        return new CharacterIdentity(id,row.getString("name"),row.getString("notes"),aliases,row.getString("account_observation"));
    }
    @Override public void saveIdentity(CharacterIdentity value) throws Exception {
        Objects.requireNonNull(value);
        write(c -> {
            try(var q=c.prepareStatement("INSERT INTO characters.character_identities(id,name,notes,account_observation) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,notes=excluded.notes,account_observation=excluded.account_observation")) {
                q.setString(1,value.id());q.setString(2,value.name());q.setString(3,value.notes());q.setString(4,value.accountObservation());q.executeUpdate();
            }
            try(var q=c.prepareStatement("DELETE FROM characters.character_aliases WHERE identity_id=?")) {q.setString(1,value.id());q.executeUpdate();}
            try(var q=c.prepareStatement("INSERT INTO characters.character_aliases VALUES(?,?)")) {
                for(String alias:value.aliases()) {q.setString(1,value.id());q.setString(2,alias);q.addBatch();} q.executeBatch();
            } CharacterIndex.reindex(c); return null;
        });
    }
    @Override public List<Persona> personas() throws Exception {
        try(var c=open();var q=c.createStatement();var r=q.executeQuery("SELECT * FROM characters.personas ORDER BY label,id")) {
            var result=new ArrayList<Persona>();while(r.next()) result.add(new Persona(r.getString("id"),r.getString("identity_id"),r.getString("label")));return List.copyOf(result);
        }
    }
    @Override public void createPersona(Persona value) throws Exception {
        write(c -> {exists(c,"characters.character_identities",value.identityId());
            try(var q=c.prepareStatement("INSERT INTO characters.personas VALUES(?,?,?)")) {
                q.setString(1,value.id());q.setString(2,value.identityId());q.setString(3,value.label());q.executeUpdate();
            }return null;
        });
    }
    @Override public String activePersona() throws Exception {
        try(var c=open();var q=c.createStatement();var r=q.executeQuery("SELECT active_persona FROM characters.identity_state WHERE id=1")) {
            if(!r.next()) throw new SQLException("Missing persona selection state");return r.getString(1);
        }
    }
    @Override public void selectPersona(String id) throws Exception {
        write(c -> {if(id!=null) exists(c,"characters.personas",id);
            try(var q=c.prepareStatement("UPDATE characters.identity_state SET active_persona=? WHERE id=1")) {q.setString(1,id);q.executeUpdate();}return null;});
    }
    @Override public MemoryAssignment assignment(MessageReference ref,String persona) throws Exception {
        try(var c=open()) {exists(c,"characters.personas",persona);return assignment(c,ref,persona);}
    }
    private static MemoryAssignment assignment(Connection c,MessageReference ref,String persona) throws SQLException {
        try(var q=c.prepareStatement("SELECT identity_id,role FROM characters.memory_assignments WHERE persona_id=? AND source_id=? AND source_line=?")) {
            q.setString(1,persona);q.setString(2,ref.sourceId());q.setLong(3,ref.sourceLine());
            try(var r=q.executeQuery()) {return r.next()?new MemoryAssignment(ref,persona,r.getString(1),AttributionRole.valueOf(r.getString(2))):null;}
        }
    }
    @Override public void assign(MemoryAssignment value) throws Exception {
        write(c -> {exists(c,"characters.personas",value.personaId());messageExists(c,value.message());
            if(value.identityId()!=null) exists(c,"characters.character_identities",value.identityId());
            var old=assignment(c,value.message(),value.personaId());
            if(value.equals(old)) return null;
            try(var q=c.prepareStatement("INSERT INTO characters.memory_assignments VALUES(?,?,?,?,?) ON CONFLICT(persona_id,source_id,source_line) DO UPDATE SET identity_id=excluded.identity_id,role=excluded.role")) {
                q.setString(1,value.personaId());q.setString(2,value.message().sourceId());q.setLong(3,value.message().sourceLine());q.setString(4,value.identityId());q.setString(5,value.role().name());q.executeUpdate();
            }
            audit(c,"ASSIGN",value,old==null?null:old.identityId());return null;
        });
    }
    @Override public void clearAssignment(MessageReference ref,String persona) throws Exception {
        write(c -> {exists(c,"characters.personas",persona);messageExists(c,ref);var old=assignment(c,ref,persona);if(old==null)return null;
            try(var q=c.prepareStatement("DELETE FROM characters.memory_assignments WHERE persona_id=? AND source_id=? AND source_line=?")) {
                q.setString(1,persona);q.setString(2,ref.sourceId());q.setLong(3,ref.sourceLine());q.executeUpdate();
            }
            audit(c,"CLEAR",new MemoryAssignment(ref,persona,null,old.role()),old.identityId());return null;
        });
    }
    private static void audit(Connection c,String kind,MemoryAssignment value,String old) throws SQLException {
        try(var q=c.prepareStatement("INSERT INTO characters.identity_decisions VALUES(?,?,?,?,?,?,?,?,?)")) {
            q.setString(1,UUID.randomUUID().toString());q.setString(2,kind);q.setString(3,value.personaId());q.setString(4,value.message().sourceId());q.setLong(5,value.message().sourceLine());q.setString(6,old);q.setString(7,value.identityId());q.setString(8,value.role().name());q.setString(9,Instant.now().toString());q.executeUpdate();
        }
    }
    private static String pattern(String value) {
        if(value==null) value="";if(value.length()>256)throw new IllegalArgumentException("Query too long");
        return "%"+value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
    }
    @Override public TranscriptPage memoryTranscript(String persona,String query,Channel channel,boolean onlyFavorites,int offset,int limit) throws Exception {
        uuid(persona);if(offset<0 || limit<1 || limit>200)throw new IllegalArgumentException("Invalid memory page");
        if(channel==Channel.PRIVATE_MESSAGE || channel==Channel.OOC || channel==Channel.SYSTEM)throw new IllegalArgumentException("Excluded channel");
        String filter=pattern(query);
        String where=" FROM transcript_lines t JOIN characters.memory_assignments m ON m.source_id=t.source_id AND m.source_line=t.source_line WHERE m.persona_id=? AND (t.text LIKE ? ESCAPE '\\' OR t.observed_speaker LIKE ? ESCAPE '\\') AND (? IS NULL OR t.channel=?)";
        String star="EXISTS(SELECT 1 FROM message_favorites f WHERE f.source_id=t.source_id AND f.source_line=t.source_line)";
        if(onlyFavorites)where+=" AND "+star;
        try(var c=open()) {
            c.setAutoCommit(false);exists(c,"characters.personas",persona);long total;
            try(var q=c.prepareStatement("SELECT count(*)"+where)) {bind(q,persona,filter,channel);try(var r=q.executeQuery()){total=r.getLong(1);}}
            var entries=new ArrayList<TranscriptLine>();var favorites=new HashSet<MessageReference>();
            try(var q=c.prepareStatement("SELECT t.*,"+star+" AS favorite"+where+" ORDER BY t.source_id,t.source_line LIMIT ? OFFSET ?")) {
                bind(q,persona,filter,channel);q.setInt(6,limit);q.setInt(7,offset);
                try(var r=q.executeQuery()) {while(r.next()) {
                    var entry=new TranscriptLine(r.getString("source_id"),r.getLong("source_line"),r.getString("clock"),r.getString("text"),Channel.valueOf(r.getString("channel")),r.getString("observed_speaker"));
                    entries.add(entry);if(r.getBoolean("favorite"))favorites.add(MessageReference.of(entry));
                }}
            }c.commit();return new TranscriptPage(entries,total,offset,limit,favorites);
        }
    }
    private static void bind(PreparedStatement q,String persona,String filter,Channel channel) throws SQLException {
        q.setString(1,persona);q.setString(2,filter);q.setString(3,filter);q.setString(4,channel==null?null:channel.name());q.setString(5,channel==null?null:channel.name());
    }
    @Override public List<IdentityMerge> merges() throws Exception {
        try(var c=open();var q=c.createStatement();var r=q.executeQuery("SELECT * FROM characters.identity_merges ORDER BY created_at,id")) {
            var result=new ArrayList<IdentityMerge>();
            while(r.next())result.add(new IdentityMerge(r.getString("id"),r.getString("from_id"),r.getString("to_id"),r.getInt("active")==1));
            return List.copyOf(result);
        }
    }
    @Override public String mergeIdentity(String from,String to) throws Exception {
        return write(c -> {exists(c,"characters.character_identities",from);exists(c,"characters.character_identities",to);
            if(!resolve(c,from).equals(from) || resolve(c,to).equals(from))throw new IllegalArgumentException("Identity already linked or cyclic merge");
            String id=UUID.randomUUID().toString();
            try(var q=c.prepareStatement("INSERT INTO characters.identity_merges VALUES(?,?,?,1,?)")) {
                q.setString(1,id);q.setString(2,from);q.setString(3,to);q.setString(4,Instant.now().toString());q.executeUpdate();
            }return id;
        });
    }
    @Override public void undoMerge(String decision) throws Exception {
        uuid(decision);write(c -> {
            try(var q=c.prepareStatement("UPDATE characters.identity_merges SET active=0 WHERE id=?")) {q.setString(1,decision);if(q.executeUpdate()!=1)throw new SQLException("Unknown merge decision");}return null;
        });
    }
    @Override public String resolveIdentity(String identity) throws Exception {
        try(var c=open()) {exists(c,"characters.character_identities",identity);return resolve(c,identity);}
    }
    static String resolve(Connection c,String identity) throws SQLException { return resolve(c,identity,"characters"); }
    static String resolve(Connection c,String identity,String schema) throws SQLException {
        var visited=new HashSet<String>();String cursor=identity;
        while(visited.size()<10000 && visited.add(cursor)) {
            try(var q=c.prepareStatement("SELECT to_id FROM "+schema+".identity_merges WHERE from_id=? AND active=1")) {
                q.setString(1,cursor);try(var r=q.executeQuery()) {if(!r.next())return cursor;cursor=r.getString(1);if(r.next())throw new SQLException("Conflicting identity links");}
            }
        }throw new SQLException("Cyclic identity links");
    }
    @Override public TranscriptPage characterTranscript(String identity,String query,Channel channel,CharacterEvidenceKind kind,boolean favorites,int offset,int limit) throws Exception {
        try(var c=open()) {
            c.setAutoCommit(false);
            var result=CharacterIndex.transcript(c,identity,query,channel,kind,favorites,offset,limit);
            c.commit();return result;
        }
    }
    @FunctionalInterface private interface Operation<T> {T run(Connection c) throws Exception;}
}
