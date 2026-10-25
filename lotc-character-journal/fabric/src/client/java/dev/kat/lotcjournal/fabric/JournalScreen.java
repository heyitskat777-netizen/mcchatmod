package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.application.JournalService;
import dev.kat.lotcjournal.application.JournalSnapshot;
import dev.kat.lotcjournal.ingestion.LogImportService;
import java.nio.file.Path;
import java.time.Instant;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.fabricmc.loader.api.FabricLoader;

/** Phase 1 shell. Reads snapshots only; SQL and backup work run in JournalService. */
public final class JournalScreen extends Screen {
    private final JournalService service;
    private final Path directory;
    private final LogImportService importer;
    private EditBox search;
    private Button layout;
    private Button backup;
    private Button importLogs;
    private String operation = "";
    private boolean savingLayout;
    private boolean creatingBackup;
    private final String version = FabricLoader.getInstance().getModContainer("lotc_journal")
        .orElseThrow().getMetadata().getVersion().getFriendlyString();

    public JournalScreen(JournalService service, LogImportService importer, Path directory) {
        super(Component.literal("Character Journal"));
        this.service = service;
        this.directory = directory;
        this.importer = importer;
    }

    @Override protected void init() {
        int panel = Math.min(340, width - 32);
        int left = (width - panel) / 2;
        search = new EditBox(font, left, 55, panel, 20, Component.literal("Search characters"));
        search.setHint(Component.literal("Search characters — available after import"));
        search.setMaxLength(120);
        addRenderableWidget(search);
        importLogs = addRenderableWidget(Button.builder(Component.literal("Log import"), button ->
            minecraft.setScreen(new LogImportScreen(this, importer, FabricLoader.getInstance().getGameDir())))
            .bounds(left, 140, panel, 20).build());
        int y = Math.max(140, height - 76);
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
            }).bounds(left, y, panel / 2 - 4, 20).build());
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
        }).bounds(left + panel / 2 + 4, y, panel / 2 - 4, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(left, y + 25, panel, 20).build());
        tick();
    }

    @Override public void tick() {
        var state = service.snapshot();
        boolean ready = state.status() == JournalSnapshot.Status.READY;
        layout.active = ready && !savingLayout && !creatingBackup;
        backup.active = layout.active;
        importLogs.active = layout.active;
        layout.setMessage(Component.literal("Layout: " +
            (state.settings().compactLayout() ? "Compact" : "Comfortable")));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, title, width / 2, 20, 0xFFE5E7EB);
        graphics.drawCenteredString(font, "Journal " + version + " | MC 1.21.11", width / 2, 36, 0xFF9CA3AF);
        int left = (width - Math.min(340, width - 32)) / 2;
        graphics.drawString(font, search.getValue().isBlank()
            ? "No characters imported yet." : "Character search is not connected yet.",
            left, 87, 0xFFD1D5DB);
        graphics.drawString(font, "AI: disabled | Live capture: not enabled", left, 103, 0xFF93C5FD);
        graphics.drawString(font, service.snapshot().message(), left, 116, 0xFF9CA3AF);
        if (!operation.isBlank())
            graphics.drawCenteredString(font, operation, width / 2, 128, 0xFFA7F3D0);
    }

    @Override public boolean isPauseScreen() { return false; }
}

