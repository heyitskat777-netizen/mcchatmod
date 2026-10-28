package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.domain.BatchSummary;
import dev.kat.lotcjournal.ingestion.BatchImportService;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Durable reports contain safe codes, never exception messages or source payloads. */
public final class ImportDashboardScreen extends Screen {
    private final Screen parent;
    private final dev.kat.lotcjournal.application.SceneService scenes;
    private final dev.kat.lotcjournal.application.IdentityService identities;
    private final BatchImportService batches;
    private List<BatchSummary> summaries = List.of();
    private BatchImportService.BatchReport report;
    private int selected, fileIndex, scroll;
    private long unknown;
    private boolean pending, confirmUndo;
    private long request;
    private String notice = "Loading saved batches";
    private Button batch, previous, next, retry, undo;

    public ImportDashboardScreen(Screen parent, BatchImportService batches) {
        this(parent,batches,null,null);
    }
    public ImportDashboardScreen(Screen parent,BatchImportService batches,dev.kat.lotcjournal.application.SceneService scenes,dev.kat.lotcjournal.application.IdentityService identities) {
        super(Component.literal("Import dashboard"));this.scenes=scenes;this.identities=identities; this.parent = parent; this.batches = batches;
    }
    @Override protected void init() {
        int panel = Math.min(420, width - 32), left = (width - panel) / 2;
        batch = addRenderableWidget(Button.builder(Component.literal("Next batch"), button -> {
            selected = (selected + 1) % summaries.size(); fileIndex = 0; load();
        }).bounds(left, 32, panel, 20).build());
        previous = addRenderableWidget(Button.builder(Component.literal("Previous file"), button -> { fileIndex--; tick(); })
            .bounds(left, height - 104, panel / 2 - 4, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal("Next file"), button -> { fileIndex++; tick(); })
            .bounds(left + panel / 2 + 4, height - 104, panel / 2 - 4, 20).build());
        retry = addRenderableWidget(Button.builder(Component.literal("Retry failed"), button -> retry())
            .bounds(left, height - 78, panel / 2 - 4, 20).build());
        undo = addRenderableWidget(Button.builder(Component.literal("Undo batch"), button -> undo())
            .bounds(left + panel / 2 + 4, height - 78, panel / 2 - 4, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh reports"), button -> refresh())
            .bounds(left, height - 52, scenes==null?panel:panel/2-4, 20).build());
        if(scenes!=null)addRenderableWidget(Button.builder(Component.literal("Review scenes / skip"),b->minecraft.setScreen(new SceneScreen(this,scenes,identities))).bounds(left+panel/2+4,height-52,panel/2-4,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(left, height - 26, panel, 20).build());
        refresh(); tick();
    }
    private void refresh() {
        pending = true; long sequence = ++request;
        batches.batches().whenComplete((value, error) -> minecraft.execute(() -> {
            if (sequence != request) return;
            pending = false;
            if (error != null) notice = "Reports unavailable; retry when storage is ready";
            else {
                summaries = value; selected = Math.min(selected, Math.max(0, summaries.size() - 1));
                if (summaries.isEmpty()) { report = null; notice = "No saved batches"; }
                else load();
            }
            tick();
        }));
    }
    private void load() {
        if (summaries.isEmpty()) return;
        pending = true; confirmUndo = false; scroll = 0; long sequence = ++request;
        batches.unknownRecords(summaries.get(selected).id()).whenComplete((value, error) -> minecraft.execute(() -> {
            if (sequence == request && error == null) unknown = value;
        }));
        batches.report(summaries.get(selected).id()).whenComplete((value, error) -> minecraft.execute(() -> {
            if (sequence != request) return;
            pending = false;
            if (error != null) notice = "Batch unavailable; refresh reports";
            else { report = value; fileIndex = Math.min(fileIndex, Math.max(0, report.files().size() - 1)); notice = ""; }
            tick();
        })); tick();
    }
    private void retry() {
        if (report == null) return;
        pending = true; notice = "Retrying failed/interrupted files under their saved policy";
        batches.retry(report.summary().id()).whenComplete((value, error) -> minecraft.execute(() -> {
            pending = false; if(error==null&&scenes!=null)scenes.refreshSuggestions();notice = error == null ? "Retry finished: " + value.summary().status() : "Retry stopped; inspect file status";
            refresh();
        })); tick();
    }
    private void undo() {
        if (report == null) return;
        if (!confirmUndo) {
            confirmUndo = true; scroll = 0; notice = "Confirm deletion of this batch's new records only. Original files and older imports remain.";
            tick(); return;
        }
        pending = true;
        batches.undo(report.summary().id()).whenComplete((count, error) -> minecraft.execute(() -> {
            pending = false; confirmUndo = false;
            notice = error == null ? "Removed " + count + " active records; source checkpoints retained"
                : "Undo unavailable; stop active imports and refresh";
            refresh();
        })); tick();
    }
    @Override public void tick() {
        boolean usable = !pending && report != null;
        batch.active = !pending && summaries.size() > 1;
        previous.active = usable && fileIndex > 0;
        next.active = usable && fileIndex + 1 < report.files().size();
        String status = report == null ? "" : report.summary().status();
        boolean terminal = !status.equals("RUNNING") && !status.equals("PENDING") && !status.equals("UNDONE");
        retry.active = usable && terminal && report.files().stream().anyMatch(file ->
            List.of("FAILED", "CANCELLED", "INTERRUPTED", "PENDING").contains(file.status()));
        undo.active = usable && terminal;
        undo.setMessage(Component.literal(confirmUndo ? "Confirm undo" : "Undo batch"));
        if (!summaries.isEmpty()) batch.setMessage(Component.literal("Batch " + (selected + 1) + "/" + summaries.size() + " (next)"));
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        int panel = Math.min(420, width - 32), left = (width - panel) / 2, y = 61;
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFE5E7EB);
        String text = notice;
        if (report != null) {
            var summary = report.summary();
            text = (notice.isBlank() ? "" : notice + "\n") + "Batch " + summary.status() + " | " + summary.fileCount() + " files | Active UNKNOWN " + unknown + "\nID " + summary.id() + "\n";
            if (!report.files().isEmpty()) {
                var file = report.files().get(fileIndex);
                text += "File " + (fileIndex + 1) + "/" + report.files().size() + ": " + file.label() + "\n"
                    + file.status() + (file.errorCode() == null ? "" : " | " + file.errorCode()) + "\n"
                    + (summary.status().equals("UNDONE") ? "Added earlier " : "New records ") + file.added() + " | Excluded " + file.excluded() + " | Ignored " + file.ignored()
                    + "\nSource lines " + file.beforeLine() + " → " + file.afterLine() + "\n";
            }

        }
        var lines = font.split(Component.literal(text), panel);
        for (int index = Math.min(scroll, Math.max(0, lines.size() - 1)); index < lines.size(); index++) {
            var line = lines.get(index);
            if (y + 10 >= height - 110) break;
            graphics.drawString(font, line, left, y, 0xFFD1D5DB); y += 12;
        }
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll = Math.max(0, Math.min(100, scroll - (int) Math.signum(vertical) * 2));
        return true;
    }
    @Override public Component getNarrationMessage() {
        return Component.literal(title.getString() + ". " + (report == null ? "" : report.summary().status()
            + ". Files " + report.summary().fileCount()) + ". " + notice);
    }
    @Override public void onClose() { request++; minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
