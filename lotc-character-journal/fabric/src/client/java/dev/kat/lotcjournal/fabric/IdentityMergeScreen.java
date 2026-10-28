package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.application.IdentityService;
import dev.kat.lotcjournal.domain.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Reversible decisions with a durable-state-derived, bounded shared-name review queue. */
final class IdentityMergeScreen extends Screen {
    private record Loaded(IdentityReviewData data,IdentityReviewBatch queue) {}
    private record Decision(String id,String first,String second,boolean separate) {}
    private final Screen parent;
    private final IdentityService service;
    private List<CharacterIdentity> values=List.of();
    private List<IdentitySeparation> separations=List.of();
    private List<Decision> decisions=List.of();
    private IdentityReviewBatch queue=new IdentityReviewBatch(List.of(),false);
    private Map<String,String> parents=Map.of();
    private int from,to,decision,queueIndex;
    private long request;
    private Button fromButton,toButton,confirm,deny,undo,history,next;
    private boolean pending=true,loadedOnce,manual=true;
    private String status="Loading decisions";
    IdentityMergeScreen(Screen parent,IdentityService service) {
        super(Component.literal("Review identity links"));this.parent=parent;this.service=service;
    }
    @Override protected void init() {
        int w=Math.min(500,width-32),x=(width-w)/2;
        fromButton=addRenderableWidget(Button.builder(Component.literal("From"),b->{from=(from+1)%values.size();manual=true;tick();}).bounds(x,35,w,20).build());
        toButton=addRenderableWidget(Button.builder(Component.literal("To"),b->{to=(to+1)%values.size();manual=true;tick();}).bounds(x,60,w,20).build());
        confirm=addRenderableWidget(Button.builder(Component.literal("Confirm these are the same identity"),b->link()).bounds(x,85,w,20).build());
        deny=addRenderableWidget(Button.builder(Component.literal("Deny link / keep separate"),b->deny()).bounds(x,110,w,20).build());
        history=addRenderableWidget(Button.builder(Component.literal("Previous decisions"),b->{decision=(decision+1)%decisions.size();tick();}).bounds(x,140,w/2-3,20).build());
        next=addRenderableWidget(Button.builder(Component.literal("Next queued pair"),b->{queueIndex=(queueIndex+1)%queue.pairs().size();selectQueued();tick();}).bounds(x+w/2+3,140,w/2-3,20).build());
        undo=addRenderableWidget(Button.builder(Component.literal("Undo selected link"),b->undo()).bounds(x,165,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose()).bounds(x,height-24,w,20).build());
        if(!pending||request==0)load(null,null,false);else tick();
    }
    private void selectQueued() {
        var pair=queue.pairs().get(queueIndex);from=indexOf(pair.firstIdentity(),0);to=indexOf(pair.secondIdentity(),values.size()>1?1:0);manual=false;
    }
    private void load(String preferredDecision,String message,boolean advance) {
        long token=++request;
        String selectedFrom=values.isEmpty()?null:values.get(from).id(),selectedTo=values.isEmpty()?null:values.get(to).id();
        String selectedDecision=preferredDecision!=null?preferredDecision:decisions.isEmpty()?null:decisions.get(decision).id();
        pending=true;tick();
        service.reviewData().thenCompose(data->service.reviewQueue(data).thenApply(batch->new Loaded(data,batch)))
            .whenComplete((result,error)->minecraft.execute(()->{
                if(token!=request||minecraft.screen!=this)return;pending=false;
                if(error!=null)status="Queue unavailable; chosen pair kept. Reopen review to retry";
                else {
                    var data=result.data();values=data.identities();queue=result.queue();separations=data.separations().stream().filter(IdentitySeparation::active).toList();
                    var roots=new HashMap<String,String>();var history=new ArrayList<Decision>();
                    for(var link:data.merges())if(link.active()){roots.put(link.fromIdentity(),link.toIdentity());history.add(new Decision(link.id(),link.fromIdentity(),link.toIdentity(),false));}
                    for(var separate:separations)history.add(new Decision(separate.id(),separate.firstIdentity(),separate.secondIdentity(),true));
                    parents=Map.copyOf(roots);decisions=List.copyOf(history);
                    from=indexOf(selectedFrom,0);to=indexOf(selectedTo,values.size()>1?1:0);decision=0;
                    if(selectedDecision!=null)for(int i=0;i<decisions.size();i++)if(decisions.get(i).id().equals(selectedDecision)){decision=i;break;}
                    if((advance||!loadedOnce)&&!queue.pairs().isEmpty()){queueIndex=0;selectQueued();}
                    else {
                        int selected=-1;for(int i=0;i<queue.pairs().size();i++){var pair=queue.pairs().get(i);String a=values.isEmpty()?null:values.get(from).id(),b=values.isEmpty()?null:values.get(to).id();if((pair.firstIdentity().equals(a)&&pair.secondIdentity().equals(b))||(pair.firstIdentity().equals(b)&&pair.secondIdentity().equals(a))){selected=i;break;}}
                        manual=selected<0;if(selected>=0)queueIndex=selected;
                    }
                    loadedOnce=true;
                    status=message==null?(queue.pairs().isEmpty()?"No queued name matches. Choose a pair manually":"Review shared-name/alias candidates; identities remain uncertain"):message+(queue.pairs().isEmpty()?". Name-match queue complete":". Next queued pair ready");
                }
                tick();
            }));
    }
    private int indexOf(String id,int fallback){for(int i=0;i<values.size();i++)if(values.get(i).id().equals(id))return i;return fallback;}
    private String label(int index){var value=values.get(index);return value.name()+" #"+value.id().substring(0,8);}
    private String name(String id){return values.stream().filter(value->value.id().equals(id)).map(CharacterIdentity::name).findFirst().orElse("#"+id.substring(0,8));}
    private String root(String id){var seen=new HashSet<String>();String cursor=id;while(parents.containsKey(cursor)&&seen.add(cursor))cursor=parents.get(cursor);return cursor;}
    private boolean blocked(){if(values.isEmpty())return false;String a=root(values.get(from).id()),b=root(values.get(to).id());return separations.stream().anyMatch(value->{String x=root(value.firstIdentity()),y=root(value.secondIdentity());return (a.equals(x)&&b.equals(y))||(a.equals(y)&&b.equals(x));});}
    private void link(){
        if(!confirm.active)return;pending=true;tick();long token=++request;
        service.mergeIdentity(values.get(from).id(),values.get(to).id()).whenComplete((id,error)->minecraft.execute(()->{
            if(token!=request||minecraft.screen!=this)return;
            if(error!=null){pending=false;status="Link not saved. Pair kept; reopen review to refresh";tick();}
            else load(id,"Identity link saved; persona knowledge unchanged",true);
        }));
    }
    private void deny(){
        if(!deny.active)return;pending=true;tick();long token=++request;
        service.denyIdentityLink(values.get(from).id(),values.get(to).id()).whenComplete((id,error)->minecraft.execute(()->{
            if(token!=request||minecraft.screen!=this)return;
            if(error!=null){pending=false;status="Denial not saved. Pair kept; undo conflicting link or retry";tick();}
            else load(id,"Denial saved; identities stay separate",true);
        }));
    }
    private void undo(){
        if(!undo.active)return;var selected=decisions.get(decision);pending=true;tick();long token=++request;
        var action=selected.separate()?service.undoSeparation(selected.id()):service.undoMerge(selected.id());
        action.whenComplete((ignored,error)->minecraft.execute(()->{
            if(token!=request||minecraft.screen!=this)return;
            if(error!=null){pending=false;status="Decision could not be reversed; reopen review to retry";tick();}
            else load(null,selected.separate()?"Denial cleared. No link was created automatically":"Identity link undone; original records retained",false);
        }));
    }
    private String queueStatus(){return pending?"Loading review queue":(manual?"Manual pair":"Queued name match")+" | "+queue.pairs().size()+(queue.hasMore()?"+":"")+" pending";}
    private String queueEvidence(){return !manual&&queueIndex<queue.pairs().size()?"Shared saved name/alias: "+queue.matchingNames().getOrDefault(queue.pairs().get(queueIndex),"Review the observed names")+". This requires your decision.":"Manual choice; no match is inferred from this selection.";}
    @Override public void tick(){
        if(confirm==null)return;fromButton.active=toButton.active=!pending&&!values.isEmpty();
        boolean valid=values.size()>1&&from!=to,together=valid&&root(values.get(from).id()).equals(root(values.get(to).id())),denied=valid&&blocked();
        confirm.active=!pending&&valid&&!together&&!denied&&root(values.get(from).id()).equals(values.get(from).id());deny.active=!pending&&valid&&!together&&!denied;
        confirm.setTooltip(Tooltip.create(Component.literal((denied?"Saved denial must be explicitly cleared before linking. ":"Confirm an identity link; persona memories unchanged. ")+queueEvidence())));
        deny.setTooltip(Tooltip.create(Component.literal((together?"Undo the existing link first. ":denied?"Already kept separate. ":"Persist a reversible keep-separate decision. ")+queueEvidence())));
        history.active=undo.active=!pending&&!decisions.isEmpty();next.active=!pending&&!queue.pairs().isEmpty();
        next.setTooltip(Tooltip.create(Component.literal("Browse pending shared-name/alias pairs without deciding. Confirm/Deny automatically advances after saving. No unrelated pair is proposed.")));
        if(!values.isEmpty()){fromButton.setMessage(Component.literal("Link from: "+label(from)));toButton.setMessage(Component.literal("Link to: "+label(to)));}
        fromButton.setTooltip(values.isEmpty()?null:Tooltip.create(Component.literal(values.get(from).id()+"\n"+queueEvidence())));toButton.setTooltip(values.isEmpty()?null:Tooltip.create(Component.literal(values.get(to).id()+"\n"+queueEvidence())));
        if(decisions.isEmpty()){history.setMessage(Component.literal("No active decisions"));history.setTooltip(null);}
        else {var selected=decisions.get(decision);String text=(selected.separate()?"Keep separate: ":"Linked: ")+name(selected.first())+" / "+name(selected.second());history.setMessage(Component.literal(font.plainSubstrByWidth(text,history.getWidth()-12)));history.setTooltip(Tooltip.create(Component.literal(text+"\nDecision "+(decision+1)+" / "+decisions.size()+" #"+selected.id().substring(0,8))));}
        undo.setMessage(Component.literal(!decisions.isEmpty()&&decisions.get(decision).separate()?"Clear selected denial":"Undo selected link"));
    }
    @Override public void render(GuiGraphics graphics,int x,int y,float delta){super.render(graphics,x,y,delta);graphics.drawCenteredString(font,title,width/2,8,0xFFE5E7EB);graphics.drawString(font,font.plainSubstrByWidth(queueStatus(),width-32),16,23,0xFF93C5FD);graphics.drawString(font,font.plainSubstrByWidth(status,width-32),16,193,0xFF93C5FD);}
    @Override public Component getNarrationMessage(){return Component.literal(title.getString()+". "+queueStatus()+". "+queueEvidence()+". "+status+(decisions.isEmpty()?"":". Selected decision "+decisions.get(decision).id()+". "+name(decisions.get(decision).first())+" / "+name(decisions.get(decision).second()))+(blocked()?". Selected identities are kept separate":""));}
    @Override public void onClose(){request++;minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
