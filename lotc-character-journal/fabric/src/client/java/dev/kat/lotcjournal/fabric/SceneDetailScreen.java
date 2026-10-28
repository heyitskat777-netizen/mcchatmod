package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.application.*;
import dev.kat.lotcjournal.domain.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Revision-checked scene metadata and exact participants, separate from source observations. */
final class SceneDetailScreen extends Screen {
    private final Screen parent;
    private final SceneService service;
    private final IdentityService identities;
    private final String id,personaId;
    private SceneDetail loaded;
    private String titleDraft="",locationDraft="",status="Loading scene";
    private final List<SceneParticipant> draft=new ArrayList<>();
    private List<SceneSummary> combineChoices=List.of();
    private SceneSummary combineTarget;
    private int combineOffset;
    private boolean pending,stale,confirmCombine;
    private long request;
    private EditBox name,location;
    private Button participants,save,skip,dialogue,reload,choose,combine;
    SceneDetailScreen(Screen parent,SceneService service,IdentityService identities,String id,String personaId) {
        super(Component.literal(personaId==null?"Review scene — shared archive":"Scene — assigned evidence only"));
        this.parent=parent;this.service=service;this.identities=identities;this.id=id;this.personaId=personaId;
    }
    @Override protected void init() {
        int w=Math.min(580,width-32),x=(width-w)/2;
        name=addRenderableWidget(new EditBox(font,x,25,w,20,Component.literal("Scene title")));name.setMaxLength(160);name.setHint(Component.literal("Scene title"));name.setValue(titleDraft);name.setResponder(v->titleDraft=v);
        location=addRenderableWidget(new EditBox(font,x,50,w,20,Component.literal("Location (unknown if empty)")));location.setMaxLength(240);location.setHint(Component.literal("Location unknown — optional manual correction"));location.setValue(locationDraft);location.setResponder(v->locationDraft=v);
        participants=addRenderableWidget(Button.builder(Component.literal("Review participants"),b->minecraft.setScreen(new SceneParticipantScreen(this,identities,draft,personaId==null))).bounds(x,75,w/2-3,20).build());
        dialogue=addRenderableWidget(Button.builder(Component.literal("Scene dialogue / split"),b->minecraft.setScreen(new TranscriptScreen(this,service,identities,id,personaId,loaded.summary().revision()))).bounds(x+w/2+3,75,w/2-3,20).build());
        save=addRenderableWidget(Button.builder(Component.literal("Confirm / save scene"),b->save()).bounds(x,100,w/2-3,20).build());
        skip=addRenderableWidget(Button.builder(Component.literal("Skip for now"),b->skip()).bounds(x+w/2+3,100,w/2-3,20).build());
        choose=addRenderableWidget(Button.builder(Component.literal("Choose scene to combine"),b->choose()).bounds(x,125,w/2-3,20).build());
        combine=addRenderableWidget(Button.builder(Component.literal("Combine scenes"),b->combine()).bounds(x+w/2+3,125,w/2-3,20).build());
        reload=addRenderableWidget(Button.builder(Component.literal("Reload saved scene"),b->load()).bounds(x,150,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose()).bounds(x,height-24,w,20).build());
        update();if(loaded==null&&!pending)load();
        else if(loaded!=null&&!pending) {
            long token=++request;service.scene(id,personaId).whenComplete((value,error)->minecraft.execute(()->{
                if(token!=request||minecraft.screen!=this)return;
                if(error!=null||value.summary().revision()!=loaded.summary().revision()){stale=true;status="Scene changed; draft kept. Reload required";}
                else {
                    boolean participantDirty=!new HashSet<>(draft).equals(new HashSet<>(loaded.participants()));
                    loaded=value;if(!participantDirty){draft.clear();draft.addAll(value.participants());}
                }update();
            }));
        }
    }
    private void load() {
        long token=++request;pending=true;status="Loading saved scene; reload discards this draft";update();
        service.scene(id,personaId).whenComplete((value,error)->minecraft.execute(()->{
            if(token!=request||minecraft.screen!=this)return;pending=false;
            if(error!=null){stale=true;status="Scene unavailable or changed; reload or return to scenes";}
            else {loaded=value;stale=false;titleDraft=value.summary().title();locationDraft=value.summary().location();draft.clear();draft.addAll(value.participants());name.setValue(titleDraft);location.setValue(locationDraft);status=personaId!=null?"Explicitly assigned evidence; shared grouping confidence is hidden":value.summary().origin()==SceneOrigin.AUTO?(value.assessment().highConfidence()?"Automatically confirmed grouping; Save to keep this exact scene":"Suggested: "+String.join("; ",value.assessment().reasons())):"User-reviewed scene; corrections stay fixed";}
            combineTarget=null;combineChoices=List.of();confirmCombine=false;update();
        }));
    }
    private void save() {
        long token=++request;pending=true;status="Saving reviewed scene";update();
        service.saveScene(id,loaded.summary().revision(),titleDraft,locationDraft,draft).whenComplete((value,error)->minecraft.execute(()->{
            if(token!=request||minecraft.screen!=this)return;pending=false;
            if(error!=null){stale=true;status="Save failed/changed elsewhere. Draft kept; reload before saving";}
            else {loaded=value;draft.clear();draft.addAll(value.participants());status="Scene confirmed; source dialogue and persona knowledge unchanged";}
            update();
        }));
    }
    private void skip() {
        long token=++request;pending=true;update();
        service.skipScene(id,loaded.summary().revision()).whenComplete((value,error)->minecraft.execute(()->{
            if(token!=request||minecraft.screen!=this)return;pending=false;
            if(error!=null){stale=true;status="Skip failed/changed elsewhere; reload";update();}else onClose();
        }));
    }
    private void choose() {
        if(!combineChoices.isEmpty()) {
            int at=combineChoices.indexOf(combineTarget);if(at+1<combineChoices.size()){combineTarget=combineChoices.get(at+1);confirmCombine=false;update();return;}
            combineOffset+=100;
        }
        long token=++request;pending=true;update();
        service.scenes(null,false,"",Math.max(0,combineOffset),100).whenComplete((value,error)->minecraft.execute(()->{
            if(token!=request||minecraft.screen!=this)return;pending=false;
            if(error!=null){status="Combine choices unavailable; retry";combineChoices=List.of();combineTarget=null;}
            else {combineChoices=value.entries().stream().filter(s->!s.id().equals(id)).toList();combineTarget=combineChoices.isEmpty()?null:combineChoices.getFirst();
                if(value.offset()+value.entries().size()>=value.total())combineOffset=-100;
                if(combineTarget==null)status="No other scene on this page; choose again for more";}
            confirmCombine=false;update();
        }));
    }
    private void combine() {
        if(!confirmCombine){confirmCombine=true;status="Confirm combining saved scenes. Unsaved drafts are discarded; review result";update();return;}
        long token=++request;pending=true;update();
        service.combineScenes(id,loaded.summary().revision(),combineTarget.id(),combineTarget.revision()).whenComplete((newId,error)->minecraft.execute(()->{
            if(token!=request||minecraft.screen!=this)return;pending=false;
            if(error!=null){
                Throwable cause=error;while(cause.getCause()!=null&&(cause instanceof java.util.concurrent.CompletionException||cause instanceof java.util.concurrent.ExecutionException))cause=cause.getCause();
                if(cause instanceof SceneCreationException rejected)status=rejected.reason()==SceneCreationException.Reason.MINIMUM?"Combine needs 10 RP messages and 2 observed actors":"Combined scenes exceed 5000 messages";
                else {stale=true;status="Combine failed/changed elsewhere; reload saved scenes";}
                confirmCombine=false;update();
            }
            else minecraft.setScreen(new SceneDetailScreen(parent,service,identities,newId,null));
        }));
    }
    void openSplit(List<String> ids){minecraft.setScreen(new SceneDetailScreen(parent,service,identities,ids.getFirst(),null));}

