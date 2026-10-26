package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.application.IdentityService;
import dev.kat.lotcjournal.domain.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A review prompt for one immutable message; uncertain attribution can stay unassigned. */
final class MemoryAssignmentScreen extends Screen {
    private final Screen parent;private final IdentityService service;private final MessageReference message;
    private List<CharacterIdentity> identities=List.of();private List<Persona> personas=List.of();
    private int identity=-1,persona=-1;private AttributionRole role=AttributionRole.REFERENCED;
    private Button identityButton,personaButton,roleButton,confirm,clear;
    private boolean pending=true;private String status="Loading reviewed choices";private long request;
    MemoryAssignmentScreen(Screen parent,IdentityService service,MessageReference message){super(Component.literal("Review message identity / memory"));this.parent=parent;this.service=service;this.message=message;}
    @Override protected void init(){int w=Math.min(500,width-32),x=(width-w)/2;
        personaButton=addRenderableWidget(Button.builder(Component.literal("Select owning persona"),b->{persona=persona+1>=personas.size()?-1:persona+1;read();}).bounds(x,35,w,20).build());
        identityButton=addRenderableWidget(Button.builder(Component.literal("Identity: unassigned"),b->{identity=identity+1>=identities.size()?-1:identity+1;tick();}).bounds(x,60,w,20).build());
        roleButton=addRenderableWidget(Button.builder(Component.literal("Role: REFERENCED"),b->{role=role==AttributionRole.REFERENCED?AttributionRole.SPEAKER:AttributionRole.REFERENCED;tick();}).bounds(x,85,w,20).build());
        confirm=addRenderableWidget(Button.builder(Component.literal("Confirm assignment"),b->save()).bounds(x,110,w,20).build());
        clear=addRenderableWidget(Button.builder(Component.literal("Clear this persona assignment"),b->clear()).bounds(x,135,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("Skip / Done"),b->onClose()).bounds(x,height-24,w,20).build());
        tick();
        service.identities("").thenCombine(service.personas(),(a,b)->new Object[]{a,b}).whenComplete((v,e)->minecraft.execute(()->{
            if(minecraft.screen!=this)return;pending=false;
            if(e!=null)status="Identity choices unavailable";
            else {identities=(List<CharacterIdentity>)v[0];personas=(List<Persona>)v[1];status=personas.isEmpty()?"Create a character and Use as persona first":"Choose a persona; uncertain identity may stay unassigned";}
            tick();}));
    }
    private void read(){identity=-1;role=AttributionRole.REFERENCED;long token=++request;
        if(persona<0){status="Choose the persona who knows this message";tick();return;}
        pending=true;tick();service.assignment(message,personas.get(persona).id()).whenComplete((value,e)->minecraft.execute(()->{
            if(token!=request||minecraft.screen!=this)return;pending=false;
            if(e!=null)status="Assignment unavailable";
            else if(value!=null){for(int i=0;i<identities.size();i++)if(identities.get(i).id().equals(value.identityId()))identity=i;role=value.role();status="Existing assignment loaded; confirm changes or clear to reverse";}
            else status="Confirm only what this persona knows; identity can remain unassigned";tick();}));}
    private void save(){if(persona<0)return;pending=true;tick();var value=new MemoryAssignment(message,personas.get(persona).id(),identity<0?null:identities.get(identity).id(),role);
        service.assign(value).whenComplete((v,e)->minecraft.execute(()->{if(minecraft.screen!=this)return;pending=false;status=e==null?"Assignment confirmed; source dialogue unchanged":"Assignment failed; message may have been removed";tick();}));}
    private void clear(){pending=true;tick();service.clearAssignment(message,personas.get(persona).id()).whenComplete((v,e)->minecraft.execute(()->{if(minecraft.screen!=this)return;pending=false;identity=-1;status=e==null?"Assignment cleared; source and favorite retained":"Assignment could not be cleared";tick();}));}
    @Override public void tick(){if(confirm==null)return;personaButton.active=!pending&&!personas.isEmpty();identityButton.active=!pending&&!identities.isEmpty();roleButton.active=!pending;confirm.active=clear.active=!pending&&persona>=0;
        personaButton.setMessage(Component.literal(persona<0?"Select owning persona": "Memory of: "+personas.get(persona).label()+" #"+personas.get(persona).id().substring(0,6)));
        identityButton.setMessage(Component.literal(identity<0?"Identity: unassigned (click to choose)":"Identity: "+identities.get(identity).name()+" #"+identities.get(identity).id().substring(0,6)));
        roleButton.setMessage(Component.literal("Role: "+role));confirm.setMessage(Component.literal(identity<0?"Confirm memory; keep identity unassigned":"Confirm assignment"));}
    @Override public void render(GuiGraphics g,int x,int y,float delta){super.render(g,x,y,delta);g.drawCenteredString(font,title,width/2,8,0xFFE5E7EB);g.drawString(font,"Message #"+message.sourceLine()+" / "+message.sourceId().substring(0,12),16,24,0xFF9CA3AF);g.drawString(font,font.plainSubstrByWidth(status,width-32),16,166,0xFF93C5FD);}
    @Override public Component getNarrationMessage(){return Component.literal(title.getString()+". "+status);}
    @Override public void onClose(){request++;minecraft.setScreen(parent);}@Override public boolean isPauseScreen(){return false;}
}
