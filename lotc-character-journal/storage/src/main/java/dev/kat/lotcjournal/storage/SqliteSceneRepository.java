package dev.kat.lotcjournal.storage;

import dev.kat.lotcjournal.domain.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Revisioned scene decisions over immutable archive coordinates. Call from the scene worker only. */
public final class SqliteSceneRepository implements SceneRepository {
    private final Path archive,characters;
    public SqliteSceneRepository(Path archive){this(archive,SplitJournalFiles.charactersFor(archive));}
    public SqliteSceneRepository(Path archive,Path characters){this.archive=archive.toAbsolutePath().normalize();this.characters=characters.toAbsolutePath().normalize();}
    private Connection open() throws SQLException{return SplitJournalFiles.open(archive,characters);}
    @FunctionalInterface private interface Operation<T>{T run(Connection c) throws Exception;}
    private <T>T read(Operation<T> operation)throws Exception{
        try(var c=open()){c.setAutoCommit(false);T result=operation.run(c);c.commit();return result;}
    }
    private <T>T write(Operation<T> operation)throws Exception{
        try(var c=open()){SplitJournalFiles.beginWrite(c);try{T result=operation.run(c);c.commit();return result;}catch(Exception failure){c.rollback();throw failure;}}
    }
    @Override public SceneSettings settings()throws Exception{return read(SceneProjection::settings);}
    @Override public void saveSettings(SceneSettings settings)throws Exception{
        Objects.requireNonNull(settings);write(c->{try(var q=c.prepareStatement("UPDATE characters.scene_settings SET inactivity_seconds=? WHERE id=1")){q.setInt(1,settings.inactivitySeconds());if(q.executeUpdate()!=1)throw new SQLException("Missing scene settings");}return null;});
    }
    @Override public int refreshSuggestions()throws Exception{return write(this::refresh);}
    private int refresh(Connection c)throws SQLException{
        var old=new HashSet<String>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT id FROM characters.scenes WHERE active=1 AND state='SUGGESTED'")){while(r.next())old.add(r.getString(1));}
        var seen=new HashSet<String>();var current=new ArrayList<SceneObservation>();SceneObservation previous=null;long barrier=0;int changed=0;var settings=SceneProjection.settings(c);
        try(var s=c.createStatement();var r=s.executeQuery("SELECT t.*,e.received_at FROM main.transcript_lines t LEFT JOIN main.live_events e ON e.source_id=t.source_id AND e.sequence=t.source_line WHERE t.channel='ROLEPLAY' ORDER BY t.source_id,t.source_line")){
            while(r.next()){
                var observation=SceneProjection.observation(c,r,null);
                if(protectedMember(c,MessageReference.of(observation.message()))){if(!current.isEmpty()){changed+=suggest(c,current,seen);current.clear();}previous=null;barrier++;continue;}
                observation=new SceneObservation(observation.message(),observation.timelineSeconds(),observation.boundaryKey()+"|barrier:"+barrier);
                boolean boundary=previous!=null&&(current.size()>=SceneGrouper.MAX_MESSAGES||SceneGrouper.group(List.of(previous,observation),settings).size()>1);
                if(boundary){changed+=suggest(c,current,seen);current.clear();}
                current.add(observation);previous=observation;
            }
        }
        if(!current.isEmpty())changed+=suggest(c,current,seen);
        for(String id:old)if(!seen.contains(id)){retire(c,SceneProjection.header(c,id,true),false);changed++;}
        return changed;
    }
    private static boolean protectedMember(Connection c,MessageReference ref)throws SQLException{
        try(var q=c.prepareStatement("SELECT 1 FROM characters.scene_members m JOIN characters.scenes s ON s.id=m.scene_id WHERE m.source_id=? AND m.source_line=? AND s.active=1 AND s.state!='SUGGESTED' LIMIT 1")){
            q.setString(1,ref.sourceId());q.setLong(2,ref.sourceLine());try(var r=q.executeQuery()){return r.next();}
        }
    }
    private static int suggest(Connection c,List<SceneObservation> observations,Set<String> seen)throws SQLException{
        var refs=SceneProjection.refs(observations);var first=refs.getFirst();
        String id=UUID.nameUUIDFromBytes(("lotc-scene-v1:"+first.sourceId()+":"+first.sourceLine()).getBytes(StandardCharsets.UTF_8)).toString();seen.add(id);
        var old=SceneProjection.header(c,id,false);
        if(old!=null&&old.state()!=SceneState.SUGGESTED)return 0; // A manual tombstone is never resurrected.
        if(old!=null&&old.active()&&refs.equals(SceneProjection.refs(SceneProjection.members(c,id,null))))return 0;
        header(c,id,"Suggested scene","",SceneState.SUGGESTED,old==null?1:Math.addExact(old.revision(),1),false);
        members(c,id,refs);return 1;
    }
    @Override public ScenePage scenes(String persona,boolean includeSkipped,String query,int offset,int limit)throws Exception{
        String filter=query==null?"":query.toLowerCase(Locale.ROOT);
        if(offset<0||limit<1||limit>100||filter.length()>256)throw new IllegalArgumentException("Invalid scene page");
        return read(c->{
            SceneProjection.persona(c,persona);
            if(filter.isEmpty())return summaryPage(c,persona,includeSkipped,offset,limit);
            var entries=new ArrayList<SceneSummary>();long total=0;
            try(var q=c.prepareStatement("SELECT id FROM characters.scenes WHERE active=1 AND (?=1 OR state!='SKIPPED') ORDER BY created_at,id")){
                q.setInt(1,persona!=null||includeSkipped?1:0);try(var r=q.executeQuery()){while(r.next()){
                    var h=SceneProjection.header(c,r.getString(1),true);long count=memberCount(c,h.id(),persona);if(count==0)continue;
                    String title=persona==null?h.title():"Assigned scene",location=persona==null?h.location():"";
                    // Search metadata and stream visible text before asking for actor
                    // names. Listing never needs full scene statistics/projections.
                    boolean match=title.toLowerCase(Locale.ROOT).contains(filter)||location.toLowerCase(Locale.ROOT).contains(filter)
                        ||textMatches(c,h.id(),persona,filter)||participantMatches(c,h,persona,filter);
                    if(!match)continue;if(total>=offset&&entries.size()<limit)entries.add(summary(h,persona,count));total++;
                }}
            }
            return new ScenePage(entries,total,offset,limit);
        });
    }
    private static ScenePage summaryPage(Connection c,String persona,boolean includeSkipped,int offset,int limit)throws SQLException{
        String owned=persona==null?"":" AND EXISTS(SELECT 1 FROM characters.scene_members m JOIN characters.memory_assignments a ON a.source_id=m.source_id AND a.source_line=m.source_line WHERE m.scene_id=s.id AND a.persona_id=?)";
        String scope=" FROM characters.scenes s WHERE s.active=1 AND (?=1 OR s.state!='SKIPPED')"+owned;
        int visibleSkipped=persona!=null||includeSkipped?1:0;long total;
        try(var q=c.prepareStatement("SELECT count(*)"+scope)){
            q.setInt(1,visibleSkipped);if(persona!=null)q.setString(2,persona);try(var r=q.executeQuery()){total=r.getLong(1);}
        }
        String count=persona==null?"(SELECT count(*) FROM characters.scene_members m WHERE m.scene_id=s.id)":"(SELECT count(*) FROM characters.scene_members m JOIN characters.memory_assignments a ON a.source_id=m.source_id AND a.source_line=m.source_line WHERE m.scene_id=s.id AND a.persona_id=?)";
        var entries=new ArrayList<SceneSummary>();
        try(var q=c.prepareStatement("SELECT s.*,"+count+" AS visible_count"+scope+" ORDER BY s.created_at,s.id LIMIT ? OFFSET ?")){
            int at=1;if(persona!=null)q.setString(at++,persona);q.setInt(at++,visibleSkipped);if(persona!=null)q.setString(at++,persona);q.setInt(at++,limit);q.setInt(at,offset);
            try(var r=q.executeQuery()){while(r.next())entries.add(new SceneSummary(r.getString("id"),persona==null?r.getString("title"):"Assigned scene",persona==null?r.getString("location"):"",persona==null?SceneState.valueOf(r.getString("state")):SceneState.SUGGESTED,r.getLong("visible_count"),r.getLong("revision")));}
        }
        return new ScenePage(entries,total,offset,limit);
    }
    private static SceneSummary summary(SceneProjection.Header h,String persona,long count){
        return new SceneSummary(h.id(),persona==null?h.title():"Assigned scene",persona==null?h.location():"",persona==null?h.state():SceneState.SUGGESTED,count,h.revision());
    }
    private static String memberScope(String persona){
        return persona==null?"":" AND EXISTS(SELECT 1 FROM characters.memory_assignments a WHERE a.source_id=m.source_id AND a.source_line=m.source_line AND a.persona_id=?)";
    }
    private static void bindMemberScope(PreparedStatement q,String id,String persona)throws SQLException{q.setString(1,id);if(persona!=null)q.setString(2,persona);}
    private static long memberCount(Connection c,String id,String persona)throws SQLException{
        try(var q=c.prepareStatement("SELECT count(*) FROM characters.scene_members m WHERE m.scene_id=?"+memberScope(persona))){bindMemberScope(q,id,persona);try(var r=q.executeQuery()){return r.getLong(1);}}
    }
    private static boolean textMatches(Connection c,String id,String persona,String filter)throws SQLException{
        try(var q=c.prepareStatement("SELECT t.text FROM characters.scene_members m JOIN main.transcript_lines t ON t.source_id=m.source_id AND t.source_line=m.source_line WHERE m.scene_id=?"+memberScope(persona))){
            bindMemberScope(q,id,persona);try(var r=q.executeQuery()){while(r.next())if(r.getString(1).toLowerCase(Locale.ROOT).contains(filter))return true;}
        }
        return false;
    }
    private static boolean participantMatches(Connection c,SceneProjection.Header h,String persona,String filter)throws SQLException{
        if(persona==null&&h.participantsReviewed())return SceneProjection.manualParticipants(c,h.id()).stream().anyMatch(p->p.name().toLowerCase(Locale.ROOT).contains(filter));
        String sql=persona==null
            ?"SELECT DISTINCT e.identity_id FROM ("+MessageTagReviews.EFFECTIVE_SQL+") e JOIN characters.scene_members m ON m.source_id=e.source_id AND m.source_line=e.source_line WHERE m.scene_id=? AND e.kind IN ('SPEAKER','ACTION')"
            :"SELECT DISTINCT a.identity_id FROM characters.scene_members m JOIN characters.memory_assignments a ON a.source_id=m.source_id AND a.source_line=m.source_line WHERE m.scene_id=? AND a.persona_id=? AND a.role='SPEAKER' AND a.identity_id IS NOT NULL";
        var actors=new HashSet<String>();
        try(var q=c.prepareStatement(sql)){bindMemberScope(q,h.id(),persona);try(var r=q.executeQuery()){while(r.next())actors.add(persona==null?SqliteIdentityRepository.resolve(c,r.getString(1)):r.getString(1));}}
        for(String id:actors)if(SceneProjection.name(c,id).toLowerCase(Locale.ROOT).contains(filter))return true;
        return false;
    }
    @Override public SceneDetail scene(String id,String persona)throws Exception{return read(c->{SceneProjection.persona(c,persona);return SceneProjection.detail(c,SceneProjection.header(c,id,true),persona);});}
    @Override public TranscriptPage transcript(String id,String persona,String query,int offset,int limit)throws Exception{return read(c->SceneProjection.transcript(c,id,persona,query,offset,limit));}
    @Override public SceneDetail saveScene(String id,long expected,String title,String location,List<SceneParticipant> participants)throws Exception{
        var snapshot=participants==null?null:List.copyOf(participants);
        return write(c->{var h=expect(c,id,expected);var rows=SceneProjection.members(c,id,null);
            new SceneSummary(id,title,location,SceneState.CONFIRMED,rows.size(),h.revision());var chosen=checkedParticipants(c,snapshot);
            if(h.state()==SceneState.CONFIRMED&&h.participantsReviewed()&&h.title().equals(title)&&h.location().equals(location)&&new HashSet<>(chosen).equals(new HashSet<>(SceneProjection.manualParticipants(c,id))))return SceneProjection.detail(c,h,null,rows);
            header(c,id,title,location,SceneState.CONFIRMED,Math.addExact(h.revision(),1),true);participants(c,id,chosen);
            return SceneProjection.detail(c,SceneProjection.header(c,id,true),null,rows);
        });
    }
    @Override public void skipScene(String id,long expected)throws Exception{
        write(c->{var h=expect(c,id,expected);if(h.state()!=SceneState.SKIPPED)header(c,id,h.title(),h.location(),SceneState.SKIPPED,Math.addExact(h.revision(),1),h.participantsReviewed());return null;});
    }
    @Override public List<String> splitScene(String id,long expected,MessageReference firstOfSecond)throws Exception{
        Objects.requireNonNull(firstOfSecond);
        return write(c->{var h=expect(c,id,expected);var refs=SceneProjection.refs(SceneProjection.members(c,id,null));int at=refs.indexOf(firstOfSecond);
            if(at<=0)throw new IllegalArgumentException("Split must start at an existing nonfirst member");
            var manual=h.participantsReviewed()?SceneProjection.manualParticipants(c,id):List.<SceneParticipant>of();
            retire(c,h,true);var result=new ArrayList<String>();
            for(int part=0;part<2;part++){String next=UUID.randomUUID().toString();
                header(c,next,h.title(),h.location(),SceneState.CONFIRMED,1,h.participantsReviewed());
                members(c,next,part==0?refs.subList(0,at):refs.subList(at,refs.size()));if(h.participantsReviewed())participants(c,next,manual);result.add(next);
            }
            return List.copyOf(result);
        });
    }
    @Override public String combineScenes(String first,long firstRevision,String second,long secondRevision)throws Exception{
        if(Objects.equals(first,second))throw new IllegalArgumentException("Choose two different scenes");
        return write(c->{var a=expect(c,first,firstRevision);var b=expect(c,second,secondRevision);
            var refs=new TreeSet<MessageReference>(Comparator.comparing(MessageReference::sourceId).thenComparingLong(MessageReference::sourceLine));
            refs.addAll(SceneProjection.refs(SceneProjection.members(c,first,null)));refs.addAll(SceneProjection.refs(SceneProjection.members(c,second,null)));
            if(refs.size()>SceneGrouper.MAX_MESSAGES)throw new IllegalArgumentException("Combined scene exceeds 5000 observations");
            boolean reviewed=a.participantsReviewed()||b.participantsReviewed();var chosen=new LinkedHashMap<String,SceneParticipant>();
            if(reviewed)for(var p:concat(SceneProjection.detail(c,a,null).participants(),SceneProjection.detail(c,b,null).participants())){
                String key=participantKey(p);var prior=chosen.get(key);chosen.put(key,prior!=null&&prior.role()!=p.role()?new SceneParticipant(p.identityId(),p.name(),EncounterRole.UNCERTAIN,p.perspectivePersonaId()):p);
            }
            var manual=reviewed?checkedParticipants(c,new ArrayList<>(chosen.values())):List.<SceneParticipant>of();
            retire(c,a,true);retire(c,b,true);String id=UUID.randomUUID().toString();
            header(c,id,a.title().equals(b.title())?a.title():shorten(a.title()+" + "+b.title(),160),a.location().equals(b.location())?a.location():"",SceneState.CONFIRMED,1,reviewed);
            members(c,id,new ArrayList<>(refs));if(reviewed)participants(c,id,manual);return id;
        });
    }
    private static List<SceneParticipant> concat(List<SceneParticipant> first,List<SceneParticipant> second){var result=new ArrayList<>(first);result.addAll(second);return result;}
    private static SceneProjection.Header expect(Connection c,String id,long expected)throws SQLException{
        if(expected<1)throw new IllegalArgumentException("Invalid scene revision");var h=SceneProjection.header(c,id,true);if(h.revision()!=expected)throw new SQLException("Scene changed; reload before editing");return h;
    }
    private static void header(Connection c,String id,String title,String location,SceneState state,long revision,boolean reviewed)throws SQLException{
        String now=Instant.now().toString();
        try(var q=c.prepareStatement("INSERT INTO characters.scenes VALUES (?,?,?,?,1,?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title,location=excluded.location,state=excluded.state,active=1,revision=excluded.revision,participants_reviewed=excluded.participants_reviewed,updated_at=excluded.updated_at")){
            q.setString(1,id);q.setString(2,title);q.setString(3,location);q.setString(4,state.name());q.setLong(5,revision);q.setInt(6,reviewed?1:0);q.setString(7,now);q.setString(8,now);q.executeUpdate();
        }
    }
    private static void members(Connection c,String id,List<MessageReference> refs)throws SQLException{
        if(refs.isEmpty()||refs.size()>SceneGrouper.MAX_MESSAGES||new HashSet<>(refs).size()!=refs.size())throw new IllegalArgumentException("Invalid scene membership");
        try(var q=c.prepareStatement("DELETE FROM characters.scene_members WHERE scene_id=?")){q.setString(1,id);q.executeUpdate();}
        try(var q=c.prepareStatement("INSERT INTO characters.scene_members VALUES (?,?,?,?)")){
            for(int i=0;i<refs.size();i++){var ref=refs.get(i);q.setString(1,id);q.setInt(2,i);q.setString(3,ref.sourceId());q.setLong(4,ref.sourceLine());q.addBatch();}q.executeBatch();
        }
    }
    private static void retire(Connection c,SceneProjection.Header h,boolean manual)throws SQLException{
        try(var q=c.prepareStatement("UPDATE characters.scenes SET active=0,state=?,revision=?,updated_at=? WHERE id=?")){
            q.setString(1,manual?SceneState.CONFIRMED.name():h.state().name());q.setLong(2,Math.addExact(h.revision(),1));q.setString(3,Instant.now().toString());q.setString(4,h.id());q.executeUpdate();
        }
        try(var q=c.prepareStatement("DELETE FROM characters.scene_members WHERE scene_id=?")){q.setString(1,h.id());q.executeUpdate();}
    }
    private static String participantKey(SceneParticipant p){return p.identityId()+":"+(p.perspectivePersonaId()==null?"":p.perspectivePersonaId());}
    private static List<SceneParticipant> checkedParticipants(Connection c,List<SceneParticipant> values)throws SQLException{
        if(values==null||values.size()>64)throw new IllegalArgumentException("Use at most 64 scene participants");var result=new ArrayList<SceneParticipant>();var seen=new HashSet<String>();
        for(var p:values){if(p==null||!seen.add(participantKey(p)))throw new IllegalArgumentException("Duplicate scene participant perspective");SceneProjection.persona(c,p.perspectivePersonaId());result.add(new SceneParticipant(p.identityId(),SceneProjection.name(c,p.identityId()),p.role(),p.perspectivePersonaId()));}
        return List.copyOf(result);
    }
    private static void participants(Connection c,String id,List<SceneParticipant> values)throws SQLException{
        try(var q=c.prepareStatement("DELETE FROM characters.scene_participants WHERE scene_id=?")){q.setString(1,id);q.executeUpdate();}
        try(var q=c.prepareStatement("INSERT INTO characters.scene_participants VALUES (?,?,?,?)")){
            for(var p:values){q.setString(1,id);q.setString(2,p.identityId());q.setString(3,p.role().name());q.setString(4,p.perspectivePersonaId());q.addBatch();}q.executeBatch();
        }
    }
    private static String shorten(String text,int limit){if(text.length()<=limit)return text;int end=limit;if(end>0&&Character.isHighSurrogate(text.charAt(end-1))&&Character.isLowSurrogate(text.charAt(end)))end--;return text.substring(0,end);}
}
