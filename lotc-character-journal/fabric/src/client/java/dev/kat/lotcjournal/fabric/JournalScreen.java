package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.application.JournalService;
import dev.kat.lotcjournal.application.IdentityService;
import dev.kat.lotcjournal.ingestion.LiveCaptureService;
import dev.kat.lotcjournal.application.JournalSnapshot;
import dev.kat.lotcjournal.ingestion.LogImportService;
import dev.kat.lotcjournal.ingestion.BatchImportService;
import dev.kat.lotcjournal.ingestion.FolderWatchService;
import java.nio.file.Path;
import java.time.Instant;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import net.fabricmc.loader.api.FabricLoader;

/** Phase 1 shell. Reads snapshots only; SQL and backup work run in JournalService. */
public final class JournalScreen extends Screen {
    private final JournalService service;
    private final Path directory;
    private final LogImportService importer;
    private final BatchImportService batches;
    private final FolderWatchService watcher;
    private final IdentityService identities;
    private final dev.kat.lotcjournal.application.SceneService scenes;
    private final LiveCaptureService live;
    private final LiveChatAdapter liveAdapter;
    private EditBox search;
    private String searchQuery="",searchFeedback="";
    private int searchDelay;
    private long searchRequest;
    private Button layout;
    private Button backup;
    private Button importLogs, transcripts, characters, memories, capture, scenesButton;
    private boolean loadingTranscripts;
    private String operation = "";
    private boolean savingLayout;
    private boolean creatingBackup;
    private final String version = FabricLoader.getInstance().getModContainer("lotc_journal")
        .orElseThrow().getMetadata().getVersion().getFriendlyString();

    public JournalScreen(JournalService service, LogImportService importer, BatchImportService batches, FolderWatchService watcher, Path directory, IdentityService identities, LiveCaptureService live, LiveChatAdapter liveAdapter) {
        this(service,importer,batches,watcher,directory,identities,live,liveAdapter,null);
    }
    public JournalScreen(JournalService service, LogImportService importer, BatchImportService batches, FolderWatchService watcher, Path directory, IdentityService identities, LiveCaptureService live, LiveChatAdapter liveAdapter, dev.kat.lotcjournal.application.SceneService scenes) {
        super(Component.literal("Character Journal"));this.scenes=scenes;
        this.service = service;
        this.directory = directory;
        this.importer = importer;
        this.batches = batches; this.watcher = watcher;
        this.identities = identities; this.live = live; this.liveAdapter = liveAdapter;
    }

