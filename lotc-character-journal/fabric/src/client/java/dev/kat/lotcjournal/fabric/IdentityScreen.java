package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.application.IdentityService;
import dev.kat.lotcjournal.domain.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Archive editing is separate from a persona's restricted memory transcript. */
final class IdentityScreen extends Screen {
    private final Screen parent;
    private final IdentityService service;
    private List<CharacterIdentity> identities=List.of();
    private int index=-1;
    private EditBox search,name,aliases,account,notes;
    private Button selector,save,persona,merge;
    private String status="Loading identities",query="";
    private long request;
    private boolean pending;
    IdentityScreen(Screen parent,IdentityService service,String query) {
        super(Component.literal("Characters — shared archive"));this.parent=parent;this.service=service;this.query=query;
    }
    @Override protected void init() {
        int panel=Math.min(520,width-32),left=(width-panel)/2,half=panel/2-4;
        search=field(left,24,half,"Search names / aliases",256,query);
        search.setResponder(value->{query=value;load();});
        selector=addRenderableWidget(Button.builder(Component.literal("New character"),b->{index=index+1>=identities.size()?-1:index+1;populate();}).bounds(left+panel/2+4,24,half,20).build());
        name=field(left,49,panel,"Character name",96,"");
        aliases=field(left,74,panel,"Aliases (separate with ;)",4096,"");
        account=field(left,99,panel,"Account observation — unverified",128,"");
        notes=field(left,124,panel,"Manual archive notes",16384,"");
        addRenderableWidget(Button.builder(Component.literal("New character"),b->{index=-1;populate();}).bounds(left,149,half,20).build());
        save=addRenderableWidget(Button.builder(Component.literal("Save character"),b->save()).bounds(left+panel/2+4,149,half,20).build());
        persona=addRenderableWidget(Button.builder(Component.literal("Use as persona"),b->makePersona()).bounds(left,174,half,20).build());
        merge=addRenderableWidget(Button.builder(Component.literal("Review identity links"),b->minecraft.setScreen(new IdentityMergeScreen(this,service))).bounds(left+panel/2+4,174,half,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose()).bounds(left,height-24,panel,20).build());
        load();
    }
    private EditBox field(int x,int y,int width,String hint,int max,String value) {
        var f=new EditBox(font,x,y,width,20,Component.literal(hint));f.setHint(Component.literal(hint));f.setMaxLength(max);f.setValue(value);return addRenderableWidget(f);
    }
    private void populate() {
        var value=index<0?null:identities.get(index);
        name.setValue(value==null?"":value.name());aliases.setValue(value==null?"":String.join("; ",value.aliases()));
        account.setValue(value==null?"":value.accountObservation());notes.setValue(value==null?"":value.notes());
        selector.setMessage(Component.literal(value==null?"New character":font.plainSubstrByWidth(value.name()+" #"+value.id().substring(0,6),selector.getWidth()-12)));
        status=value==null?"Create a character; aliases never merge automatically":"Archive notes are shared; memories require explicit persona assignment";
        tick();
    }
    private void load() {
        long token=++request;pending=true;tick();
        service.identities(query).whenComplete((values,error)->minecraft.execute(()->{
            if(token!=request || minecraft.screen!=this)return;pending=false;
            if(error!=null)status="Identity storage unavailable; retry";
            else {identities=values;index=-1;populate();}tick();
        }));
    }
    private void save() {
        final CharacterIdentity value;
        try {value=new CharacterIdentity(index<0?UUID.randomUUID().toString():identities.get(index).id(),name.getValue().strip(),notes.getValue(),
            Arrays.stream(aliases.getValue().split(";")).map(String::strip).filter(a->!a.isEmpty()).collect(java.util.stream.Collectors.toSet()),account.getValue().strip());}
        catch(IllegalArgumentException e){status="Enter a name and valid bounded fields";return;}
        pending=true;tick();
        service.saveIdentity(value).whenComplete((v,error)->minecraft.execute(()->{
            if(minecraft.screen!=this)return;pending=false;
            if(error!=null){status="Character could not be saved";tick();return;}
            query="";search.setValue("");
            service.identities("").whenComplete((values,e)->minecraft.execute(()->{
                if(minecraft.screen!=this)return;
                if(e!=null){status="Saved; reopen to refresh";return;}
                identities=values;index=0;while(index<identities.size()&&!identities.get(index).id().equals(value.id()))index++;
                if(index>=identities.size())index=-1;populate();status="Character saved";
            }));
        }));
    }
    private void makePersona() {
        if(index<0)return;var identity=identities.get(index);pending=true;tick();
        service.personas().thenCompose(values->{
            var existing=values.stream().filter(p->p.identityId().equals(identity.id())).findFirst();
            if(existing.isPresent())return service.selectPersona(existing.get().id());
            var value=new Persona(UUID.randomUUID().toString(),identity.id(),identity.name());
            return service.createPersona(value).thenCompose(v->service.selectPersona(value.id()));
        }).whenComplete((v,error)->minecraft.execute(()->{if(minecraft.screen!=this)return;pending=false;status=error==null?"Persona selected; assign messages explicitly to build its memories":"Persona could not be selected";tick();}));
    }
    @Override public void tick(){if(save==null)return;save.active=!pending;selector.active=!pending;persona.active=!pending&&index>=0;merge.active=!pending;}
    @Override public void render(GuiGraphics g,int x,int y,float delta){super.render(g,x,y,delta);g.drawCenteredString(font,title,width/2,8,0xFFE5E7EB);g.drawString(font,font.plainSubstrByWidth(status,Math.min(520,width-32)),(width-Math.min(520,width-32))/2,199,0xFF93C5FD);}
    @Override public Component getNarrationMessage(){return Component.literal(title.getString()+". "+status);}
    @Override public void onClose(){request++;minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
