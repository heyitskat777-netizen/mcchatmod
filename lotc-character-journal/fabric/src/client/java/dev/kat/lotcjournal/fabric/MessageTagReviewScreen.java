package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.application.IdentityService;
import dev.kat.lotcjournal.domain.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** An explicit exact tag set over one immutable message; separate from persona assignment. */
final class MessageTagReviewScreen extends Screen {
    private final TranscriptScreen parent;
    private final IdentityService service;
    private final MessageReference message;
    private final List<CharacterTag> draft=new ArrayList<>();
    private final Map<String,String> names=new HashMap<>();
    private List<CharacterIdentity> choices=List.of();
    private MessageTagReview loaded;
    private String query="",loadedQuery,identityId,status="Loading message tags";
    private CharacterEvidenceKind kind=CharacterEvidenceKind.MENTION;
    private int selected,queryDelay;
    private long request,lookup;
    private boolean pending,queryPending,refreshRequired;
    private EditBox search;
    private Button identity,kindButton,tags,add,replace,remove,save,reject,restore,reload;

    MessageTagReviewScreen(TranscriptScreen parent,IdentityService service,MessageReference message) {
        super(Component.literal("Review message tags — shared archive"));this.parent=parent;this.service=service;this.message=message;
    }
    @Override protected void init() {
        int w=Math.min(560,width-32),x=(width-w)/2;
        search=addRenderableWidget(new EditBox(font,x,35,w,20,Component.literal("Find a character")));
        search.setMaxLength(256);search.setHint(Component.literal("Search character names and aliases"));search.setValue(query);
        search.setResponder(value->{query=value;queryDelay=3;queryPending=true;identityId=null;update();});
        identity=addRenderableWidget(Button.builder(Component.empty(),b->chooseIdentity()).bounds(x,60,w-105,20).build());
        kindButton=addRenderableWidget(Button.builder(Component.empty(),b->{kind=CharacterEvidenceKind.values()[(kind.ordinal()+1)%CharacterEvidenceKind.values().length];if(!kindAllowed())kind=CharacterEvidenceKind.MENTION;update();}).bounds(x+w-100,60,100,20).build());
        tags=addRenderableWidget(Button.builder(Component.empty(),b->{selected=(selected+1)%draft.size();editSelected();update();}).bounds(x,85,w,20).build());
        int third=w/3;
        add=addRenderableWidget(Button.builder(Component.literal("Add tag"),b->edit(false)).bounds(x,110,third-3,20).build());
        replace=addRenderableWidget(Button.builder(Component.literal("Replace"),b->edit(true)).bounds(x+third,110,third-3,20).build());
        remove=addRenderableWidget(Button.builder(Component.literal("Remove"),b->{draft.remove(selected);selected=Math.min(selected,Math.max(0,draft.size()-1));editSelected();status="Draft changed; Save reviewed tags to persist";update();}).bounds(x+2*third,110,w-2*third,20).build());
        save=addRenderableWidget(Button.builder(Component.literal("Save reviewed tags"),b->save(false)).bounds(x,135,w/2-3,20).build());
        reject=addRenderableWidget(Button.builder(Component.literal("Reject all tags"),b->save(true)).bounds(x+w/2+3,135,w/2-3,20).build());
        restore=addRenderableWidget(Button.builder(Component.literal("Restore automatic"),b->restore()).bounds(x,160,w/2-3,20).build());
        reload=addRenderableWidget(Button.builder(Component.literal("Reload saved tags"),b->load()).bounds(x+w/2+3,160,w/2-3,20).build());
        save.setTooltip(Tooltip.create(Component.literal("Save this exact reviewed tag list. Later automatic updates stay hidden until Restore automatic. Persona memories and source dialogue stay unchanged.")));
        reject.setTooltip(Tooltip.create(Component.literal("Persist an empty reviewed list for this message; future reindex/import will not restore its automatic tags.")));
        restore.setTooltip(Tooltip.create(Component.literal("Discard this draft and resume current automatic tags. Does not assign any persona memory.")));
        addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose()).bounds(x,height-24,w,20).build());
        update();if(loaded==null&&!pending)load();if(loadedQuery==null&&!queryPending)search();
    }
    private void load() {
        long token=++request;pending=true;status="Loading saved tags; explicit reload replaces the draft";update();
        service.reviewTags(message).whenComplete((value,error)->minecraft.execute(()->{
            if(token!=request||minecraft.screen!=this)return;pending=false;
            if(error!=null)status="Tags unavailable; message may have been removed. Reload to retry";
            else {accept(value);status="Review shared character tags; persona memories stay unchanged";}
            update();
        }));
    }
    private void accept(MessageTagReview value) {
        loaded=value;refreshRequired=false;names.putAll(value.identityNames());draft.clear();draft.addAll(value.effectiveTags());
        selected=Math.min(selected,Math.max(0,draft.size()-1));editSelected();
    }
    private void search() {
        long token=++lookup;String requested=query;queryPending=true;
        service.identities(requested).whenComplete((values,error)->minecraft.execute(()->{
            if(token!=lookup||!requested.equals(query)||minecraft.screen!=this)return;
            queryPending=false;loadedQuery=requested;
            if(error!=null){choices=List.of();status="Character search unavailable; change search to retry";}
            else {choices=values;for(var value:values)names.put(value.id(),value.name());}
            update();
        }));
    }
    private void chooseIdentity() {
        int at=-1;for(int i=0;i<choices.size();i++)if(choices.get(i).id().equals(identityId)){at=i;break;}
        identityId=at+1<choices.size()?choices.get(at+1).id():null;update();
    }
    private void editSelected() {if(!draft.isEmpty()){var tag=draft.get(selected);identityId=tag.identityId();kind=tag.kind();}}
    private boolean kindAllowed() {return loaded!=null&&(kind==CharacterEvidenceKind.MENTION||loaded.message().channel()==Channel.ROLEPLAY);}
    private boolean dirty() {return loaded!=null&&!Set.copyOf(draft).equals(Set.copyOf(loaded.effectiveTags()));}
    private void edit(boolean replacement) {
        var tag=new CharacterTag(identityId,kind);
        if(draft.contains(tag)&&(!replacement||!draft.get(selected).equals(tag))){status="That tag is already in the draft";update();return;}
        if(replacement)draft.set(selected,tag);else {draft.add(tag);selected=draft.size()-1;}
        status="Draft changed; Save reviewed tags to persist";update();
    }
    private void save(boolean empty) {
        if(pending||loaded==null||refreshRequired)return;
        long token=++request;long revision=loaded.revision();List<CharacterTag> values=empty?List.of():List.copyOf(draft);
        pending=true;status="Saving reviewed tags";update();
        service.saveReviewedTags(message,revision,values).whenComplete((value,error)->minecraft.execute(()->{
            if(token!=request||minecraft.screen!=this)return;pending=false;
            if(error!=null){refreshRequired=true;status="Save failed or review changed elsewhere. Draft kept; Reload saved tags to refresh";}
            else {accept(value);status=empty?"All tags rejected for this message; restore automatic to reverse":"Reviewed tags saved; persona memories unchanged";}
            update();
        }));
    }
    private void restore() {
        if(pending||loaded==null||refreshRequired)return;long token=++request;pending=true;status="Restoring automatic tags";update();
        service.restoreAutomaticTags(message,loaded.revision()).whenComplete((value,error)->minecraft.execute(()->{
            if(token!=request||minecraft.screen!=this)return;pending=false;
            if(error!=null){refreshRequired=true;status="Restore failed or review changed elsewhere. Draft kept; Reload saved tags to refresh";}
            else {accept(value);status="Automatic tags restored; no persona memories assigned";}
            update();
        }));
    }
    private String label(CharacterTag tag) {return names.getOrDefault(tag.identityId(),"#"+tag.identityId().substring(0,8))+" — "+tag.kind();}
    private void update() {
        if(save==null)return;
        boolean ready=loaded!=null&&!pending;
        search.active=!pending;identity.active=ready&&!queryPending&&!choices.isEmpty();kindButton.active=ready&&loaded.message().channel()==Channel.ROLEPLAY;
        identity.setMessage(Component.literal(identityId==null?(queryPending?"Searching characters":"Choose character (click)"):names.getOrDefault(identityId,"Character")+" #"+identityId.substring(0,8)));
        identity.setTooltip(identityId==null?null:Tooltip.create(Component.literal(names.getOrDefault(identityId,"Character")+"\nIdentity: "+identityId)));
        kindButton.setMessage(Component.literal(kind.name()));
        tags.active=ready&&!draft.isEmpty();tags.setMessage(Component.literal(draft.isEmpty()?"No tags in draft":font.plainSubstrByWidth("Tag "+(selected+1)+" / "+draft.size()+": "+label(draft.get(selected)),tags.getWidth()-12)));
        tags.setTooltip(draft.isEmpty()?null:Tooltip.create(Component.literal(label(draft.get(selected))+"\nIdentity: "+draft.get(selected).identityId())));
        add.active=ready&&!queryPending&&identityId!=null&&kindAllowed()&&draft.size()<64;
        replace.active=ready&&!queryPending&&identityId!=null&&kindAllowed()&&!draft.isEmpty();remove.active=ready&&!draft.isEmpty();
        save.active=ready&&!refreshRequired&&draft.size()<=64;reject.active=ready&&!refreshRequired;restore.active=ready&&!refreshRequired&&(loaded.overridden()||dirty());reload.active=!pending;
    }
    @Override public void tick() {if(queryDelay>0&&--queryDelay==0)search();update();}
    @Override public void render(GuiGraphics graphics,int x,int y,float delta) {
        super.render(graphics,x,y,delta);graphics.drawCenteredString(font,title,width/2,8,0xFFE5E7EB);
        int w=Math.min(560,width-32),left=(width-w)/2;
        String mode=loaded==null?"Loading":(loaded.overridden()?"Reviewed":"Automatic")+" | saved "+loaded.effectiveTags().size()+" | draft "+draft.size()+(dirty()?" (unsaved)":"");
        graphics.drawString(font,font.plainSubstrByWidth("Message #"+message.sourceLine()+" | "+mode,w),left,24,0xFF93C5FD);
        String guidance=refreshRequired?"Draft kept. Reload saved tags before saving":draft.size()>64?"Save limit: 64 tags. Remove extras or Reject all tags":status;
        graphics.drawString(font,font.plainSubstrByWidth(guidance,w),left,185,0xFF93C5FD);
        if(loaded!=null)graphics.drawString(font,font.plainSubstrByWidth(loaded.message().channel()+": "+loaded.message().text(),w),left,199,0xFF9CA3AF);
    }
    @Override public Component getNarrationMessage() {
        return Component.literal(title.getString()+". "+status+". "+(loaded==null?"Loading":(loaded.overridden()?"Reviewed":"Automatic")+" saved tags "+loaded.effectiveTags().size()+". Draft tags "+draft.size()+". Revision "+loaded.revision())
            +(refreshRequired?". Reload saved tags required":"")+". "+draft.stream().map(this::label).collect(java.util.stream.Collectors.joining(". ")));
    }
    @Override public void onClose(){request++;lookup++;parent.refreshTags();minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
