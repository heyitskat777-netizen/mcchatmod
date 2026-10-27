package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Rebuildable observations about names. Never writes persona knowledge or source dialogue. */
final class CharacterIndex {
    static final Map<String,String> TABLES = Map.of(
        "character_evidence", "CREATE TABLE character_evidence (identity_id TEXT NOT NULL REFERENCES character_identities(id),source_id TEXT NOT NULL,source_line INTEGER NOT NULL,kind TEXT NOT NULL CHECK(kind IN ('SPEAKER','ACTION','MENTION')),matched_name TEXT NOT NULL,PRIMARY KEY(identity_id,source_id,source_line,kind))",
        "character_discoveries", "CREATE TABLE character_discoveries (name_key TEXT PRIMARY KEY NOT NULL,identity_id TEXT NOT NULL REFERENCES character_identities(id))"
    );
    static final Map<String,String> INDEXES = Map.of("character_evidence_by_message",
        "CREATE INDEX character_evidence_by_message ON character_evidence(source_id,source_line)");
    static void create(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            for (String ddl : TABLES.values()) s.execute(ddl.replace("CREATE TABLE ", "CREATE TABLE characters."));
            for (String ddl : INDEXES.values()) s.execute(ddl.replace("CREATE INDEX ", "CREATE INDEX characters."));
        }
    }
    static void validate(Connection c) throws SQLException {
        try (var s=c.createStatement(); var r=s.executeQuery("SELECT 1 FROM characters.character_evidence e LEFT JOIN main.transcript_lines t ON t.source_id=e.source_id AND t.source_line=e.source_line WHERE t.source_id IS NULL LIMIT 1")) {
            if(r.next()) throw new SQLException("Orphan character evidence");
        }
    }
    private static final class Names {
        final Map<String,Set<String>> ids = new HashMap<>();
        final Map<String,String> labels = new HashMap<>();
        void add(String name,String id) {
            String key=CharacterNameMatcher.key(name);
            ids.computeIfAbsent(key,ignored -> new HashSet<>()).add(id); labels.putIfAbsent(key,name);
        }
        String unique(String name) {
            var candidates=ids.get(CharacterNameMatcher.key(name));
            return candidates!=null && candidates.size()==1 ? candidates.iterator().next() : null;
        }
    }
    private static Names names(Connection c) throws SQLException {
        var names=new Names();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT id,name FROM characters.character_identities UNION ALL SELECT identity_id,alias FROM characters.character_aliases UNION ALL SELECT identity_id,name_key FROM characters.character_discoveries")) {
            while(r.next()) names.add(r.getString(2),r.getString(1));
        }
        return names;
    }
    private static boolean discover(Connection c,Names names,TranscriptLine line) throws SQLException {
        if(line.channel()!=Channel.ROLEPLAY) return false;
        if(MessageTagReviews.overridden(c,MessageReference.of(line))) return false;
        var actor=CharacterNameMatcher.actor(line.text());
        if(actor.isEmpty()) return false;
        String name=actor.get().name(), key=CharacterNameMatcher.key(name);
        if(names.ids.containsKey(key)) return false; // A collision remains unresolved.
        String id=UUID.nameUUIDFromBytes(("lotc-observed-character-v1:"+key).getBytes(StandardCharsets.UTF_8)).toString();
        try(var q=c.prepareStatement("INSERT INTO characters.character_identities(id,name,notes,account_observation) VALUES(?,?,?,?)")) {
            q.setString(1,id);q.setString(2,name);q.setString(3,"");q.setString(4,"");q.executeUpdate();
        }
        try(var q=c.prepareStatement("INSERT INTO characters.character_discoveries VALUES(?,?)")) {
            q.setString(1,key);q.setString(2,id);q.executeUpdate();
        }
        names.add(name,id);return true;
    }
    static void reindex(Connection c) throws SQLException {
        var names=names(c);
        scan(c,line -> discover(c,names,line));
        try(var s=c.createStatement()) {s.executeUpdate("DELETE FROM characters.character_evidence");}
        scan(c,line -> {index(c,names,line);return false;});
    }
    static void indexNew(Connection c,List<TranscriptLine> lines) throws SQLException {
        var names=names(c);boolean discovered=false;
        for(var line:lines) if(exists(c,line)) discovered |= discover(c,names,line);
        if(discovered) { reindex(c); return; } // New names must also find earlier mentions.
        for(var line:lines) if(exists(c,line)) index(c,names,line);
    }
    private static boolean exists(Connection c,TranscriptLine line) throws SQLException {
        try(var q=c.prepareStatement("SELECT 1 FROM main.transcript_lines WHERE source_id=? AND source_line=?")) {
            q.setString(1,line.sourceId());q.setLong(2,line.sourceLine());try(var r=q.executeQuery()) {return r.next();}
        }
    }
    private static void scan(Connection c,Visitor action) throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM main.transcript_lines ORDER BY source_id,source_line")) {
            while(r.next()) action.accept(read(r));
        }
    }
    private static void index(Connection c,Names names,TranscriptLine line) throws SQLException {
        try(var q=c.prepareStatement("DELETE FROM characters.character_evidence WHERE source_id=? AND source_line=?")) {
            q.setString(1,line.sourceId());q.setLong(2,line.sourceLine());q.executeUpdate();
        }
        var actor=line.channel()==Channel.ROLEPLAY ? CharacterNameMatcher.actor(line.text()) : Optional.<CharacterNameMatcher.Actor>empty();
        String actorId=actor.isPresent()?names.unique(actor.get().name()):null;
        if(actorId!=null) put(c,actorId,line,actor.get().kind(),actor.get().name());
        var occupied=new ArrayList<CharacterNameMatcher.Span>();
        for(String key:names.ids.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder())).toList()) {
            var spans=CharacterNameMatcher.occurrences(line.text(),key);
            boolean match=false;
            for(var span:spans) {
                boolean covered=occupied.stream().anyMatch(old -> old.start()<=span.start() && old.end()>=span.end());
                if(!covered) { occupied.add(span);match=true; }
            }
            String id=names.unique(key);
            if(match && id!=null && !id.equals(actorId)) put(c,id,line,CharacterEvidenceKind.MENTION,names.labels.get(key));
        }
    }
    private static void put(Connection c,String id,TranscriptLine line,CharacterEvidenceKind kind,String name) throws SQLException {
        try(var q=c.prepareStatement("INSERT OR IGNORE INTO characters.character_evidence VALUES(?,?,?,?,?)")) {
            q.setString(1,id);q.setString(2,line.sourceId());q.setLong(3,line.sourceLine());q.setString(4,kind.name());q.setString(5,name);q.executeUpdate();
        }
    }
    static TranscriptPage transcript(Connection c,String identity,String query,Channel channel,CharacterEvidenceKind kind,boolean favorites,int offset,int limit) throws SQLException {
        if(query==null) query="";
        if(identity==null || !UUID.fromString(identity).toString().equals(identity) || offset<0 || limit<1 || limit>200 || query.length()>256)
            throw new IllegalArgumentException("Invalid character transcript query");
        if(channel==Channel.PRIVATE_MESSAGE || channel==Channel.OOC || channel==Channel.SYSTEM) throw new IllegalArgumentException("Excluded channel");
        String pattern="%"+query.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        String star="EXISTS(SELECT 1 FROM main.message_favorites f WHERE f.source_id=t.source_id AND f.source_line=t.source_line)";
        String where=" FROM main.transcript_lines t WHERE EXISTS(SELECT 1 FROM ("+MessageTagReviews.EFFECTIVE_SQL+") e WHERE e.source_id=t.source_id AND e.source_line=t.source_line AND e.identity_id=? AND (? IS NULL OR e.kind=?)) AND t.text LIKE ? ESCAPE '\\' AND (? IS NULL OR t.channel=?)"+(favorites?" AND "+star:"");
        long total;
        try(var q=c.prepareStatement("SELECT count(*)"+where)) {bind(q,identity,pattern,channel,kind);try(var r=q.executeQuery()){total=r.getLong(1);}}
        var entries=new ArrayList<TranscriptLine>();var stars=new HashSet<MessageReference>();
        try(var q=c.prepareStatement("SELECT t.*,"+star+" AS favorite"+where+" ORDER BY t.source_id,t.source_line LIMIT ? OFFSET ?")) {
            bind(q,identity,pattern,channel,kind);q.setInt(7,limit);q.setInt(8,offset);
            try(var r=q.executeQuery()) {while(r.next()) {var entry=read(r);entries.add(entry);if(r.getBoolean("favorite"))stars.add(MessageReference.of(entry));}}
        }
        return new TranscriptPage(entries,total,offset,limit,stars);
    }
    private static void bind(PreparedStatement q,String id,String pattern,Channel channel,CharacterEvidenceKind kind) throws SQLException {
        q.setString(1,id);q.setString(2,kind==null?null:kind.name());q.setString(3,kind==null?null:kind.name());q.setString(4,pattern);
        q.setString(5,channel==null?null:channel.name());q.setString(6,channel==null?null:channel.name());
    }
    private static TranscriptLine read(ResultSet r) throws SQLException {
        return new TranscriptLine(r.getString("source_id"),r.getLong("source_line"),r.getString("clock"),r.getString("text"),Channel.valueOf(r.getString("channel")),r.getString("observed_speaker"));
    }
    @FunctionalInterface private interface Visitor { boolean accept(TranscriptLine line) throws SQLException; }
}