    private void update() {
        if(save==null)return;boolean ready=loaded!=null&&!pending,editable=ready&&!stale&&personaId==null;
        name.active=location.active=editable;participants.active=ready;dialogue.active=ready&&!stale;
        save.active=editable&&!titleDraft.isBlank()&&draft.size()<=64;
        save.setTooltip(Tooltip.create(Component.literal(loaded==null?"Loading scene":personaId!=null?"Assigned evidence only":(loaded.summary().origin()==SceneOrigin.AUTO?"Automatic grouping confidence. Explicit Save freezes this exact scene.\n":"User-reviewed membership is protected.\n")+String.join("\n",loaded.assessment().reasons()))));skip.active=choose.active=editable;combine.active=editable&&combineTarget!=null;
        reload.active=!pending;combine.setMessage(Component.literal(confirmCombine?"Confirm combine":"Combine scenes"));
        choose.setMessage(Component.literal(combineTarget==null?"Choose scene to combine":font.plainSubstrByWidth("#"+combineTarget.id().substring(0,8)+" "+combineTarget.title(),choose.getWidth()-12)));
        choose.setTooltip(combineTarget==null?null:Tooltip.create(Component.literal(combineTarget.id()+"\n"+combineTarget.title()+"\n"+combineTarget.messageCount()+" messages")));
        participants.setMessage(Component.literal("Participants: "+draft.size()+" (review)"));
    }
    @Override public void tick(){update();}
    @Override public void render(GuiGraphics graphics,int x,int y,float delta) {
        super.render(graphics,x,y,delta);graphics.drawCenteredString(font,title,width/2,8,0xFFE5E7EB);int w=Math.min(580,width-32),left=(width-w)/2;
        if(loaded!=null){var stats=loaded.statistics();
            String counts=loaded.summary().state()+" | "+stats.messages()+" msgs | "+(w<400
                ? "S:"+stats.speechObservations()+" A:"+stats.actionObservations()+" M/R:"+stats.mentionObservations()
                : "Speech "+stats.speechObservations()+" | Actions "+stats.actionObservations()+" | Mentions/refs "+stats.mentionObservations());
            graphics.drawString(font,font.plainSubstrByWidth(counts,w),left,175,0xFF93C5FD);
            graphics.drawString(font,font.plainSubstrByWidth("Elapsed "+time(stats.elapsedSeconds())+" | Active ~"+time(stats.estimatedActiveSeconds())+" (gap cap 60s)",w),left,187,0xFF9CA3AF);}
        graphics.drawString(font,font.plainSubstrByWidth(stale?"Draft kept. Reload required; scene may have changed":draft.size()>64?"Save limit: 64 participants. Remove extras to save":status,w),left,201,0xFF93C5FD);
    }
    private static String time(Long seconds){return seconds==null?"unknown":seconds+"s";}
    @Override public Component getNarrationMessage(){return Component.literal(title.getString()+". "+status+(loaded==null||personaId!=null?"":". "+loaded.summary().origin()+" grouping. "+String.join(". ",loaded.assessment().reasons()))+(stale?". Reload required":"")+(loaded==null?"":". "+loaded.summary().state()+". Messages "+loaded.statistics().messages()+". Speech observations "+loaded.statistics().speechObservations()+". Action observations "+loaded.statistics().actionObservations()+". Mentions or references "+loaded.statistics().mentionObservations()+". Elapsed "+time(loaded.statistics().elapsedSeconds())+". Estimated active time "+time(loaded.statistics().estimatedActiveSeconds())+", adjacent gaps capped at 60 seconds. Participants "+draft.size()+". Location "+(locationDraft.isBlank()?"unknown":locationDraft)));}
    @Override public void onClose(){request++;minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
