package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.application.IdentityService;
import dev.kat.lotcjournal.domain.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Edits the parent scene draft only; explicit scene Save is the persistence boundary. */
final class SceneParticipantScreen extends Screen {
    private final Screen parent;
    private final IdentityService identities;
    private final List<SceneParticipant> draft;
    private final boolean editable;
    private List<CharacterIdentity> choices=List.of();
    private List<Persona> personas=List.of();
    private final Map<String,String> names=new HashMap<>();
    private String query="",identityId,perspectiveId,status="Automatic actors are suggestions; mentions alone are not participants";
    private EncounterRole role=EncounterRole.UNCERTAIN;
    private int selected,delay;
    private long request;
    private boolean pending;
    private Button choice,current,roleButton,perspective,add,remove,clear;
    SceneParticipantScreen(Screen parent,IdentityService identities,List<SceneParticipant> draft,boolean editable) {
        super(Component.literal(editable?"Review scene participants — draft":"Participants — assigned evidence only"));
        this.parent=parent;this.identities=identities;this.draft=draft;this.editable=editable;
        for(var value:draft)names.put(value.identityId(),value.name());
    }
    @Override protected void init() {
        int w=Math.min(580,width-32),x=(width-w)/2;
        var search=addRenderableWidget(new EditBox(font,x,24,w,20,Component.literal("Find participant")));search.setMaxLength(256);search.setHint(Component.literal("Find character names / aliases"));search.setValue(query);
        search.setResponder(v->{query=v;identityId=null;pending=true;delay=3;update();});search.active=editable;
        choice=addRenderableWidget(Button.builder(Component.empty(),b->choose()).bounds(x,49,w,20).build());
        current=addRenderableWidget(Button.builder(Component.empty(),b->{selected=(selected+1)%draft.size();select();update();}).bounds(x,74,w,20).build());
        roleButton=addRenderableWidget(Button.builder(Component.empty(),b->{role=EncounterRole.values()[(role.ordinal()+1)%EncounterRole.values().length];if(role==EncounterRole.UNCERTAIN)perspectiveId=null;update();}).bounds(x,99,w/2-3,20).build());
        perspective=addRenderableWidget(Button.builder(Component.empty(),b->cyclePerspective()).bounds(x+w/2+3,99,w/2-3,20).build());
        add=addRenderableWidget(Button.builder(Component.literal("Add / update participant"),b->add()).bounds(x,124,w/2-3,20).build());
        remove=addRenderableWidget(Button.builder(Component.literal("Remove selected"),b->{draft.remove(selected);selected=Math.min(selected,Math.max(0,draft.size()-1));select();status="Draft changed; return and Confirm / save scene";update();}).bounds(x+w/2+3,124,w/2-3,20).build());
        clear=addRenderableWidget(Button.builder(Component.literal("Clear participants (draft)"),b->{draft.clear();selected=0;status="Empty participant draft; save the scene to confirm";update();}).bounds(x,149,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose()).bounds(x,height-24,w,20).build());
        identities.personas().whenComplete((value,error)->minecraft.execute(()->{if(minecraft.screen!=this)return;if(error==null)personas=value;update();}));
        select();search();update();
    }
    private void select(){if(!draft.isEmpty()){var value=draft.get(selected);identityId=value.identityId();role=value.role();perspectiveId=value.perspectivePersonaId();}}
    private void search(){long token=++request;String filter=query;pending=true;update();identities.identities(filter).whenComplete((value,error)->minecraft.execute(()->{
        if(token!=request||!query.equals(filter)||minecraft.screen!=this)return;pending=false;
        if(error!=null){choices=List.of();status="Character search unavailable; change query to retry";}else {choices=value;for(var identity:value)names.put(identity.id(),identity.name());}update();
    }));}
    private void choose(){int index=-1;for(int i=0;i<choices.size();i++)if(choices.get(i).id().equals(identityId))index=i;identityId=index+1<choices.size()?choices.get(index+1).id():null;update();}
    private void cyclePerspective(){int index=-1;for(int i=0;i<personas.size();i++)if(personas.get(i).id().equals(perspectiveId))index=i;perspectiveId=index+1<personas.size()?personas.get(index+1).id():null;update();}
    private void add(){var value=new SceneParticipant(identityId,names.getOrDefault(identityId,"Character"),role,perspectiveId);
        int existing=-1;for(int i=0;i<draft.size();i++)if(draft.get(i).identityId().equals(identityId)&&Objects.equals(draft.get(i).perspectivePersonaId(),perspectiveId))existing=i;
        if(existing>=0){draft.set(existing,value);selected=existing;}else {draft.add(value);selected=draft.size()-1;}
        status="Participant draft changed; return and Confirm / save scene";update();
    }
    private void update(){if(add==null)return;
        choice.active=editable&&!pending&&!choices.isEmpty();choice.setMessage(Component.literal(identityId==null?(pending?"Searching characters":"Choose participant (click)"):names.getOrDefault(identityId,"Character")+" #"+identityId.substring(0,8)));
        choice.setTooltip(identityId==null?null:Tooltip.create(Component.literal(names.getOrDefault(identityId,"Character")+"\n"+identityId)));
        current.active=!draft.isEmpty();current.setMessage(Component.literal(draft.isEmpty()?"No participants in draft":font.plainSubstrByWidth("Participant "+(selected+1)+" / "+draft.size()+": "+draft.get(selected).name()+" — "+draft.get(selected).role(),current.getWidth()-12)));
        current.setTooltip(draft.isEmpty()?null:Tooltip.create(Component.literal(draft.get(selected).identityId()+"\nPerspective: "+Objects.toString(draft.get(selected).perspectivePersonaId(),"Unassigned"))));
        roleButton.active=editable;roleButton.setMessage(Component.literal("Relation: "+role));perspective.active=editable&&!personas.isEmpty();
        String label=personas.stream().filter(p->p.id().equals(perspectiveId)).map(Persona::label).findFirst().orElse("Choose persona");
        perspective.setMessage(Component.literal(font.plainSubstrByWidth("Perspective: "+label+(perspectiveId==null?"":" #"+perspectiveId.substring(0,8)),perspective.getWidth()-12)));
        perspective.setTooltip(Tooltip.create(Component.literal("Direct/overheard is a manual interpretation from this explicit persona's perspective. It grants no memory assignments.\nPersona: "+Objects.toString(perspectiveId,"Unassigned")+"")));
        add.active=editable&&!pending&&identityId!=null&&(role==EncounterRole.UNCERTAIN||perspectiveId!=null)&&(draft.size()<64||draft.stream().anyMatch(p->p.identityId().equals(identityId)&&Objects.equals(p.perspectivePersonaId(),perspectiveId)));
        remove.active=editable&&!draft.isEmpty();clear.active=editable&&!draft.isEmpty();
    }
    @Override public void tick(){if(delay>0&&--delay==0)search();}
    @Override public void render(GuiGraphics graphics,int x,int y,float delta){super.render(graphics,x,y,delta);graphics.drawCenteredString(font,title,width/2,8,0xFFE5E7EB);int w=Math.min(580,width-32),left=(width-w)/2;graphics.drawString(font,font.plainSubstrByWidth(status,w),left,177,0xFF93C5FD);graphics.drawString(font,font.plainSubstrByWidth("Direct/overheard requires an explicit persona; no knowledge is granted",w),left,191,0xFF9CA3AF);}
    @Override public Component getNarrationMessage(){return Component.literal(title.getString()+". "+status+". Participants "+draft.size()+". "+draft.stream().map(p->p.name()+" "+p.role()).collect(java.util.stream.Collectors.joining(". ")));}
    @Override public void onClose(){request++;minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
