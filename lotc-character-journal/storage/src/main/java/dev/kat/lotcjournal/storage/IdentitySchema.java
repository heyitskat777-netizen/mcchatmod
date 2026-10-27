package dev.kat.lotcjournal.storage;

import java.util.Map;

/** Additive schema owned by the identity adapter; composed by journal initialization. */
final class IdentitySchema {
    static final Map<String,String> LEGACY_TABLES = Map.of(
        "character_identities", "CREATE TABLE character_identities (id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,notes TEXT NOT NULL,account_observation TEXT NOT NULL)",
        "character_aliases", "CREATE TABLE character_aliases (identity_id TEXT NOT NULL REFERENCES character_identities(id),alias TEXT NOT NULL,PRIMARY KEY(identity_id,alias))",
        "personas", "CREATE TABLE personas (id TEXT PRIMARY KEY NOT NULL,identity_id TEXT NOT NULL REFERENCES character_identities(id),label TEXT NOT NULL)",
        "identity_state", "CREATE TABLE identity_state (id INTEGER PRIMARY KEY CHECK(id=1),active_persona TEXT REFERENCES personas(id))",
        "memory_assignments", "CREATE TABLE memory_assignments (persona_id TEXT NOT NULL REFERENCES personas(id),source_id TEXT NOT NULL,source_line INTEGER NOT NULL,identity_id TEXT REFERENCES character_identities(id),role TEXT NOT NULL CHECK(role IN ('REFERENCED','SPEAKER')),PRIMARY KEY(persona_id,source_id,source_line),FOREIGN KEY(source_id,source_line) REFERENCES transcript_lines(source_id,source_line) ON DELETE CASCADE)",
        "identity_merges", "CREATE TABLE identity_merges (id TEXT PRIMARY KEY NOT NULL,from_id TEXT NOT NULL REFERENCES character_identities(id),to_id TEXT NOT NULL REFERENCES character_identities(id),active INTEGER NOT NULL CHECK(active IN (0,1)),created_at TEXT NOT NULL,CHECK(from_id<>to_id))",
        "identity_decisions", "CREATE TABLE identity_decisions (id TEXT PRIMARY KEY NOT NULL,kind TEXT NOT NULL,persona_id TEXT NOT NULL,source_id TEXT NOT NULL,source_line INTEGER NOT NULL,old_identity TEXT,new_identity TEXT,role TEXT NOT NULL,created_at TEXT NOT NULL)"
    );
    static final java.util.List<String> ORDER = java.util.List.of("character_identities","character_aliases","personas","identity_state","memory_assignments","identity_merges","identity_decisions");
    static final Map<String,String> TABLES = splitTables();
    private static Map<String,String> splitTables() {
        var tables = new java.util.HashMap<>(LEGACY_TABLES);
        tables.put("memory_assignments", tables.get("memory_assignments").replace(",FOREIGN KEY(source_id,source_line) REFERENCES transcript_lines(source_id,source_line) ON DELETE CASCADE", ""));
        return Map.copyOf(tables);
    }
    static void create(java.sql.Connection c) throws java.sql.SQLException {
        try(var s=c.createStatement()) {
            for(String table:ORDER) s.execute(TABLES.get(table).replace("CREATE TABLE ","CREATE TABLE characters."));
            s.execute("INSERT INTO characters.identity_state VALUES(1,NULL)");
        }
        CharacterIndex.create(c);
    }
    static void migrate(java.sql.Connection c) throws java.sql.SQLException {
        create(c);
        try(var s=c.createStatement()) {
            for(String table:ORDER) {
                if(table.equals("identity_state")) s.executeUpdate("DELETE FROM characters.identity_state");
                s.executeUpdate("INSERT INTO characters."+table+" SELECT * FROM main."+table);
            }
            // Drop children before their parents. All seven copies and all drops are
            // part of the same attached rollback-journal transaction.
            for(String table:java.util.List.of("identity_decisions","identity_merges","memory_assignments","identity_state","personas","character_aliases","character_identities"))
                s.execute("DROP TABLE main."+table);
        }
    }
    static void validate(java.sql.Connection c) throws java.sql.SQLException { validate(c,"characters"); CharacterIndex.validate(c); }
    static void validateLegacy(java.sql.Connection c) throws java.sql.SQLException { validate(c,"main"); }
    private static void validate(java.sql.Connection c,String schema) throws java.sql.SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+schema+".character_identities")) {
            while(r.next()) {
                SqliteIdentityRepository.readIdentity(c,r,schema);
                SqliteIdentityRepository.resolve(c,r.getString("id"),schema);
            }
        }
        try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+schema+".personas")) {
            while(r.next()) new dev.kat.lotcjournal.domain.Persona(r.getString("id"),r.getString("identity_id"),r.getString("label"));
        }
        try(var s=c.createStatement();var r=s.executeQuery("SELECT active_persona FROM "+schema+".identity_state WHERE id=1")) {
            if(!r.next()) throw new java.sql.SQLException("Missing persona selection state");
        }
        if(schema.equals("characters")) {
            try(var s=c.createStatement();var r=s.executeQuery("SELECT 1 FROM characters.memory_assignments m LEFT JOIN main.transcript_lines t ON t.source_id=m.source_id AND t.source_line=m.source_line WHERE t.source_id IS NULL LIMIT 1")) {
                if(r.next()) throw new java.sql.SQLException("Character memory refers to missing archive evidence");
            }
        }
    }
}
