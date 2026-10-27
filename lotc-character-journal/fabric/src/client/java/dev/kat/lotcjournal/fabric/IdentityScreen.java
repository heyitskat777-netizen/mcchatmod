package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.application.IdentityService;
import dev.kat.lotcjournal.domain.CharacterIdentity;
import dev.kat.lotcjournal.ingestion.LogImportService;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Visible paged saved-character list. Editing and new drafts have separate screens. */
final class IdentityScreen extends Screen {
    private final Screen parent;
    private final IdentityService service;
    final LogImportService importer;
    private List<CharacterIdentity> identities = List.of();
    private final List<Button> rows = new ArrayList<>();
    private EditBox search;
    private Button previous, next, pageLabel, retry;
    private String query, selectedId, status = "Loading characters", loadedQuery = "";
    private int page, pageSize;
    private long request;
    private boolean pending;

    IdentityScreen(Screen parent, IdentityService service, String query) {
        this(parent, service, query, null);
    }
    IdentityScreen(Screen parent, IdentityService service, String query, LogImportService importer) {
        super(Component.literal("Characters — shared archive"));
        this.parent = parent; this.service = service; this.query = query; this.importer = importer;
    }
    @Override protected void init() {
        int panel = Math.min(520, width - 32), left = (width - panel) / 2;
        search = addRenderableWidget(new EditBox(font, left, 24, panel - 85, 20, Component.literal("Search characters")));
        search.setMaxLength(256); search.setHint(Component.literal("Search names, aliases or account observations"));
        search.setValue(query);
        search.setResponder(value -> { query = value; page = 0; load(false); });
        addRenderableWidget(Button.builder(Component.literal("Clear search"), button -> search.setValue(""))
            .bounds(left + panel - 80, 24, 80, 20).build());
        pageSize = Math.max(1, (height - 135) / 25);
        rows.clear();
        for (int row = 0; row < pageSize; row++) {
            final int index = row;
            rows.add(addRenderableWidget(Button.builder(Component.empty(), button -> open(index))
                .bounds(left, 64 + 25 * row, panel, 20).build()));
        }
        int third = panel / 3;
        previous = addRenderableWidget(Button.builder(Component.literal("Previous"), button -> { page--; updateRows(); })
            .bounds(left, height - 76, third - 3, 20).build());
        pageLabel = addRenderableWidget(Button.builder(Component.empty(), button -> {})
            .bounds(left + third, height - 76, third - 3, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal("Next"), button -> { page++; updateRows(); })
            .bounds(left + third * 2, height - 76, panel - third * 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("New character"), button ->
            minecraft.setScreen(new CharacterDetailScreen(this, service, null)))
            .bounds(left, height - 51, third - 3, 20).build());
        retry = addRenderableWidget(Button.builder(Component.literal("Refresh / retry"), button -> load(true))
            .bounds(left + third, height - 51, third - 3, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Review links"), button ->
            minecraft.setScreen(new IdentityMergeScreen(this, service)))
            .bounds(left + third * 2, height - 51, panel - third * 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(left, height - 26, panel, 20).build());
        updateRows(); load(true);
    }
    void remember(String id) { selectedId = id; }
    private void open(int row) {
        int index = page * pageSize + row;
        if (pending || index >= identities.size()) return;
        var value = identities.get(index); selectedId = value.id();
        minecraft.setScreen(new CharacterDetailScreen(this, service, value));
    }
    private void load(boolean revealSelection) {
        long token = ++request;
        pending = true; status = "Loading characters"; updateRows();
        final String requestedQuery = query;
        service.identities(requestedQuery).whenComplete((values, error) -> minecraft.execute(() -> {
            if (token != request || minecraft.screen != this) return;
            pending = false;
            if (error != null) status = "Refresh failed; previous results retained. Refresh / retry";
            else {
                identities = values; loadedQuery = requestedQuery;
                if (revealSelection && selectedId != null) for (int index = 0; index < values.size(); index++) {
                    if (values.get(index).id().equals(selectedId)) { page = index / pageSize; break; }
                }
                status = values.isEmpty() ? (requestedQuery.isBlank() ? "No saved characters. Choose New character" : "No matching characters. Clear search or choose New character")
                    : "Click a character to review, edit or Use as persona";
            }
            updateRows();
        }));
    }
    private void updateRows() {
        if (previous == null) return;
        page = Math.max(0, Math.min(page, Math.max(0, (identities.size() - 1) / pageSize)));
        for (int row = 0; row < rows.size(); row++) {
            var button = rows.get(row); int index = page * pageSize + row;
            button.visible = index < identities.size(); button.active = button.visible && !pending;
            if (!button.visible) continue;
            var value = identities.get(index);
            String label = value.name() + " #" + value.id().substring(0, 8);
            button.setMessage(Component.literal((value.id().equals(selectedId) ? "> " : "") + font.plainSubstrByWidth(label, button.getWidth() - 18)));
            button.setTooltip(Tooltip.create(Component.literal(value.name() + "\nID: " + value.id()
                + "\nAliases: " + value.aliases().stream().sorted().limit(3).collect(java.util.stream.Collectors.joining("; ")))));
        }
        previous.active = !pending && page > 0;
        next.active = !pending && (page + 1) * pageSize < identities.size();
        pageLabel.active = false;
        pageLabel.setMessage(Component.literal("Page " + (page + 1) + " / " + Math.max(1, (identities.size() + pageSize - 1) / pageSize)));
        retry.active = !pending;
    }
    @Override public void render(GuiGraphics graphics, int x, int y, float delta) {
        super.render(graphics, x, y, delta);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFE5E7EB);
        int panel = Math.min(520, width - 32), left = (width - panel) / 2;
        String count = pending ? status : identities.size() + " saved character(s)" + (identities.size() == 1000 ? " (search narrows first 1000 matches)" : "");
        graphics.drawString(font, font.plainSubstrByWidth(count + " | " + status, panel), left, 49, 0xFF93C5FD);
    }
    @Override public Component getNarrationMessage() {
        return Component.literal(title.getString() + ". " + status + ". " + identities.size() + " saved characters. Results for " + loadedQuery
            + ". Page " + (page + 1) + ". " + identities.stream().skip((long) page * pageSize).limit(pageSize)
                .map(value -> value.name() + " #" + value.id().substring(0, 8)).collect(java.util.stream.Collectors.joining(". ")));
    }
    @Override public void onClose() { request++; minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
