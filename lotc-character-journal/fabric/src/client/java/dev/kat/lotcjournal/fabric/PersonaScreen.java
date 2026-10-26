package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.application.IdentityService;
import dev.kat.lotcjournal.domain.Persona;
import dev.kat.lotcjournal.ingestion.LogImportService;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class PersonaScreen extends Screen {
    private final Screen parent;private final IdentityService service;private final LogImportService importer;
    private List<Persona> values=List.of();private int index;private boolean pending=true;
    private String status="Loading persona choices";private Button choice,select,view;
    PersonaScreen(Screen parent,IdentityService service,LogImportService importer){super(Component.literal("Persona memories — explicit assignments only"));this.parent=parent;this.service=service;this.importer=importer;}
    @Override protected void init(){int w=Math.min(500,width-32),x=(width-w)/2;
        choice=addRenderableWidget(Button.builder(Component.literal("Choose persona"),b->{index=(index+1)%values.size();tick();}).bounds(x,45,w,20).build());
        select=addRenderableWidget(Button.builder(Component.literal("Select this persona"),b->{pending=true;tick();service.selectPersona(values.get(index).id()).whenComplete((v,e)->minecraft.execute(()->{if(minecraft.screen!=this)return;pending=false;status=e==null?"Persona selected":"Selection unavailable";tick();}));}).bounds(x,75,w,20).build());
        view=addRenderableWidget(Button.builder(Component.literal("View this persona's memories"),b->open()).bounds(x,105,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose()).bounds(x,height-24,w,20).build());
        tick();
        service.personas().thenCombine(service.activePersona(),(a,b)->new Object[]{a,b}).whenComplete((v,e)->minecraft.execute(()->{if(minecraft.screen!=this)return;pending=false;
            if(e!=null)status="Persona storage unavailable";else{values=(List<Persona>)v[0];index=0;for(int i=0;i<values.size();i++)if(values.get(i).id().equals(v[1]))index=i;status=values.isEmpty()?"Create a character and Use as persona first":"Unassigned and other-persona messages are excluded";}tick();}));
    }
    private void open(){var persona=values.get(index);pending=true;tick();importer.sources().whenComplete((sources,e)->minecraft.execute(()->{if(minecraft.screen!=this)return;pending=false;if(e!=null){status="Memories unavailable";tick();}else minecraft.setScreen(new TranscriptScreen(this,importer,sources,service,persona.id()));}));}
    @Override public void tick(){if(choice==null)return;choice.active=select.active=view.active=!pending&&!values.isEmpty();if(!values.isEmpty())choice.setMessage(Component.literal(values.get(index).label()+" #"+values.get(index).id().substring(0,8)));}
    @Override public void render(GuiGraphics g,int x,int y,float delta){super.render(g,x,y,delta);g.drawCenteredString(font,title,width/2,15,0xFFE5E7EB);g.drawString(font,font.plainSubstrByWidth(status,width-32),16,140,0xFF93C5FD);}
    @Override public Component getNarrationMessage(){return Component.literal(title.getString()+". "+status);}
    @Override public void onClose(){minecraft.setScreen(parent);}@Override public boolean isPauseScreen(){return false;}
}
