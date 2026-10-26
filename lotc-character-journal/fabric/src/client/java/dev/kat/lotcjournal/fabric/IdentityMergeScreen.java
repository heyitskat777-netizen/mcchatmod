package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.application.IdentityService;
import dev.kat.lotcjournal.domain.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Linking is an explicit reversible overlay, never destructive record consolidation. */
final class IdentityMergeScreen extends Screen {
    private final Screen parent;private final IdentityService service;
    private List<CharacterIdentity> values=List.of();private List<IdentityMerge> decisions=List.of();
    private int from,to,decision;private Button fromButton,toButton,confirm,undo,history;
    private boolean pending=true;private String status="Loading decisions";
    IdentityMergeScreen(Screen parent,IdentityService service){super(Component.literal("Confirm identity links"));this.parent=parent;this.service=service;}
    @Override protected void init(){int w=Math.min(500,width-32),x=(width-w)/2;
        fromButton=addRenderableWidget(Button.builder(Component.literal("From"),b->{from=(from+1)%values.size();tick();}).bounds(x,35,w,20).build());
        toButton=addRenderableWidget(Button.builder(Component.literal("To"),b->{to=(to+1)%values.size();tick();}).bounds(x,60,w,20).build());
        confirm=addRenderableWidget(Button.builder(Component.literal("Confirm these are the same identity"),b->link()).bounds(x,85,w,20).build());
        history=addRenderableWidget(Button.builder(Component.literal("Previous decisions"),b->{decision=(decision+1)%decisions.size();tick();}).bounds(x,120,w,20).build());
        undo=addRenderableWidget(Button.builder(Component.literal("Undo selected link"),b->undo()).bounds(x,145,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose()).bounds(x,height-24,w,20).build());load();
    }
    private void load(){pending=true;tick();service.identities("").thenCombine(service.merges(),(a,b)->new Object[]{a,b}).whenComplete((v,e)->minecraft.execute(()->{
        if(minecraft.screen!=this)return;pending=false;if(e!=null)status="Identity decisions unavailable";
        else {values=(List<CharacterIdentity>)v[0];decisions=((List<IdentityMerge>)v[1]).stream().filter(IdentityMerge::active).toList();from=0;to=values.size()>1?1:0;decision=0;status="No dialogue or persona memories are combined; only an identity link is confirmed";}tick();}));}
    private String label(int index){var v=values.get(index);return v.name()+" #"+v.id().substring(0,8);}
    private void link(){pending=true;tick();service.mergeIdentity(values.get(from).id(),values.get(to).id()).whenComplete((v,e)->minecraft.execute(()->{if(minecraft.screen!=this)return;if(e!=null){pending=false;status="Link rejected: already linked, same identity, or cycle";tick();}else load();}));}
    private void undo(){pending=true;tick();service.undoMerge(decisions.get(decision).id()).whenComplete((v,e)->minecraft.execute(()->{if(minecraft.screen!=this)return;if(e!=null){pending=false;status="Link could not be undone";tick();}else load();}));}
    @Override public void tick(){if(confirm==null)return;fromButton.active=toButton.active=!pending&&!values.isEmpty();confirm.active=!pending&&values.size()>1&&from!=to;history.active=undo.active=!pending&&!decisions.isEmpty();
        if(!values.isEmpty()){fromButton.setMessage(Component.literal("Link from: "+label(from)));toButton.setMessage(Component.literal("Link to: "+label(to)));}
        history.setMessage(Component.literal(decisions.isEmpty()?"No active links": "Decision "+(decision+1)+" / "+decisions.size()+": #"+decisions.get(decision).id().substring(0,8)));}
    @Override public void render(GuiGraphics g,int x,int y,float delta){super.render(g,x,y,delta);g.drawCenteredString(font,title,width/2,12,0xFFE5E7EB);g.drawString(font,font.plainSubstrByWidth(status,width-32),16,178,0xFF93C5FD);}
    @Override public Component getNarrationMessage(){return Component.literal(title.getString()+". "+status);}
    @Override public void onClose(){minecraft.setScreen(parent);}@Override public boolean isPauseScreen(){return false;}
}
