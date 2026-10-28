package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.application.SceneService;
import dev.kat.lotcjournal.application.IdentityService;
import dev.kat.lotcjournal.domain.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Bounded scene browser. All queries and regrouping run on the scene worker. */
final class SceneScreen extends Screen {
    private final Screen parent;
    private final SceneService service;
    private final IdentityService identities;
    private List<Persona> personas=List.of();
    private List<SceneSummary> entries=List.of();
    private final List<Button> rows=new ArrayList<>();
    private String personaId,query="",status="Loading scenes";
    private long request, total;
    private int offset,delay;
    private SceneSettings settings=SceneSettings.DEFAULT;
    private boolean pending,includeSkipped,suggestedOnly,initialRefresh;
    private EditBox search;
    private Button scope,skipped,suggested,gap,refresh,previous,next;
    SceneScreen(Screen parent,SceneService service,IdentityService identities) {
        super(Component.literal("Scenes — review suggested dialogue groups"));this.parent=parent;this.service=service;this.identities=identities;
    }
    @Override protected void init() {
        int w=Math.min(580,width-32),x=(width-w)/2;
        search=addRenderableWidget(new EditBox(font,x,24,w,20,Component.literal("Search scenes and dialogue")));
        search.setMaxLength(200);search.setValue(query);search.setHint(Component.literal("Search scenes / dialogue"));
        search.setResponder(value->{query=value;delay=3;offset=0;status="Searching scenes";update();});
        int third=w/3;
        scope=addRenderableWidget(Button.builder(Component.empty(),b->cycleScope()).bounds(x,49,third-3,20).build());
        skipped=addRenderableWidget(Button.builder(Component.empty(),b->{includeSkipped=!includeSkipped;offset=0;load();}).bounds(x+third+3,49,third-6,20).build());
        suggested=addRenderableWidget(Button.builder(Component.empty(),b->{suggestedOnly=!suggestedOnly;offset=0;load();}).bounds(x+2*third+3,49,w-2*third-3,20).build());
        gap=addRenderableWidget(Button.builder(Component.empty(),b->cycleGap()).bounds(x,74,w/2-3,20).build());
        gap.setTooltip(Tooltip.create(Component.literal("Default 15 minutes. Regroup automatic windows, including automatically confirmed ones. Explicitly reviewed/skipped scenes stay fixed.")));
        refresh=addRenderableWidget(Button.builder(Component.literal("Suggest / refresh"),b->regroup()).bounds(x+w/2+3,74,w/2-3,20).build());
        rows.clear();int count=Math.max(1,Math.min(12,(height-155)/24));
        for(int i=0;i<count;i++) {int index=i;rows.add(addRenderableWidget(Button.builder(Component.empty(),b->open(index)).bounds(x,103+i*24,w,20).build()));}
        previous=addRenderableWidget(Button.builder(Component.literal("Previous"),b->{offset=Math.max(0,offset-rows.size());load();}).bounds(x,height-48,w/2-3,20).build());
        next=addRenderableWidget(Button.builder(Component.literal("Next"),b->{offset+=rows.size();load();}).bounds(x+w/2+3,height-48,w/2-3,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose()).bounds(x,height-24,w,20).build());
        if(personas.isEmpty())identities.personas().whenComplete((value,error)->minecraft.execute(()->{if(minecraft.screen!=this)return;if(error==null)personas=value;update();}));
        service.settings().whenComplete((value,error)->minecraft.execute(()->{if(minecraft.screen!=this)return;if(error==null)settings=value;update();}));
        update();if(pending&&request>0)return;if(!initialRefresh&&personaId==null){initialRefresh=true;regroup();}else load();
    }
    private void cycleScope() {
        int index=-1;for(int i=0;i<personas.size();i++)if(personas.get(i).id().equals(personaId))index=i;
        personaId=index+1<personas.size()?personas.get(index+1).id():null;offset=0;load();
    }
    private void cycleGap() {
        int[] minutes={1,5,10,15,30,60,120};int next=minutes[0];
        for(int minute:minutes)if(minute>settings.inactivitySeconds()/60){next=minute;break;}
        var changed=new SceneSettings(next*60);pending=true;status="Saving gap and reassessing automatic scenes";long token=++request;update();
        service.saveSettings(changed).thenCompose(v->service.refreshSuggestions()).whenComplete((value,error)->minecraft.execute(()->{
            if(token!=request||minecraft.screen!=this)return;pending=false;
            if(error!=null){status="Gap/refresh failed; reload to inspect saved state";update();}else {settings=changed;offset=0;load();}
        }));
    }
    private void regroup() {
        pending=true;status="Assessing 10-message/two-participant scenes; reviewed decisions stay";long token=++request;update();
        service.refreshSuggestions().whenComplete((value,error)->minecraft.execute(()->{
            if(token!=request||minecraft.screen!=this)return;pending=false;
            if(error!=null){status="Suggestions unavailable; retry when storage is ready";update();}else {offset=0;load();}
        }));
    }
    private void load() {
        delay=0;long token=++request;pending=true;status="Loading scenes";update();
        service.scenes(personaId,includeSkipped,personaId==null&&suggestedOnly,query,offset,rows.size()).whenComplete((page,error)->minecraft.execute(()->{
            if(token!=request||minecraft.screen!=this)return;pending=false;
            if(error!=null){entries=List.of();total=0;status="Scenes unavailable; refresh to retry";}
            else {entries=page.entries();total=page.total();if(entries.isEmpty()&&offset>0){offset=0;load();return;}
                status=entries.isEmpty()?"No scenes match: automatic scenes need 10 RP messages and 2 actors":"Suggested scenes need review; automatic confirmations describe grouping";}
            update();
        }));
    }
    private void open(int index) {if(index<entries.size())minecraft.setScreen(new SceneDetailScreen(this,service,identities,entries.get(index).id(),personaId));}
    private void update() {
        if(scope==null)return;
        boolean busy=pending||delay>0;
        search.active=!pending;scope.active=!busy;skipped.active=suggested.active=!busy&&personaId==null;gap.active=refresh.active=!busy&&personaId==null;
        String name=personas.stream().filter(p->p.id().equals(personaId)).map(Persona::label).findFirst().orElse("Shared archive");
        scope.setMessage(Component.literal(personaId==null?name:font.plainSubstrByWidth("Persona: "+name+" #"+personaId.substring(0,8),scope.getWidth()-12)));
        scope.setTooltip(Tooltip.create(Component.literal(personaId==null?"Shared archive administration. Click to inspect explicitly assigned persona evidence only.":"Assigned messages only. Scene titles, locations, participants and statistics are projected from this persona's evidence.")));
        suggested.setMessage(Component.literal(personaId!=null?"Assigned only":suggestedOnly?"Suggested: Only":"Suggested: All"));
        suggested.setTooltip(Tooltip.create(Component.literal("Show only low-confidence suggested scenes. Suggested scenes are listed before confirmed scenes.")));
        skipped.setMessage(Component.literal(personaId!=null?"Assigned evidence":includeSkipped?"Skipped: Show":"Skipped: Hide"));gap.setMessage(Component.literal("Gap: "+settings.inactivitySeconds()/60+" minutes"));
        previous.active=!busy&&offset>0;next.active=!busy&&offset+entries.size()<total;
        for(int i=0;i<rows.size();i++) {var button=rows.get(i);button.active=!busy&&i<entries.size();button.visible=!busy&&i<entries.size();
            if(i<entries.size()){var value=entries.get(i);button.setMessage(Component.literal(font.plainSubstrByWidth((personaId!=null?"Assigned":value.state()+(value.origin()==SceneOrigin.AUTO&&value.state()==SceneState.CONFIRMED?" (auto)":""))+" | "+value.messageCount()+" msgs | "+value.title(),button.getWidth()-12)));button.setTooltip(Tooltip.create(Component.literal(value.title()+"\nGrouping: "+(personaId!=null?"Assigned evidence only":value.origin()==SceneOrigin.AUTO?"Automatic confidence; no identities confirmed":"User reviewed")+"\nScene: "+value.id()+"\nLocation: "+(value.location().isBlank()?"Unknown":value.location()))));}}
    }
    @Override public void tick(){if(!pending&&delay>0&&--delay==0)load();}
    @Override public void render(GuiGraphics graphics,int x,int y,float delta) {
        super.render(graphics,x,y,delta);graphics.drawCenteredString(font,title,width/2,8,0xFFE5E7EB);
        int w=Math.min(580,width-32);graphics.drawString(font,font.plainSubstrByWidth(status,w),(width-w)/2,height-61,0xFF93C5FD);
    }
    @Override public Component getNarrationMessage(){return Component.literal(title.getString()+". "+status+". "+(pending||delay>0?"Loading scenes":total+" scenes.")+". "+(personaId==null?"Shared archive":"Assigned persona only")+". "+(pending||delay>0?"":entries.stream().map(v->v.title()+" "+v.state()+" "+v.messageCount()+" messages").collect(java.util.stream.Collectors.joining(". "))));}
    @Override public void onClose(){request++;minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
