package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.application.IdentityService;
import dev.kat.lotcjournal.domain.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit reversible positive and negative identity decisions; never combines memories. */
final class IdentityMergeScreen extends Screen {
    private record Choices(List<CharacterIdentity> identities,List<IdentityMerge> links,List<IdentitySeparation> separations) {}
    private record Decision(String id,String first,String second,boolean separate) {}
    private final Screen parent;
    private final IdentityService service;
    private List<CharacterIdentity> values=List.of();
    private List<IdentitySeparation> separations=List.of();
    private List<Decision> decisions=List.of();
    private Map<String,String> parents=Map.of();
    private int from,to,decision;
    private long request;
    private Button fromButton,toButton,confirm,deny,undo,history;
    private boolean pending=true;
    private String status="Loading decisions";
    IdentityMergeScreen(Screen parent,IdentityService service) {
        super(Component.literal("Review identity links"));this.parent=parent;this.service=service;
    }
    @Override protected void init() {
        int w=Math.min(500,width-32),x=(width-w)/2;
        fromButton=addRenderableWidget(Button.builder(Component.literal("From"),b->{from=(from+1)%values.size();tick();}).bounds(x,35,w,20).build());
        toButton=addRenderableWidget(Button.builder(Component.literal("To"),b->{to=(to+1)%values.size();tick();}).bounds(x,60,w,20).build());
        confirm=addRenderableWidget(Button.builder(Component.literal("Confirm these are the same identity"),b->link()).bounds(x,85,w,20).build());
        deny=addRenderableWidget(Button.builder(Component.literal("Deny link / keep separate"),b->deny()).bounds(x,110,w,20).build());
        history=addRenderableWidget(Button.builder(Component.literal("Previous decisions"),b->{decision=(decision+1)%decisions.size();tick();}).bounds(x,140,w,20).build());
        undo=addRenderableWidget(Button.builder(Component.literal("Undo selected link"),b->undo()).bounds(x,165,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose()).bounds(x,height-24,w,20).build());
        load(null,null);
    }
    private void load(String preferredDecision,String message) {
        long token=++request;
        String selectedFrom=values.isEmpty()?null:values.get(from).id(),selectedTo=values.isEmpty()?null:values.get(to).id();
        String selectedDecision=preferredDecision!=null?preferredDecision:decisions.isEmpty()?null:decisions.get(decision).id();
        pending=true;tick();
        service.identities("").thenCombine(service.merges(),(a,b)->new Choices(a,b,List.of()))
            .thenCombine(service.separations(),(a,b)->new Choices(a.identities(),a.links(),b))
            .whenComplete((choices,error)->minecraft.execute(()->{
                if(token!=request||minecraft.screen!=this)return;
                pending=false;
                if(error!=null)status="Decisions unavailable; reopen review to retry";
                else {
                    values=choices.identities();separations=choices.separations().stream().filter(IdentitySeparation::active).toList();
                    var roots=new HashMap<String,String>();var history=new ArrayList<Decision>();
                    for(var link:choices.links()) if(link.active()) {
                        roots.put(link.fromIdentity(),link.toIdentity());
                        history.add(new Decision(link.id(),link.fromIdentity(),link.toIdentity(),false));
                    }
                    for(var separate:separations) history.add(new Decision(separate.id(),separate.firstIdentity(),separate.secondIdentity(),true));
                    parents=Map.copyOf(roots);decisions=List.copyOf(history);
                    from=indexOf(selectedFrom,0);to=indexOf(selectedTo,values.size()>1?1:0);decision=0;
                    if(selectedDecision!=null)for(int i=0;i<decisions.size();i++)if(decisions.get(i).id().equals(selectedDecision)){decision=i;break;}
                    status=message==null?"Choose two characters to link or keep separate":message;
                }
                tick();
            }));
    }
    private int indexOf(String id,int fallback) {for(int i=0;i<values.size();i++)if(values.get(i).id().equals(id))return i;return fallback;}
    private String label(int index) {var value=values.get(index);return value.name()+" #"+value.id().substring(0,8);}
    private String name(String id) {return values.stream().filter(value->value.id().equals(id)).map(CharacterIdentity::name).findFirst().orElse("#"+id.substring(0,8));}
    private String root(String id) {
        var seen=new HashSet<String>();String cursor=id;
        while(parents.containsKey(cursor)&&seen.add(cursor))cursor=parents.get(cursor);
        return cursor;
    }
    private boolean blocked() {
        if(values.isEmpty())return false;
        String a=root(values.get(from).id()),b=root(values.get(to).id());
        return separations.stream().anyMatch(value->{String x=root(value.firstIdentity()),y=root(value.secondIdentity());return (a.equals(x)&&b.equals(y))||(a.equals(y)&&b.equals(x));});
    }
    private void link() {
        if(!confirm.active)return;pending=true;tick();
        service.mergeIdentity(values.get(from).id(),values.get(to).id()).whenComplete((id,error)->minecraft.execute(()->{
            if(minecraft.screen!=this)return;
            if(error!=null){pending=false;status="Link not saved; reopen review or undo an existing decision first";tick();}
            else load(id,"Link saved; shared related history combines; persona memories unchanged");
        }));
    }
    private void deny() {
        if(!deny.active)return;pending=true;tick();
        service.denyIdentityLink(values.get(from).id(),values.get(to).id()).whenComplete((id,error)->minecraft.execute(()->{
            if(minecraft.screen!=this)return;
            if(error!=null){pending=false;status="Denial not saved; undo any existing link first, then retry";tick();}
            else load(id,"Denial saved. These identities stay separate until you explicitly clear it");
        }));
    }
    private void undo() {
        if(!undo.active)return;var selected=decisions.get(decision);pending=true;tick();
        var action=selected.separate()?service.undoSeparation(selected.id()):service.undoMerge(selected.id());
        action.whenComplete((ignored,error)->minecraft.execute(()->{
            if(minecraft.screen!=this)return;
            if(error!=null){pending=false;status="Decision could not be reversed; reopen review to retry";tick();}
            else load(null,selected.separate()?"Denial cleared. No link was created automatically":"Identity link undone; original records retained");
        }));
    }
    @Override public void tick() {
        if(confirm==null)return;
        fromButton.active=toButton.active=!pending&&!values.isEmpty();
        boolean valid=values.size()>1&&from!=to;
        boolean together=valid&&root(values.get(from).id()).equals(root(values.get(to).id()));
        boolean denied=valid&&blocked();
        confirm.active=!pending&&valid&&!together&&!denied&&root(values.get(from).id()).equals(values.get(from).id());
        deny.active=!pending&&valid&&!together&&!denied;
        deny.setTooltip(Tooltip.create(Component.literal(together?"Undo the existing identity link before keeping these identities separate":denied?"Already kept separate. Clear the selected denial to permit linking":"Remember that these identities are different; block direct and indirect linking")));
        confirm.setTooltip(Tooltip.create(Component.literal(denied?"Blocked by a saved denial. Explicitly clear that denial first":"Confirm a shared related-history group. Original records remain distinct; persona knowledge is unchanged")));
        history.active=undo.active=!pending&&!decisions.isEmpty();
        if(!values.isEmpty()){fromButton.setMessage(Component.literal("Link from: "+label(from)));toButton.setMessage(Component.literal("Link to: "+label(to)));}
        if(decisions.isEmpty()){history.setMessage(Component.literal("No active links or denials"));history.setTooltip(null);}
        else {
            var selected=decisions.get(decision);
            String label=(selected.separate()?"Keep separate: ":"Linked: ")+name(selected.first())+" / "+name(selected.second());
            history.setMessage(Component.literal(font.plainSubstrByWidth(label,history.getWidth()-12)));
            history.setTooltip(Tooltip.create(Component.literal(label+"\nDecision "+(decision+1)+" / "+decisions.size()+" #"+selected.id().substring(0,8))));
        }
        undo.setMessage(Component.literal(!decisions.isEmpty()&&decisions.get(decision).separate()?"Clear selected denial":"Undo selected link"));
    }
    @Override public void render(GuiGraphics graphics,int x,int y,float delta) {
        super.render(graphics,x,y,delta);graphics.drawCenteredString(font,title,width/2,12,0xFFE5E7EB);
        graphics.drawString(font,font.plainSubstrByWidth(status,width-32),16,193,0xFF93C5FD);
    }
    @Override public Component getNarrationMessage() {return Component.literal(title.getString()+". "+status+(blocked()?". Selected identities are kept separate":""));}
    @Override public void onClose(){request++;minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
