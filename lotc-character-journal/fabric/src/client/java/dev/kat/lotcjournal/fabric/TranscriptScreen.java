package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.domain.Channel;
import dev.kat.lotcjournal.domain.ImportSource;
import dev.kat.lotcjournal.domain.TranscriptLine;
import dev.kat.lotcjournal.ingestion.LogImportService;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractTextAreaWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** Bounded conversation pages with a native scroll bar; provenance is an explicit detail view. */
public final class TranscriptScreen extends Screen {
    private static final int PAGE_SIZE = 50;
    private static final Channel[] CHANNELS = {null, Channel.ROLEPLAY, Channel.UNKNOWN};
    private final Screen parent;
    private final LogImportService importer;
    private final List<ImportSource> sources;
    private int sourceIndex = -1, channelIndex, offset;
    private long total, removed, request;
    private String query = "", notice = "";
    private boolean pending, details;
    private List<TranscriptLine> entries = List.of();
    private Button source, channel, previous, next, detail;
    private DialoguePane pane;

    public TranscriptScreen(Screen parent, LogImportService importer, List<ImportSource> sources) {
        super(Component.literal("Imported transcripts"));
        this.parent = parent; this.importer = importer; this.sources = List.copyOf(sources);
    }
    @Override protected void init() {
        int panel = Math.min(600, width - 32), left = (width - panel) / 2;
        Anchor anchor = pane == null ? null : pane.anchor();
        source = addRenderableWidget(Button.builder(Component.literal("All sources"), button -> {
            sourceIndex = sourceIndex + 1 >= sources.size() ? -1 : sourceIndex + 1; offset = 0; load(false);
        }).bounds(left, 24, panel / 2 - 4, 20).build());
        channel = addRenderableWidget(Button.builder(Component.literal("Channel: ALL"), button -> {
            channelIndex = (channelIndex + 1) % CHANNELS.length; offset = 0; load(false);
        }).bounds(left + panel / 2 + 4, 24, panel / 2 - 4, 20).build());
        var search = new EditBox(font, left, 49, panel - 212, 20, Component.literal("Transcript search"));
        search.setMaxLength(200); search.setHint(Component.literal("Search dialogue")); search.setValue(query);
        search.setResponder(value -> { query = value; offset = 0; load(false); }); addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.literal("Roleplay only"), button -> {
            channelIndex = 1; offset = 0; load(false);
        }).bounds(left + panel - 206, 49, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("UNKNOWN review"), button -> {
            channelIndex = 2; sourceIndex = -1; offset = 0; load(false);
        }).bounds(left + panel - 100, 49, 100, 20).build());
        int part = panel / 4, y = height - 24;
        previous = addRenderableWidget(Button.builder(Component.literal("Previous"), button -> page(-1, true))
            .bounds(left, y, part - 3, 20).build());
        detail = addRenderableWidget(Button.builder(Component.literal("Details"), button -> {
            var saved = pane.anchor(); details = !details; detail.setMessage(Component.literal(details ? "Hide details" : "Details"));
            pane.setEntries(entries, saved, false);
        }).bounds(left + part, y, part - 3, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal("Next"), button -> page(1, false))
            .bounds(left + 2 * part, y, part - 3, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(left + 3 * part, y, panel - 3 * part, 20).build());
        pane = addRenderableWidget(new DialoguePane(left, 88, panel - 7, Math.max(20, height - 118)));
        pane.setEntries(entries, anchor, false);
        detail.setMessage(Component.literal(details ? "Hide details" : "Details"));
        if (entries.isEmpty()) load(false);
        else tick();
    }
    private void page(int direction, boolean bottom) {
        if (pending || (direction < 0 ? offset == 0 : offset + entries.size() >= total)) return;
        offset = direction < 0 ? Math.max(0, offset - PAGE_SIZE) : Math.addExact(offset, PAGE_SIZE);
        load(bottom);
    }
    private void load(boolean bottom) {
        if (pane == null) return;
        long sequence = ++request;
        pending = true; entries = List.of(); pane.setEntries(entries, null, false);
        notice = "Loading dialogue"; tick();
        String sourceId = sourceIndex < 0 ? null : sources.get(sourceIndex).id();
        importer.removedRecords(sourceId).whenComplete((count, error) -> minecraft.execute(() -> {
            if (sequence == request && error == null) {
                removed = count;
                if (details) pane.setEntries(entries, pane.anchor(), false);
            }
        }));
        importer.transcript(sourceId, query, CHANNELS[channelIndex], offset, PAGE_SIZE)
            .whenComplete((page, error) -> minecraft.execute(() -> {
                if (sequence != request) return;
                pending = false;
                if (error != null) { total = 0; notice = "Dialogue unavailable; retry when storage is ready"; }
                else {
                    total = page.total(); entries = page.entries();
                    if (entries.isEmpty() && offset > 0 && total > 0) {
                        offset = (int) Math.min(Integer.MAX_VALUE - PAGE_SIZE, ((total - 1) / PAGE_SIZE) * PAGE_SIZE);
                        load(true); return;
                    }
                    notice = entries.isEmpty() ? (channelIndex == 1 && query.isBlank()
                        ? "No confirmed RP; older or ambiguous imports may need UNKNOWN review"
                        : "No matching records in this filter") : "";
                }
                pane.setEntries(entries, null, bottom); tick();
            }));
    }
    @Override public void tick() {
        previous.active = !pending && offset > 0;
        next.active = !pending && offset + (long) entries.size() < total && offset <= Integer.MAX_VALUE - PAGE_SIZE;
        source.active = !pending && !sources.isEmpty(); channel.active = !pending;
        detail.active = !pending;
        channel.setMessage(Component.literal("Channel: " + (CHANNELS[channelIndex] == null ? "ALL" : CHANNELS[channelIndex])));
        source.setMessage(Component.literal(sourceIndex < 0 ? "All sources (click to filter)" : font.plainSubstrByWidth(
            sources.get(sourceIndex).label(), source.getWidth() - 12)));
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFE5E7EB);
        int panel = Math.min(600, width - 32), left = (width - panel) / 2;
        String status = pending || entries.isEmpty() ? notice : "Messages " + (offset + 1) + "–" + (offset + entries.size())
            + " of " + total + " | Scroll or drag the bar";
        graphics.drawString(font, font.plainSubstrByWidth(status, panel), left, 75, 0xFF93C5FD);
    }
    @Override public Component getNarrationMessage() {
        return Component.literal(title.getString() + ". " + (entries.isEmpty() ? notice : "Messages " + (offset + 1)
            + " to " + (offset + entries.size()) + " of " + total) + ". Scroll " + (int) pane.scrollAmount()
            + " of " + pane.maxScrollAmount() + ". " + pane.visibleText()
            + (details ? ". Removed for selected source(s), all text/channels: " + removed : ""));
    }
    @Override public void onClose() { request++; minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    private record Anchor(int entry, double fraction) {}
    private record Row(int entry, int y, int height, List<FormattedCharSequence> lines, String spoken) {}
    private final class DialoguePane extends AbstractTextAreaWidget {
        private List<Row> rows = List.of();
        private int innerHeight;
        DialoguePane(int x, int y, int width, int height) {
            super(x, y, width, height, Component.literal("Dialogue. Scroll with wheel, arrows or Page Up and Page Down"));
        }
        void setEntries(List<TranscriptLine> values, Anchor anchor, boolean bottom) {
            var result = new ArrayList<Row>(); int y = 0;
            for (int index = 0; index < values.size(); index++) {
                var entry = values.get(index);
                String text = entry.text();
                if (details) text += "\nSource " + sources.stream().filter(value -> value.id().equals(entry.sourceId()))
                    .findFirst().map(ImportSource::label).orElse(entry.sourceId().substring(0, 12))
                    + " | line " + entry.sourceLine() + " | clock " + (entry.clock() == null ? "unknown" : entry.clock())
                    + "\nChannel " + entry.channel() + " | Speaker " + (entry.observedSpeaker() == null ? "unresolved" : entry.observedSpeaker())
                    + " | Date and timezone unresolved";
                var lines = font.split(Component.literal(text), Math.max(16, getWidth() - totalInnerPadding()));
                int height = lines.size() * 12 + 8;
                result.add(new Row(index, y, height, lines, text)); y += height;
            }
            if (details) {
                String text = "Removed for selected source(s): " + removed + " (all text/channels)";
                var lines = font.split(Component.literal(text), Math.max(16, getWidth() - totalInnerPadding()));
                result.add(new Row(values.size(), y, lines.size() * 12 + 8, lines, text)); y += lines.size() * 12 + 8;
            }
            rows = List.copyOf(result); innerHeight = y;
            double target = bottom ? maxScrollAmount() : 0;
            if (anchor != null) for (var row : rows) if (row.entry() == anchor.entry()) {
                target = row.y() + anchor.fraction() * row.height(); break;
            }
            setScrollAmount(target);
        }
        Anchor anchor() {
            for (var row : rows) if (row.y() + row.height() > scrollAmount())
                return new Anchor(row.entry(), Math.max(0, (scrollAmount() - row.y()) / row.height()));
            return null;
        }
        String visibleText() {
            var result = new StringBuilder();
            for (var row : rows) {
                for (int index = 0; index < row.lines().size(); index++) {
                    double y = innerPadding() + row.y() + index * 12 - scrollAmount();
                    if (y + 10 <= 0 || y >= getHeight()) continue;
                    row.lines().get(index).accept((position, style, codePoint) -> { result.appendCodePoint(codePoint); return true; });
                    result.append(' ');
                }
            }
            return result.toString();
        }
        @Override protected int getInnerHeight() { return innerHeight; }
        @Override protected double scrollRate() { return 24; }
        @Override protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            for (var row : rows) {
                if (row.y() > scrollAmount() + getHeight() || row.y() + row.height() < scrollAmount()) continue;
                int first = Math.max(0, (int) ((scrollAmount() - innerPadding() - row.y()) / 12));
                for (int index = first; index < row.lines().size(); index++) {
                    int y = getInnerTop() + row.y() + index * 12;
                    if (y - scrollAmount() >= getBottom()) break;
                    if (withinContentAreaTopBottom(y, y + 10)) graphics.drawString(font, row.lines().get(index), getInnerLeft(), y, 0xFFE5E7EB);
                }
            }
        }
        @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
            double before = scrollAmount();
            if (!pending && vertical < 0 && before >= maxScrollAmount() && next.active) { page(1, false); return true; }
            if (!pending && vertical > 0 && before <= 0 && previous.active) { page(-1, true); return true; }
            return super.mouseScrolled(x, y, horizontal, vertical);
        }
        @Override public boolean keyPressed(KeyEvent event) {
            if (!pending && (event.isDown() || event.key() == GLFW.GLFW_KEY_PAGE_DOWN)
                && scrollAmount() >= maxScrollAmount() && next.active) { page(1, false); return true; }
            if (!pending && (event.isUp() || event.key() == GLFW.GLFW_KEY_PAGE_UP)
                && scrollAmount() <= 0 && previous.active) { page(-1, true); return true; }
            switch (event.key()) {
                case GLFW.GLFW_KEY_PAGE_DOWN -> { setScrollAmount(scrollAmount() + getHeight() - 12); return true; }
                case GLFW.GLFW_KEY_PAGE_UP -> { setScrollAmount(scrollAmount() - getHeight() + 12); return true; }
                case GLFW.GLFW_KEY_HOME -> { setScrollAmount(0); return true; }
                case GLFW.GLFW_KEY_END -> { setScrollAmount(maxScrollAmount()); return true; }
                default -> { return super.keyPressed(event); }
            }
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
            output.add(NarratedElementType.HINT, Component.literal(visibleText()));
        }
    }
}
