package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.application.JournalService;
import dev.kat.lotcjournal.application.JournalSnapshot;
import java.nio.file.Path;
import java.time.Instant;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Phase 1 shell. Reads snapshots only; SQL and backup work run in JournalService. */
public final class JournalScreen extends Screen {
    private final JournalService service;
    private final Path directory;
    private EditBox search;
    private Button layout;
    private Button backup;
    private String operation = "";

    public JournalScreen(JournalService service, Path directory) {
        super(Component.literal("Character Journal"));
        this.service = service;
        this.directory = directory;
    }

    @Override protected void init() {
        int panel = Math.min(340, width - 32);
        int left = (width - panel) / 2;
        search = new EditBox(font, left, 55, panel, 20, Component.literal("Search characters"));
        search.setHint(Component.literal("Search characters — available after import"));
        search.setMaxLength(120);
        addRenderableWidget(search);
        int y = Math.max(110, height - 76);
        layout = addRenderableWidget(Button.builder(Component.literal("Layout"),
            button -> service.toggleCompactLayout().exceptionally(error -> {
                minecraft.execute(() -> operation = "Layout could not be saved");
                return null;
            })).bounds(left, y, panel / 2 - 4, 20).build());
        backup = addRenderableWidget(Button.builder(Component.literal("Create backup"), button -> {
            operation = "Creating backup...";
            backup.active = false;
            service.backup(directory.resolve("backups").resolve(
                "journal-" + Instant.now().toEpochMilli() + ".sqlite"))
                .whenComplete((path, error) -> minecraft.execute(() ->
                    operation = error == null ? "Backup created" : "Backup failed; see log"));
        }).bounds(left + panel / 2 + 4, y, panel / 2 - 4, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(left, y + 25, panel, 20).build());
    }

    @Override public void tick() {
        var state = service.snapshot();
        boolean ready = state.status() == JournalSnapshot.Status.READY;
        layout.active = ready;
        backup.active = ready && !operation.equals("Creating backup...");
        layout.setMessage(Component.literal("Layout: " +
            (state.settings().compactLayout() ? "Compact" : "Comfortable")));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, title, width / 2, 20, 0xFFE5E7EB);
        graphics.drawCenteredString(font, "Foundation build • Fabric 1.21.11", width / 2, 36, 0xFF9CA3AF);
        int left = (width - Math.min(340, width - 32)) / 2;
        graphics.drawString(font, search.getValue().isBlank()
            ? "No characters imported yet." : "Character search is not connected yet.",
            left, 87, 0xFFD1D5DB);
        if (height >= 260) {
            graphics.drawString(font, "AI: disabled  |  Chat capture: not enabled", left, 105, 0xFF93C5FD);
            graphics.drawString(font, service.snapshot().message(), left, 123, 0xFF9CA3AF);
        }
        if (!operation.isBlank())
            graphics.drawCenteredString(font, operation, width / 2, Math.max(100, height - 94), 0xFFA7F3D0);
    }

    @Override public boolean isPauseScreen() { return false; }
}

