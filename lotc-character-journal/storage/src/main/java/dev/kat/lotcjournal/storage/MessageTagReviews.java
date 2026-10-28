package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Per-coordinate interpretation overrides. Original text/channel and persona knowledge remain separate. */
final class MessageTagReviews {
    static final Map<String,String> TABLES=Map.of(
        "message_tag_reviews","CREATE TABLE message_tag_reviews (source_id TEXT NOT NULL CHECK(length(source_id)=64 AND source_id NOT GLOB '*[^0-9a-f]*'),source_line INTEGER NOT NULL CHECK(typeof(source_line)='integer' AND source_line>0),reviewed INTEGER NOT NULL CHECK(reviewed IN (0,1)),revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>=1),created_at TEXT NOT NULL,updated_at TEXT NOT NULL,PRIMARY KEY(source_id,source_line))",
        "message_tag_choices","CREATE TABLE message_tag_choices (source_id TEXT NOT NULL,source_line INTEGER NOT NULL,identity_id TEXT NOT NULL REFERENCES character_identities(id),kind TEXT NOT NULL CHECK(kind IN ('SPEAKER','ACTION','MENTION')),PRIMARY KEY(source_id,source_line,identity_id,kind),FOREIGN KEY(source_id,source_line) REFERENCES message_tag_reviews(source_id,source_line) ON DELETE CASCADE)"
    );
    static final Map<String,String> INDEXES=Map.of("message_tag_choices_by_identity",
        "CREATE INDEX message_tag_choices_by_identity ON message_tag_choices(identity_id,source_id,source_line,kind)");
    /** A rejected-all review contributes zero rows; restored AUTO tombstones deliberately use automatic evidence. */
    static final String EFFECTIVE_SQL="SELECT x.source_id,x.source_line,x.identity_id,x.kind FROM characters.message_tag_choices x JOIN characters.message_tag_reviews r ON r.source_id=x.source_id AND r.source_line=x.source_line WHERE r.reviewed=1 UNION ALL SELECT a.source_id,a.source_line,a.identity_id,a.kind FROM characters.character_evidence a WHERE NOT EXISTS(SELECT 1 FROM characters.message_tag_reviews r WHERE r.source_id=a.source_id AND r.source_line=a.source_line AND r.reviewed=1)";
    private MessageTagReviews() {}
    static void create(Connection c) throws SQLException {
        try(var s=c.createStatement()) {
            s.execute(TABLES.get("message_tag_reviews").replace("CREATE TABLE ","CREATE TABLE characters."));
            s.execute(TABLES.get("message_tag_choices").replace("CREATE TABLE ","CREATE TABLE characters."));
            for(String sql:INDEXES.values())s.execute(sql.replace("CREATE INDEX ","CREATE INDEX characters."));
        }
    }
    static boolean overridden(Connection c,MessageReference ref) throws SQLException {
        try(var q=c.prepareStatement("SELECT reviewed FROM characters.message_tag_reviews WHERE source_id=? AND source_line=?")) {
            reference(q,ref);try(var r=q.executeQuery()){return r.next()&&r.getInt(1)==1;}
        }
    }
    static MessageTagReview review(Connection c,MessageReference ref) throws SQLException {
        Objects.requireNonNull(ref);var message=message(c,ref);long revision=0;boolean reviewed=false;
        try(var q=c.prepareStatement("SELECT reviewed,revision FROM characters.message_tag_reviews WHERE source_id=? AND source_line=?")) {
            reference(q,ref);try(var r=q.executeQuery()){if(r.next()){reviewed=r.getInt(1)==1;revision=r.getLong(2);}}
        }
        var automatic=tags(c,ref,"character_evidence");
        var effective=reviewed?tags(c,ref,"message_tag_choices"):automatic;
        var names=new java.util.HashMap<String,String>();
        try(var q=c.prepareStatement("SELECT id,name FROM characters.character_identities WHERE id IN (SELECT identity_id FROM characters.character_evidence WHERE source_id=? AND source_line=? UNION SELECT identity_id FROM characters.message_tag_choices WHERE source_id=? AND source_line=?)")) {
            reference(q,ref);q.setString(3,ref.sourceId());q.setLong(4,ref.sourceLine());
            try(var r=q.executeQuery()){while(r.next())names.put(r.getString(1),r.getString(2));}
        }
        return new MessageTagReview(message,automatic,effective,reviewed,revision,Map.copyOf(names));
    }
    static MessageTagReview save(Connection c,MessageReference ref,long expected,List<CharacterTag> requested) throws SQLException {
        var current=review(c,ref);checkRevision(current,expected);
        if(requested==null||requested.size()>64||requested.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Use at most 64 character tags");
        var chosen=List.copyOf(requested);var unique=new HashSet<>(chosen);
        if(unique.size()!=chosen.size())throw new IllegalArgumentException("Duplicate character tag");
        for(var tag:chosen) {
            if(current.message().channel()!=Channel.ROLEPLAY&&tag.kind()!=CharacterEvidenceKind.MENTION)
                throw new IllegalArgumentException("Unknown dialogue can only have mention tags");
            try(var q=c.prepareStatement("SELECT 1 FROM characters.character_identities WHERE id=?")) {
                q.setString(1,tag.identityId());try(var r=q.executeQuery()){if(!r.next())throw new SQLException("Unknown character tag identity");}
            }
        }
        if(current.overridden()&&unique.equals(new HashSet<>(current.effectiveTags())))return current;
        long revision=Math.addExact(current.revision(),1);String now=Instant.now().toString();
        try(var q=c.prepareStatement("INSERT INTO characters.message_tag_reviews(source_id,source_line,reviewed,revision,created_at,updated_at) VALUES (?,?,1,?,?,?) ON CONFLICT(source_id,source_line) DO UPDATE SET reviewed=1,revision=excluded.revision,updated_at=excluded.updated_at")) {
            reference(q,ref);q.setLong(3,revision);q.setString(4,now);q.setString(5,now);q.executeUpdate();
        }
        clearChoices(c,ref);
        try(var q=c.prepareStatement("INSERT INTO characters.message_tag_choices VALUES (?,?,?,?)")) {
            for(var tag:chosen.stream().sorted(Comparator.comparing(CharacterTag::identityId).thenComparing(t->t.kind().name())).toList()) {
                reference(q,ref);q.setString(3,tag.identityId());q.setString(4,tag.kind().name());q.addBatch();
            }
            q.executeBatch();
        }
        return review(c,ref);
    }
    static MessageTagReview restore(Connection c,MessageReference ref,long expected) throws SQLException {
        var current=review(c,ref);checkRevision(current,expected);
        if(!current.overridden())return current;
        clearChoices(c,ref);
        try(var q=c.prepareStatement("UPDATE characters.message_tag_reviews SET reviewed=0,revision=?,updated_at=? WHERE source_id=? AND source_line=?")) {
            q.setLong(1,Math.addExact(current.revision(),1));q.setString(2,Instant.now().toString());q.setString(3,ref.sourceId());q.setLong(4,ref.sourceLine());q.executeUpdate();
        }
        CharacterIndex.indexNew(c,List.of(current.message()));
        return review(c,ref);
    }
    private static void checkRevision(MessageTagReview current,long expected) throws SQLException {
        if(expected<0)throw new IllegalArgumentException("Invalid tag review revision");
        if(current.revision()!=expected)throw new SQLException("Message tags changed; reload before saving");
    }
    private static void clearChoices(Connection c,MessageReference ref) throws SQLException {
        try(var q=c.prepareStatement("DELETE FROM characters.message_tag_choices WHERE source_id=? AND source_line=?")){reference(q,ref);q.executeUpdate();}
    }
    private static List<CharacterTag> tags(Connection c,MessageReference ref,String table) throws SQLException {
        var result=new ArrayList<CharacterTag>();
        try(var q=c.prepareStatement("SELECT identity_id,kind FROM characters."+table+" WHERE source_id=? AND source_line=? ORDER BY identity_id,kind")) {
            reference(q,ref);try(var r=q.executeQuery()){while(r.next())result.add(new CharacterTag(r.getString(1),CharacterEvidenceKind.valueOf(r.getString(2))));}
        }
        return List.copyOf(result);
    }
    private static TranscriptLine message(Connection c,MessageReference ref) throws SQLException {
        try(var q=c.prepareStatement("SELECT * FROM main.transcript_lines WHERE source_id=? AND source_line=?")) {
            reference(q,ref);try(var r=q.executeQuery()) {
                if(!r.next())throw new SQLException("Message is no longer available");
                return new TranscriptLine(r.getString("source_id"),r.getLong("source_line"),r.getString("clock"),r.getString("text"),Channel.valueOf(r.getString("channel")),r.getString("observed_speaker"));
            }
        }
    }
    private static void reference(java.sql.PreparedStatement q,MessageReference ref) throws SQLException {q.setString(1,ref.sourceId());q.setLong(2,ref.sourceLine());}
    static void validate(Connection c) throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM characters.message_tag_reviews")) {
            while(r.next()) {
                try {
                    new MessageReference(r.getString("source_id"),r.getLong("source_line"));Instant.parse(r.getString("created_at"));Instant.parse(r.getString("updated_at"));
                    if(r.getLong("revision")<1||(r.getInt("reviewed")!=0&&r.getInt("reviewed")!=1))throw new IllegalArgumentException("Invalid tag review metadata");
                } catch(RuntimeException invalid){throw new SQLException("Invalid stored tag review",invalid);}
            }
        }
        try(var s=c.createStatement();var r=s.executeQuery("SELECT 1 FROM characters.message_tag_reviews r LEFT JOIN main.transcript_lines t ON t.source_id=r.source_id AND t.source_line=r.source_line WHERE t.source_id IS NULL UNION ALL SELECT 1 FROM characters.message_tag_choices x LEFT JOIN characters.message_tag_reviews r ON r.source_id=x.source_id AND r.source_line=x.source_line LEFT JOIN characters.character_identities i ON i.id=x.identity_id LEFT JOIN main.transcript_lines t ON t.source_id=x.source_id AND t.source_line=x.source_line WHERE r.source_id IS NULL OR r.reviewed=0 OR i.id IS NULL OR t.source_id IS NULL OR (t.channel!='ROLEPLAY' AND x.kind!='MENTION') UNION ALL SELECT 1 FROM characters.message_tag_choices GROUP BY source_id,source_line HAVING count(*)>64 LIMIT 1")) {
            if(r.next())throw new SQLException("Inconsistent character tag review references or channel");
        }
    }
}
