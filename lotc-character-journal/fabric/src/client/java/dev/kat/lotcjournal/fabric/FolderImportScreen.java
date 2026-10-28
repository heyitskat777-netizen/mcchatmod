package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.ingestion.BatchImportService;
import dev.kat.lotcjournal.ingestion.FolderWatchService;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Preview confirmation is separate from discovery; all file/SQL work belongs to workers. */
public final class FolderImportScreen extends Screen {
    private final Screen parent;
    private final dev.kat.lotcjournal.application.SceneService scenes;
    private final dev.kat.lotcjournal.application.IdentityService identities;
    private final BatchImportService batches;
    private final FolderWatchService watcher;
    private final Path gameDirectory;
    private String pathValue = "", namesValue = "", notice = "";
    private BatchImportService.FolderPreview preview;
    private boolean pending, confirmWatch;
    private long revision;
    private int previewScroll;
    private Button scan, confirm, cancel, watch, sceneReview;

    public FolderImportScreen(Screen parent, BatchImportService batches, FolderWatchService watcher, Path gameDirectory) {
        this(parent,batches,watcher,gameDirectory,null,null);
    }
    public FolderImportScreen(Screen parent, BatchImportService batches, FolderWatchService watcher, Path gameDirectory, dev.kat.lotcjournal.application.SceneService scenes, dev.kat.lotcjournal.application.IdentityService identities) {
        super(Component.literal("Import all logs"));this.scenes=scenes;this.identities=identities;
        this.parent = parent; this.batches = batches; this.watcher = watcher; this.gameDirectory = gameDirectory;
    }

