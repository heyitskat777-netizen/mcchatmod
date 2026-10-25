package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.domain.ImportSource;
import dev.kat.lotcjournal.domain.ImportView;
import dev.kat.lotcjournal.ingestion.LogImportService;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit user-selected historical imports. Rendering reads only worker snapshots. */
public final class LogImportScreen extends Screen {
    private final Screen parent;
    private final LogImportService importer;
    private final Path gameDirectory;
    private List<ImportSource> sources = List.of();
    private String pathValue = "";
    private String namesValue = "";
    private String notice = "";
    private boolean pending;
    private Button start;
    private Button cancel;
    private Button transcript;

    public LogImportScreen(Screen parent, LogImportService importer, Path gameDirectory) {
        super(Component.literal("Historical log import"));
        this.parent = parent;
        this.importer = importer;
        this.gameDirectory = gameDirectory;
    }

    @Override protected void init() {
        int panel = Math.min(360, width - 32), left = (width - panel) / 2;
        var path = new EditBox(font, left, 40, panel, 20, Component.literal("Finished log path"));
        path.setMaxLength(1024);
        path.setHint(Component.literal("Finished .log or .log.gz path"));
        path.setValue(pathValue);
        path.setResponder(value -> pathValue = value);
        addRenderableWidget(path);
        var names = new EditBox(font, left, 84, panel, 20, Component.literal("Reviewed names"));
        names.setMaxLength(2048);
        names.setHint(Component.literal("Reviewed names (comma separated, optional)"));
        names.setValue(namesValue);
        names.setResponder(value -> namesValue = value);
        addRenderableWidget(names);
        start = addRenderableWidget(Button.builder(Component.literal("Import log"), button -> begin())
            .bounds(left, 112, panel / 2 - 4, 20).build());
        cancel = addRenderableWidget(Button.builder(Component.literal("Cancel import"), button -> {
            importer.cancel();
            notice = "Cancellation keeps committed batches; retry resumes";
        }).bounds(left + panel / 2 + 4, 112, panel / 2 - 4, 20).build());
        transcript = addRenderableWidget(Button.builder(Component.literal("View transcripts"), button ->
            minecraft.setScreen(new TranscriptScreen(this, importer, sources)))
            .bounds(left, height - 52, panel, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(left, height - 27, panel, 20).build());
        refreshSources();
        tick();
    }

    private void refreshSources() {
        importer.sources().whenComplete((value, error) -> minecraft.execute(() -> {
            if (error == null) sources = value;
            else notice = "Sources unavailable; check journal storage status";
            tick();
        }));
    }

    private void begin() {
        try {
            Path path = gameDirectory.resolve(Path.of(pathValue.strip())).normalize();
            var names = Arrays.stream(namesValue.split(",")).map(String::strip)
                .filter(value -> !value.isEmpty()).collect(Collectors.toSet());
            pending = true;
            notice = "Validating source before storage";
            tick();
            importer.importFile(path, names).whenComplete((progress, error) -> minecraft.execute(() -> {
                pending = false;
                notice = error == null ? "Finished; view transcripts or import another file"
                    : "Import stopped; see status and retry a finished valid log";
                refreshSources();
                tick();
            }));
        } catch (RuntimeException invalidPath) { notice = "Enter a valid finished log path"; }
    }

    @Override public void tick() {
        var state = importer.snapshot().state();
        boolean busy = pending || state == ImportView.State.VALIDATING || state == ImportView.State.IMPORTING;
        start.active = !busy && !pathValue.isBlank() && state != ImportView.State.CLOSED;
        cancel.active = busy;
        transcript.active = !busy && !sources.isEmpty();
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        int panel = Math.min(360, width - 32), left = (width - panel) / 2;
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFE5E7EB);
        graphics.drawString(font, "Path relative to this instance, or an absolute path", left, 27, 0xFF9CA3AF);
        graphics.drawString(font, "Optional observed names; no identity links are created", left, 71, 0xFF9CA3AF);
        String message = importer.snapshot().message();
        graphics.drawString(font, font.plainSubstrByWidth(message, panel), left, 140, 0xFF93C5FD);
        var progress = importer.snapshot().progress();
        if (progress != null) graphics.drawString(font, "Lines " + progress.processedLines() + " | Records "
            + progress.importedLines() + " | Excluded " + progress.excludedLines(), left, 153, 0xFFD1D5DB);
        if (height > 255) graphics.drawString(font, "Unknown channels stay review-only; date/timezone unresolved", left, 169, 0xFF9CA3AF);
        if (!notice.isBlank() && height > 280)
            graphics.drawString(font, font.plainSubstrByWidth(notice, panel), left, 184, 0xFF9CA3AF);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public Component getNarrationMessage() {
        var state = importer.snapshot();
        return Component.literal(title.getString() + ". " + state.state() + ". " + state.message() + ". " + notice);
    }
    @Override public boolean isPauseScreen() { return false; }
}
