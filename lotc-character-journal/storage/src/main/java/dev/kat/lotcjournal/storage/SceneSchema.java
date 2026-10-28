package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;

/** Scene interpretations own coordinates, never copies of source dialogue. */
final class SceneSchema {
    static final Map<String,String> TABLES=Map.of(
        "scenes","CREATE TABLE scenes (id TEXT PRIMARY KEY NOT NULL,title TEXT NOT NULL CHECK(length(title)>0 AND length(title)<=160),location TEXT NOT NULL CHECK(length(location)<=240),state TEXT NOT NULL CHECK(state IN ('SUGGESTED','CONFIRMED','SKIPPED')),active INTEGER NOT NULL CHECK(active IN (0,1)),revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>=1),participants_reviewed INTEGER NOT NULL CHECK(participants_reviewed IN (0,1)),created_at TEXT NOT NULL,updated_at TEXT NOT NULL)",
        "scene_members","CREATE TABLE scene_members (scene_id TEXT NOT NULL REFERENCES scenes(id) ON DELETE CASCADE,position INTEGER NOT NULL CHECK(typeof(position)='integer' AND position>=0 AND position<5000),source_id TEXT NOT NULL CHECK(length(source_id)=64 AND source_id NOT GLOB '*[^0-9a-f]*'),source_line INTEGER NOT NULL CHECK(typeof(source_line)='integer' AND source_line>0),PRIMARY KEY(scene_id,source_id,source_line),UNIQUE(scene_id,position))",
        "scene_participants","CREATE TABLE scene_participants (scene_id TEXT NOT NULL REFERENCES scenes(id) ON DELETE CASCADE,identity_id TEXT NOT NULL REFERENCES character_identities(id),role TEXT NOT NULL CHECK(role IN ('UNCERTAIN','DIRECT','OVERHEARD')),perspective_persona TEXT REFERENCES personas(id),CHECK(role='UNCERTAIN' OR perspective_persona IS NOT NULL))",
        "scene_settings","CREATE TABLE scene_settings (id INTEGER PRIMARY KEY CHECK(id=1),inactivity_seconds INTEGER NOT NULL CHECK(typeof(inactivity_seconds)='integer' AND inactivity_seconds BETWEEN 60 AND 7200))"
    );
    static final Map<String,String> INDEXES=Map.of(
        "scene_members_by_message","CREATE INDEX scene_members_by_message ON scene_members(source_id,source_line)",
        "scene_participant_perspective","CREATE UNIQUE INDEX scene_participant_perspective ON scene_participants(scene_id,identity_id,coalesce(perspective_persona,''))"
    );
    private SceneSchema() {}
    static void create(Connection c) throws SQLException {
        try(var s=c.createStatement()) {
            for(String table:java.util.List.of("scenes","scene_members","scene_participants","scene_settings"))s.execute(TABLES.get(table).replace("CREATE TABLE ","CREATE TABLE characters."));
            for(String sql:INDEXES.values())s.execute(sql.startsWith("CREATE UNIQUE INDEX")?sql.replace("CREATE UNIQUE INDEX ","CREATE UNIQUE INDEX characters."):sql.replace("CREATE INDEX ","CREATE INDEX characters."));
            s.execute("INSERT INTO characters.scene_settings VALUES (1,900)");
        }
    }
    static void validate(Connection c) throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT s.*,(SELECT count(*) FROM characters.scene_members m WHERE m.scene_id=s.id) AS members FROM characters.scenes s")) {
            while(r.next())try {
                SceneParticipant.requireUuid(r.getString("id"));SceneState.valueOf(r.getString("state"));Instant.parse(r.getString("created_at"));Instant.parse(r.getString("updated_at"));
                if(r.getInt("active")==1)new SceneSummary(r.getString("id"),r.getString("title"),r.getString("location"),SceneState.valueOf(r.getString("state")),r.getLong("members"),r.getLong("revision"));
                if(r.getLong("members")>SceneGrouper.MAX_MESSAGES)throw new IllegalArgumentException("Oversized scene");
            } catch(RuntimeException invalid){throw new SQLException("Invalid stored scene",invalid);}
        }
        try(var s=c.createStatement();var r=s.executeQuery("SELECT inactivity_seconds FROM characters.scene_settings WHERE id=1")) {
            if(!r.next())throw new SQLException("Missing scene settings");
            try {new SceneSettings(r.getInt(1));}catch(RuntimeException invalid){throw new SQLException("Invalid scene settings",invalid);}
        }
        try(var s=c.createStatement();var r=s.executeQuery("SELECT 1 FROM characters.scene_members m LEFT JOIN main.transcript_lines t ON t.source_id=m.source_id AND t.source_line=m.source_line WHERE t.source_id IS NULL OR t.channel!='ROLEPLAY' UNION ALL SELECT 1 FROM characters.scene_members m JOIN characters.scenes s ON s.id=m.scene_id WHERE s.active=1 GROUP BY m.source_id,m.source_line HAVING count(*)>1 UNION ALL SELECT 1 FROM characters.scene_participants GROUP BY scene_id HAVING count(*)>64 LIMIT 1")) {
            if(r.next())throw new SQLException("Invalid scene references, overlapping membership or participants");
        }
    }
    static void removeBatch(Connection c,String batch) throws SQLException {
        var affected=new ArrayList<String>();
        try(var q=c.prepareStatement("SELECT DISTINCT m.scene_id FROM characters.scene_members m JOIN main.observation_owners o ON o.source_id=m.source_id AND o.source_line=m.source_line WHERE o.batch_id=?")) {
            q.setString(1,batch);try(var r=q.executeQuery()){while(r.next())affected.add(r.getString(1));}
        }
        try(var q=c.prepareStatement("DELETE FROM characters.scene_members WHERE (source_id,source_line) IN (SELECT source_id,source_line FROM main.observation_owners WHERE batch_id=?)")) {q.setString(1,batch);q.executeUpdate();}
        for(String id:affected) changed(c,id);
    }
    static void removeReference(Connection c,String source,long line) throws SQLException {
        var affected=new ArrayList<String>();
        try(var q=c.prepareStatement("SELECT scene_id FROM characters.scene_members WHERE source_id=? AND source_line=?")) {
            q.setString(1,source);q.setLong(2,line);try(var r=q.executeQuery()){while(r.next())affected.add(r.getString(1));}
        }
        try(var q=c.prepareStatement("DELETE FROM characters.scene_members WHERE source_id=? AND source_line=?")) {q.setString(1,source);q.setLong(2,line);q.executeUpdate();}
        for(String id:affected)changed(c,id);
    }
    private static void changed(Connection c,String id) throws SQLException {
        try(var q=c.prepareStatement("UPDATE characters.scenes SET revision=revision+1,updated_at=?,active=CASE WHEN EXISTS(SELECT 1 FROM characters.scene_members WHERE scene_id=?) THEN active ELSE 0 END WHERE id=?")) {
            q.setString(1,Instant.now().toString());q.setString(2,id);q.setString(3,id);q.executeUpdate();
        }
    }
}
