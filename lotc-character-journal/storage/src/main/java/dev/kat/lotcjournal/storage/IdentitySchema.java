package dev.kat.lotcjournal.storage;

import java.util.Map;

/** Additive schema owned by the identity adapter; composed by journal initialization. */
final class IdentitySchema {
    static final Map<String,String> TABLES = Map.of(
        "character_identities", "CREATE TABLE character_identities (id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,notes TEXT NOT NULL,account_observation TEXT NOT NULL)",
        "character_aliases", "CREATE TABLE character_aliases (identity_id TEXT NOT NULL REFERENCES character_identities(id),alias TEXT NOT NULL,PRIMARY KEY(identity_id,alias))",
        "personas", "CREATE TABLE personas (id TEXT PRIMARY KEY NOT NULL,identity_id TEXT NOT NULL REFERENCES character_identities(id),label TEXT NOT NULL)",
        "identity_state", "CREATE TABLE identity_state (id INTEGER PRIMARY KEY CHECK(id=1),active_persona TEXT REFERENCES personas(id))",
        "memory_assignments", "CREATE TABLE memory_assignments (persona_id TEXT NOT NULL REFERENCES personas(id),source_id TEXT NOT NULL,source_line INTEGER NOT NULL,identity_id TEXT REFERENCES character_identities(id),role TEXT NOT NULL CHECK(role IN ('REFERENCED','SPEAKER')),PRIMARY KEY(persona_id,source_id,source_line),FOREIGN KEY(source_id,source_line) REFERENCES transcript_lines(source_id,source_line) ON DELETE CASCADE)",
        "identity_merges", "CREATE TABLE identity_merges (id TEXT PRIMARY KEY NOT NULL,from_id TEXT NOT NULL REFERENCES character_identities(id),to_id TEXT NOT NULL REFERENCES character_identities(id),active INTEGER NOT NULL CHECK(active IN (0,1)),created_at TEXT NOT NULL,CHECK(from_id<>to_id))",
        "identity_decisions", "CREATE TABLE identity_decisions (id TEXT PRIMARY KEY NOT NULL,kind TEXT NOT NULL,persona_id TEXT NOT NULL,source_id TEXT NOT NULL,source_line INTEGER NOT NULL,old_identity TEXT,new_identity TEXT,role TEXT NOT NULL,created_at TEXT NOT NULL)"
    );
    static void create(java.sql.Connection c) throws java.sql.SQLException {
        try(var s=c.createStatement()) {
            for(String table:java.util.List.of("character_identities","character_aliases","personas","identity_state","memory_assignments","identity_merges","identity_decisions")) s.execute(TABLES.get(table));
            s.execute("INSERT INTO identity_state VALUES(1,NULL)");
        }
    }
    static void validate(java.sql.Connection c) throws java.sql.SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM character_identities")) {
            while(r.next()) {
                SqliteIdentityRepository.readIdentity(c,r);
                SqliteIdentityRepository.resolve(c,r.getString("id"));
            }
        }
        try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM personas")) {
            while(r.next()) new dev.kat.lotcjournal.domain.Persona(r.getString("id"),r.getString("identity_id"),r.getString("label"));
        }
        try(var s=c.createStatement();var r=s.executeQuery("SELECT active_persona FROM identity_state WHERE id=1")) {
            if(!r.next()) throw new java.sql.SQLException("Missing persona selection state");
        }
    }
}