    @Override protected void init() {
        int panel = Math.min(340, width - 32);
        int left = (width - panel) / 2;
        search = new EditBox(font, left, 55, panel, 20, Component.literal("Search characters"));
        search.setHint(Component.literal("Search characters — press Enter"));
        search.setMaxLength(120);
        search.setValue(searchQuery);
        search.setResponder(value->{searchQuery=value;if(!savingLayout&&!creatingBackup&&!loadingTranscripts)operation="";searchRequest++;searchDelay=3;searchFeedback=value.isBlank()?"":"Searching characters...";});
        addRenderableWidget(search);
        importLogs = addRenderableWidget(Button.builder(Component.literal("Log import"), button ->
            minecraft.setScreen(new LogImportScreen(this, importer, batches, watcher, FabricLoader.getInstance().getGameDir(), live, identities, scenes)))
            .bounds(left, 115, panel / 2 - 4, 20).build());
        transcripts = addRenderableWidget(Button.builder(Component.literal("View transcripts"), button -> {
            loadingTranscripts = true; operation = "Loading transcripts..."; tick();
            importer.sources().whenComplete((sources, error) -> minecraft.execute(() -> {
                loadingTranscripts = false;
                if (minecraft.screen != this) return;
                if (error != null) { operation = "Transcripts unavailable; retry when storage is ready"; tick(); }
                else { operation = ""; minecraft.setScreen(new TranscriptScreen(this, importer, sources, identities, null)); }
            }));
        }).bounds(left + panel / 2 + 4, 115, panel / 2 - 4, 20).build());
        characters = addRenderableWidget(Button.builder(Component.literal("Characters"), button ->
            openCharacters()).bounds(left,140,panel/2-4,20).build());
        memories = addRenderableWidget(Button.builder(Component.literal("Persona memories"), button ->
            minecraft.setScreen(new PersonaScreen(this, identities, importer))).bounds(left+panel/2+4,140,panel/2-4,20).build());
        capture = addRenderableWidget(Button.builder(Component.literal("Pause live capture"), button -> {
            capture.active=false;
            live.pause(!live.snapshot().paused()).whenComplete((v,error)->minecraft.execute(()->{
                if(minecraft.screen!=this)return;operation=error==null?"Live capture preference saved":"Capture preference failed";tick();
            }));
        }).bounds(left,165,panel/2-4,20).build());
        int y = 165;
        layout = addRenderableWidget(Button.builder(Component.literal("Layout"),
            button -> {
                savingLayout = true;
                operation = "Saving layout...";
                tick();
                service.toggleCompactLayout().whenComplete((ignored, error) -> minecraft.execute(() -> {
                    savingLayout = false;
                    operation = error == null ? "Layout saved" : "Layout could not be saved; retry";
                    tick();
                }));
            }).bounds(left + panel / 2 + 4, y, panel / 2 - 4, 20).build());
        backup = addRenderableWidget(Button.builder(Component.literal("Create backup"), button -> {
            operation = "Creating backup...";
            creatingBackup = true;
            tick();
            service.backup(directory.resolve("backups").resolve(
                "journal-" + Instant.now().toEpochMilli() + ".sqlite"))
                .whenComplete((path, error) -> minecraft.execute(() -> {
                    creatingBackup = false;
                    operation = error == null ? "Backup created" : "Backup failed; you can retry";
                    tick();
                }));
        }).bounds(left, 190, scenes==null?panel:panel/2-4, 20).build());
        if(scenes!=null)scenesButton=addRenderableWidget(Button.builder(Component.literal("Scenes"),button->minecraft.setScreen(new SceneScreen(this,scenes,identities))).bounds(left+panel/2+4,190,panel/2-4,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(left, height - 24, panel, 20).build());
        if(!searchQuery.isBlank())searchDelay=1;
        tick();
    }

    private void openCharacters(){minecraft.setScreen(new IdentityScreen(this,identities,searchQuery,importer));}
    private void lookupCharacters(){
        String query=searchQuery;long token=++searchRequest;
        if(query.isBlank()){searchFeedback="";return;}
        identities.identities(query).whenComplete((values,error)->minecraft.execute(()->{
            if(token!=searchRequest||minecraft.screen!=this||!query.equals(searchQuery))return;
            searchFeedback=error!=null?"Search unavailable; Enter opens character search":values.isEmpty()?"No characters found — Enter to browse":(values.size()==1000?"First 1000 matches":values.size()+" character match"+(values.size()==1?"":"es"))+" — Enter to view";
        }));
    }
    @Override public boolean keyPressed(KeyEvent event){
        if((event.key()==GLFW.GLFW_KEY_ENTER||event.key()==GLFW.GLFW_KEY_KP_ENTER)&&search!=null&&search.isFocused()&&characters.active){openCharacters();return true;}
        return super.keyPressed(event);
    }
    @Override public void onClose(){searchRequest++;super.onClose();}

    @Override public void tick() {
        if(searchDelay>0&&--searchDelay==0)lookupCharacters();
        var state = service.snapshot();
        boolean ready = state.status() == JournalSnapshot.Status.READY;
        layout.active = ready && !savingLayout && !creatingBackup;
        backup.active = layout.active;
        importLogs.active = layout.active && !loadingTranscripts;
        transcripts.active = layout.active && !loadingTranscripts;
        characters.active = memories.active = layout.active;
        if(scenesButton!=null)scenesButton.active=layout.active;
        capture.active = ready && live.snapshot().state() != LiveCaptureService.State.CLOSED;
        capture.setMessage(Component.literal(live.snapshot().paused() ? "Resume live capture" : "Pause live capture"));
        layout.setMessage(Component.literal("Layout: " +
            (state.settings().compactLayout() ? "Compact" : "Comfortable")));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, title, width / 2, 20, 0xFFE5E7EB);
        graphics.drawCenteredString(font, "Journal " + version + " | MC 1.21.11", width / 2, 36, 0xFF9CA3AF);
        int left = (width - Math.min(340, width - 32)) / 2;
        graphics.drawString(font, font.plainSubstrByWidth(visibleStatus(), Math.min(340, width-32)), left, 87, 0xFFD1D5DB);
        var state = live.snapshot();
        String captureStatus = "Live: " + state.state() + " | Saved " + state.saved() + " | Pending " + state.pending();
        if (state.errorCode() != null) captureStatus = "Live error: " + state.errorCode();
        if (liveAdapter.overflow() > 0) captureStatus = "Live input overflow: " + liveAdapter.overflow() + " rejected";
        graphics.drawString(font, font.plainSubstrByWidth(captureStatus, Math.min(340, width-32)), left, 100, 0xFF93C5FD);
    }

    private String visibleStatus(){
        if(!operation.isBlank())return operation;
        return searchQuery.isBlank()?service.snapshot().message():searchFeedback;
    }
    @Override public Component getNarrationMessage() {
        var value = live.snapshot();
        return Component.literal(title.getString() + ". Live " + value.state() + "; saved " + value.saved()
            + "; pending " + value.pending() + "; " + visibleStatus());
    }
    @Override public boolean isPauseScreen() { return false; }
}

