package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Read-snapshot projections; persona views are derived exclusively from assigned scene evidence. */
final class SceneProjection {
    record Header(String id,String title,String location,SceneState state,boolean active,long revision,boolean participantsReviewed,boolean automatic,boolean qualified) {}
    private SceneProjection() {}
    static Header header(Connection c,String id,boolean requireActive) throws SQLException {
        SceneParticipant.requireUuid(id);
        try(var q=c.prepareStatement("SELECT * FROM characters.scenes WHERE id=?")) {
            q.setString(1,id);try(var r=q.executeQuery()) {
                if(!r.next()){if(requireActive)throw new SQLException("Scene is no longer available");return null;}
                var value=new Header(id,r.getString("title"),r.getString("location"),SceneState.valueOf(r.getString("state")),r.getInt("active")==1,r.getLong("revision"),r.getInt("participants_reviewed")==1,r.getString("origin").equals("AUTO"),r.getInt("qualified")==1);
                if(requireActive&&!value.active())throw new SQLException("Scene is no longer available");return value;
            }
        }
    }
    static void persona(Connection c,String id) throws SQLException {
        if(id==null)return;SceneParticipant.requireUuid(id);
        try(var q=c.prepareStatement("SELECT 1 FROM characters.personas WHERE id=?")){q.setString(1,id);try(var r=q.executeQuery()){if(!r.next())throw new SQLException("Unknown persona");}}
    }
    static SceneSettings settings(Connection c) throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT inactivity_seconds FROM characters.scene_settings WHERE id=1")){if(!r.next())throw new SQLException("Missing scene settings");return new SceneSettings(r.getInt(1));}
    }
    static List<SceneObservation> members(Connection c,String scene,String persona) throws SQLException {
        var result=new ArrayList<SceneObservation>();
        String ownership=persona==null?"":" AND EXISTS(SELECT 1 FROM characters.memory_assignments a WHERE a.source_id=t.source_id AND a.source_line=t.source_line AND a.persona_id=?)";
        try(var q=c.prepareStatement("SELECT t.*,e.received_at FROM characters.scene_members m JOIN main.transcript_lines t ON t.source_id=m.source_id AND t.source_line=m.source_line LEFT JOIN main.live_events e ON e.source_id=t.source_id AND e.sequence=t.source_line WHERE m.scene_id=?"+ownership+" ORDER BY m.position")) {
            q.setString(1,scene);if(persona!=null)q.setString(2,persona);
            try(var r=q.executeQuery()){while(r.next())result.add(observation(c,r,persona));}
        }
        return List.copyOf(result);
    }
    static SceneObservation observation(Connection c,ResultSet r,String persona) throws SQLException {
        var line=new TranscriptLine(r.getString("source_id"),r.getLong("source_line"),r.getString("clock"),r.getString("text"),Channel.valueOf(r.getString("channel")),r.getString("observed_speaker"));
        Long time=null;String instant=r.getString("received_at");
        try {if(instant!=null)time=Instant.parse(instant).getEpochSecond();else if(line.clock()!=null)time=(long)LocalTime.parse(line.clock()).toSecondOfDay();}
        catch(DateTimeException invalid){time=null;}
        var owners=new ArrayList<String>();
        if(persona!=null)owners.add(persona);
        else try(var q=c.prepareStatement("SELECT persona_id FROM characters.memory_assignments WHERE source_id=? AND source_line=? ORDER BY persona_id")) {
            q.setString(1,line.sourceId());q.setLong(2,line.sourceLine());try(var rows=q.executeQuery()){while(rows.next())owners.add(rows.getString(1));}
        }
        return new SceneObservation(line,time,String.join(",",owners));
    }
    static List<MessageReference> refs(List<SceneObservation> rows){return rows.stream().map(r->MessageReference.of(r.message())).toList();}
    static List<Set<String>> actorsByMessage(Connection c,List<SceneObservation> rows) throws SQLException {
        var result=new ArrayList<Set<String>>();var resolved=new HashMap<String,String>();
        try(var q=c.prepareStatement("SELECT identity_id FROM ("+MessageTagReviews.EFFECTIVE_SQL+") WHERE source_id=? AND source_line=? AND kind IN ('SPEAKER','ACTION')")) {
            for(var row:rows){q.setString(1,row.message().sourceId());q.setLong(2,row.message().sourceLine());var actors=new HashSet<String>();
                try(var r=q.executeQuery()){while(r.next()){String raw=r.getString(1),id=resolved.get(raw);if(id==null){id=SqliteIdentityRepository.resolve(c,raw);resolved.put(raw,id);}actors.add(id);}}
                result.add(Set.copyOf(actors));
            }
        }
        return List.copyOf(result);
    }
    static SceneAssessment assessment(Connection c,List<SceneObservation> rows) throws SQLException {
        return SceneConfidence.assess(rows,actorsByMessage(c,rows));
    }
    static void visible(Header header,String persona) throws SQLException {
        if(persona==null&&header.automatic()&&!header.qualified())throw new SQLException("Automatic window does not meet scene requirements");
    }
    static String name(Connection c,String id) throws SQLException {
        try(var q=c.prepareStatement("SELECT name FROM characters.character_identities WHERE id=?")){q.setString(1,id);try(var r=q.executeQuery()){if(!r.next())throw new SQLException("Unknown scene participant");return r.getString(1);}}
    }
    static List<SceneParticipant> manualParticipants(Connection c,String scene) throws SQLException {
        var result=new ArrayList<SceneParticipant>();
        try(var q=c.prepareStatement("SELECT p.*,i.name FROM characters.scene_participants p JOIN characters.character_identities i ON i.id=p.identity_id WHERE p.scene_id=? ORDER BY p.identity_id,coalesce(p.perspective_persona,'')")) {
            q.setString(1,scene);try(var r=q.executeQuery()){while(r.next())result.add(new SceneParticipant(r.getString("identity_id"),r.getString("name"),EncounterRole.valueOf(r.getString("role")),r.getString("perspective_persona")));}
        }
        return List.copyOf(result);
    }
    static SceneDetail detail(Connection c,Header header,String persona) throws SQLException {
        var rows=members(c,header.id(),persona);if(rows.isEmpty())throw new SQLException("Scene has no assigned evidence for this view");
        return detail(c,header,persona,rows);
    }
    static SceneDetail detail(Connection c,Header header,String persona,List<SceneObservation> rows) throws SQLException {
        visible(header,persona);
        var actors=new TreeSet<String>();var speech=new HashSet<MessageReference>();var action=new HashSet<MessageReference>();var mention=new HashSet<MessageReference>();
        try(var q=c.prepareStatement("SELECT identity_id,kind FROM ("+MessageTagReviews.EFFECTIVE_SQL+") WHERE source_id=? AND source_line=?")) {
            for(var row:rows){var ref=MessageReference.of(row.message());q.setString(1,ref.sourceId());q.setLong(2,ref.sourceLine());
                try(var r=q.executeQuery()){while(r.next()){
                    var kind=CharacterEvidenceKind.valueOf(r.getString("kind"));
                    if(persona==null){
                        if(kind==CharacterEvidenceKind.MENTION)mention.add(ref);
                        else {actors.add(SqliteIdentityRepository.resolve(c,r.getString("identity_id")));if(kind==CharacterEvidenceKind.SPEAKER)speech.add(ref);else action.add(ref);}
                    }
                }}
            }
        }
        if(persona!=null)try(var q=c.prepareStatement("SELECT identity_id,role FROM characters.memory_assignments WHERE persona_id=? AND source_id=? AND source_line=?")) {
            for(var row:rows){var ref=MessageReference.of(row.message());q.setString(1,persona);q.setString(2,ref.sourceId());q.setLong(3,ref.sourceLine());try(var r=q.executeQuery()){while(r.next()){
                if(r.getString("role").equals("SPEAKER")){
                    if(r.getString("identity_id")!=null)actors.add(r.getString("identity_id"));
                    if(CharacterNameMatcher.actor(row.message().text()).map(a->a.kind()==CharacterEvidenceKind.ACTION).orElse(false))action.add(ref);else speech.add(ref);
                }else mention.add(ref);
            }}}
        }
        List<SceneParticipant> participants;
        var manual=header.participantsReviewed()?manualParticipants(c,header.id()):List.<SceneParticipant>of();
        if(persona==null&&header.participantsReviewed())participants=manual;
        else {
            var values=new ArrayList<SceneParticipant>();
            for(String id:actors){EncounterRole role=EncounterRole.UNCERTAIN;String perspective=null;boolean matched=false,conflict=false;
                if(persona!=null)for(var saved:manual)if(persona.equals(saved.perspectivePersonaId())&&id.equals(saved.identityId())){
                    if(matched&&role!=saved.role())conflict=true;else {role=saved.role();perspective=persona;matched=true;}
                }
                if(conflict){role=EncounterRole.UNCERTAIN;perspective=null;}
                values.add(new SceneParticipant(id,name(c,id),role,perspective));
            }
            participants=List.copyOf(values);
        }
        var summary=new SceneSummary(header.id(),persona==null?header.title():"Assigned scene",persona==null?header.location():"",persona==null?header.state():SceneState.SUGGESTED,rows.size(),header.revision(),persona==null&&header.automatic()?SceneOrigin.AUTO:SceneOrigin.MANUAL);
        return new SceneDetail(summary,participants,new SceneStatistics(rows.size(),speech.size(),action.size(),mention.size(),SceneGrouper.elapsed(rows),SceneGrouper.active(rows)),persona==null?assessment(c,rows):SceneAssessment.unassessed());
    }
    static TranscriptPage transcript(Connection c,String id,String persona,String query,int offset,int limit) throws SQLException {
        visible(header(c,id,true),persona);persona(c,persona);if(query==null)query="";
        if(offset<0||limit<1||limit>200||query.length()>256)throw new IllegalArgumentException("Invalid scene transcript bounds");
        var rows=members(c,id,persona);if(rows.isEmpty())throw new SQLException("Scene has no assigned evidence for this view");
        String filter=query.toLowerCase(Locale.ROOT);
        var filtered=rows.stream().map(SceneObservation::message).filter(line->line.text().toLowerCase(Locale.ROOT).contains(filter)||(line.observedSpeaker()!=null&&line.observedSpeaker().toLowerCase(Locale.ROOT).contains(filter))).toList();
        var page=filtered.subList(Math.min(offset,filtered.size()),Math.min(filtered.size(),(int)Math.min((long)offset+limit,Integer.MAX_VALUE)));
        var stars=new HashSet<MessageReference>();
        try(var q=c.prepareStatement("SELECT 1 FROM main.message_favorites WHERE source_id=? AND source_line=?")) {
            for(var line:page){q.setString(1,line.sourceId());q.setLong(2,line.sourceLine());try(var r=q.executeQuery()){if(r.next())stars.add(MessageReference.of(line));}}
        }
        return new TranscriptPage(page,filtered.size(),offset,limit,stars);
    }
}