    @Override protected void init() {
        int panel = Math.min(420, width - 32), left = (width - panel) / 2;
        var path = new EditBox(font, left, 30, panel, 20, Component.literal("Log folder path"));
        path.setMaxLength(1024); path.setHint(Component.literal("Folder path, no nested folders")); path.setValue(pathValue);
        path.setResponder(value -> { pathValue = value; invalidatePreview(); }); addRenderableWidget(path);
        var names = new EditBox(font, left, 56, panel, 20, Component.literal("Reviewed names"));
        names.setMaxLength(2048); names.setValue(namesValue);
        names.setHint(Component.literal("Optional reviewed names, comma separated"));
        names.setResponder(value -> { namesValue = value; invalidatePreview(); }); addRenderableWidget(names);
        scan = addRenderableWidget(Button.builder(Component.literal("Preview folder"), button -> preview())
            .bounds(left, 82, panel / 2 - 4, 20).build());
        confirm = addRenderableWidget(Button.builder(Component.literal("Confirm Import all"), button -> confirm())
            .bounds(left + panel / 2 + 4, 82, panel / 2 - 4, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Import dashboard"), button ->
            minecraft.setScreen(new ImportDashboardScreen(this, batches, scenes, identities)))
            .bounds(left, 108, panel / 2 - 4, 20).build());
        watch = addRenderableWidget(Button.builder(Component.literal("Enable folder watch"), button -> toggleWatch())
            .bounds(left + panel / 2 + 4, 108, panel / 2 - 4, 20).build());
        if(scenes!=null)sceneReview=addRenderableWidget(Button.builder(Component.literal("Review scenes / skip"),button->minecraft.setScreen(new SceneScreen(this,scenes,identities))).bounds(left,height-77,panel,20).build());
        cancel = addRenderableWidget(Button.builder(Component.literal("Cancel queue"), button -> {
            batches.cancel(); notice = "Cancellation requested; committed checkpoints remain";
        }).bounds(left, height - 52, panel, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(left, height - 27, panel, 20).build());
        tick();
    }

    private void invalidatePreview() { previewScroll = 0; preview = null; confirmWatch = false; revision++; }
    private Set<String> names() {
        return Arrays.stream(namesValue.split(",")).map(String::strip).filter(value -> !value.isEmpty()).collect(Collectors.toSet());
    }
    private Path folder() { return gameDirectory.resolve(Path.of(pathValue.strip())).normalize(); }
    private void preview() {
        try {
            pending = true; preview = null; notice = "Validating folder without importing records";
            long request = ++revision;
            batches.previewFolder(folder(), names()).whenComplete((value, error) -> minecraft.execute(() -> {
                pending = false;
                if (request == revision) { preview = error == null ? value : null;
                    notice = error == null ? "Preview ready; confirm to write eligible files" : "Preview failed; check folder and limits"; }
                tick();
            }));
        } catch (RuntimeException error) { pending = false; notice = "Enter a valid folder path"; }
        tick();
    }
    private void confirm() {
        if (preview == null) return;
        pending = true; notice = "Importing preview; changed files fail individually";
        batches.confirm(preview).whenComplete((value, error) -> minecraft.execute(() -> {
            pending = false; preview = null;
            if(error==null&&scenes!=null)scenes.refreshSuggestions();
            notice = error == null ? "Batch " + value.summary().status() + "; inspect the dashboard" : "Batch stopped; inspect the dashboard";
            tick();
        })); tick();
    }
    private void toggleWatch() {
        boolean enabled = watcher.snapshot().settings().enabled();
        if (!enabled && !confirmWatch) {
            confirmWatch = true;
            notice = "Confirm: scan this folder every 30s, including after restart; finished stable files only";
            tick(); return;
        }
        try {
            pending = true;
            watcher.configure(enabled ? null : folder(), !enabled, enabled ? Set.of() : names())
                .whenComplete((value, error) -> minecraft.execute(() -> {
                    pending = false; confirmWatch = false;
                    notice = error == null ? (value.enabled() ? "Folder watch enabled; active logs excluded" : "Folder watch disabled")
                        : "Watch settings failed; previous settings retained";
                    tick();
                }));
        } catch (RuntimeException invalid) { pending = false; notice = "Enter a valid folder path"; }
        tick();
    }
    @Override public void tick() {
        var state = batches.snapshot().state();
        boolean busy = pending || state == BatchImportService.State.IMPORTING || state == BatchImportService.State.PREVIEWING;
        scan.active = !busy && !pathValue.isBlank(); confirm.active = !busy && preview != null;
        if(sceneReview!=null)sceneReview.active=!busy;
        cancel.active = busy; watch.active = !busy && (!pathValue.isBlank() || watcher.snapshot().settings().enabled());
        watch.setMessage(Component.literal(watcher.snapshot().settings().enabled() ? "Disable folder watch"
            : confirmWatch ? "Confirm folder watch" : "Enable folder watch"));
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        int panel = Math.min(420, width - 32), left = (width - panel) / 2;
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFE5E7EB);


        int y = 134;
        if (preview != null && !confirmWatch) {
            long valid = preview.entries().stream().filter(file -> file.validated() != null).count();
            long known = preview.entries().stream().filter(file -> file.status().equals("DUPLICATE")).count();
            long encoded = preview.entries().stream().filter(file -> file.validated() != null).mapToLong(file -> file.validated().encodedBytes()).sum();
            graphics.drawString(font, valid + " valid | " + known + " known | " + (preview.entries().size() - valid)
                + " invalid | " + preview.entries().size() + " files", left, y, 0xFFD1D5DB); y += 12;
            graphics.drawString(font, font.plainSubstrByWidth("Bytes " + encoded + " encoded / " + preview.decodedBytes()
                + " decoded | Scroll files", panel), left, y, 0xFF9CA3AF); y += 12;
            for (int index = previewScroll; index < preview.entries().size() && y + 10 < height - (scenes==null?57:82); index++) {
                var file = preview.entries().get(index);
                graphics.drawString(font, font.plainSubstrByWidth((index + 1) + ". " + file.status() + ": "
                    + file.path().getFileName(), panel), left, y, 0xFF9CA3AF); y += 11;
            }
        } else {
            for (var line : font.split(Component.literal(notice.isBlank() ? batches.snapshot().message() : notice), panel)) {
                if (y + 10 >= height - (scenes==null?57:82)) break;
                graphics.drawString(font, line, left, y, 0xFF93C5FD); y += 11;
            }
        }
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (preview != null) previewScroll = Math.max(0, Math.min(Math.max(0, preview.entries().size() - 1),
            previewScroll - (int) Math.signum(vertical)));
        return true;
    }

    @Override public Component getNarrationMessage() {
        return Component.literal(title.getString() + ". " + batches.snapshot().state() + ". " + notice
            + (preview == null ? "" : ". Preview files " + preview.entries().size()) + ". " + watcher.snapshot().message());
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
