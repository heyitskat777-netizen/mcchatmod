package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.IdentitySeparation;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Explicit negative identity decisions. Source dialogue and persona assignments are never changed here. */
final class IdentitySeparations {
    static final String TABLE = "CREATE TABLE identity_separations (id TEXT PRIMARY KEY NOT NULL,first_id TEXT NOT NULL REFERENCES character_identities(id),second_id TEXT NOT NULL REFERENCES character_identities(id),active INTEGER NOT NULL CHECK(active IN (0,1)),created_at TEXT NOT NULL,CHECK(first_id<second_id))";
    static final String INDEX = "CREATE UNIQUE INDEX identity_separations_active_pair ON identity_separations(first_id,second_id) WHERE active=1";
    private IdentitySeparations() {}
    static void create(Connection c) throws SQLException {
        try(var s=c.createStatement()) {
            s.execute(TABLE.replace("CREATE TABLE ","CREATE TABLE characters."));
            s.execute(INDEX.replace("CREATE UNIQUE INDEX ","CREATE UNIQUE INDEX characters."));
        }
    }
    static List<IdentitySeparation> all(Connection c) throws SQLException {
        var result=new ArrayList<IdentitySeparation>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM characters.identity_separations ORDER BY created_at,id")) {
            while(r.next()) result.add(new IdentitySeparation(r.getString("id"),r.getString("first_id"),r.getString("second_id"),r.getInt("active")==1));
        }
        return List.copyOf(result);
    }
    static String deny(Connection c,String first,String second) throws SQLException {
        exists(c,first);exists(c,second);
        if(first.equals(second)||SqliteIdentityRepository.resolve(c,first).equals(SqliteIdentityRepository.resolve(c,second)))
            throw new IllegalArgumentException("Undo the existing identity link before keeping these identities separate");
        String a=first.compareTo(second)<0?first:second,b=first.compareTo(second)<0?second:first;
        try(var q=c.prepareStatement("SELECT id FROM characters.identity_separations WHERE first_id=? AND second_id=? AND active=1")) {
            q.setString(1,a);q.setString(2,b);try(var r=q.executeQuery()) {if(r.next())return r.getString(1);}
        }
        String id=UUID.randomUUID().toString();
        try(var q=c.prepareStatement("INSERT INTO characters.identity_separations VALUES (?,?,?,1,?)")) {
            q.setString(1,id);q.setString(2,a);q.setString(3,b);q.setString(4,Instant.now().toString());q.executeUpdate();
        }
        return id;
    }
    static void undo(Connection c,String decision) throws SQLException {
        uuid(decision);
        try(var q=c.prepareStatement("UPDATE characters.identity_separations SET active=0 WHERE id=?")) {
            q.setString(1,decision);if(q.executeUpdate()!=1) throw new SQLException("Unknown identity separation decision");
        }
    }
    static void checkMerge(Connection c,String fromRoot,String toRoot) throws SQLException {
        for(var separation:all(c)) if(separation.active()) {
            String a=SqliteIdentityRepository.resolve(c,separation.firstIdentity());
            String b=SqliteIdentityRepository.resolve(c,separation.secondIdentity());
            if(a.equals(b)) throw new SQLException("Identity links contradict a persisted separation");
            if((a.equals(fromRoot)&&b.equals(toRoot))||(a.equals(toRoot)&&b.equals(fromRoot)))
                throw new IllegalArgumentException("This merge would override a separation decision; undo that decision first");
        }
    }
    static void validate(Connection c) throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM characters.identity_separations")) {
            while(r.next()) {
                try {
                    uuid(r.getString("id"));uuid(r.getString("first_id"));uuid(r.getString("second_id"));Instant.parse(r.getString("created_at"));
                    if(r.getString("first_id").compareTo(r.getString("second_id"))>=0 || (r.getInt("active")!=0&&r.getInt("active")!=1))
                        throw new IllegalArgumentException("Invalid separation fields");
                    if(r.getInt("active")==1&&SqliteIdentityRepository.resolve(c,r.getString("first_id")).equals(SqliteIdentityRepository.resolve(c,r.getString("second_id"))))
                        throw new SQLException("Identity links contradict a persisted separation");
                } catch(RuntimeException invalid) {throw new SQLException("Invalid stored separation decision",invalid);}
            }
        }
    }
    private static void exists(Connection c,String id) throws SQLException {
        uuid(id);
        try(var q=c.prepareStatement("SELECT 1 FROM characters.character_identities WHERE id=?")) {
            q.setString(1,id);try(var r=q.executeQuery()) {if(!r.next())throw new SQLException("Unknown identity");}
        }
    }
    private static void uuid(String value) {
        if(value==null||!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException("Invalid identity reference");
    }
}
