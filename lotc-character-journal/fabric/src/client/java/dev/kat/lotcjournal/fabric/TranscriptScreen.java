package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.domain.ImportSource;
import dev.kat.lotcjournal.domain.Channel;
import dev.kat.lotcjournal.domain.TranscriptLine;
import dev.kat.lotcjournal.ingestion.LogImportService;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** One source-traceable observation per page. Source and text are never attributed to a persona. */
public final class TranscriptScreen extends Screen {
    private final Screen parent;
    private final LogImportService importer;
    private final List<ImportSource> sources;
    private int sourceIndex = -1;
    private int channelIndex;
    private static final Channel[] CHANNELS = {null, Channel.ROLEPLAY, Channel.SYSTEM, Channel.UNKNOWN};
    private Button channel;
    private long removed;
    private int offset;
    private long total;
    private int scroll;
    private String query = "";
    private String notice = "";
    private TranscriptLine entry;
    private List<FormattedCharSequence> wrapped = List.of();
    private boolean pending;
    private long request;
    private Button source;
    private Button previous;
    private Button next;

    public TranscriptScreen(Screen parent, LogImportService importer, List<ImportSource> sources) {
        super(Component.literal("Imported transcripts"));
        this.parent = parent;
        this.importer = importer;
        this.sources = List.copyOf(sources);
    }

    @Override protected void init() {
        int panel = Math.min(420, width - 32), left = (width - panel) / 2;
        source = addRenderableWidget(Button.builder(Component.literal("Source"), button -> {
            sourceIndex = sourceIndex + 1 >= sources.size() ? -1 : sourceIndex + 1;
            offset = 0;
            load();
        }).bounds(left, 28, panel, 20).build());
        var search = new EditBox(font, left, 55, panel, 20, Component.literal("Transcript search"));
        search.setMaxLength(200);
        search.setHint(Component.literal("Search imported text"));
        search.setValue(query);
        search.setResponder(value -> { query = value; offset = 0; load(); });
        addRenderableWidget(search);
        channel = addRenderableWidget(Button.builder(Component.literal("Channel: ALL"), button -> {
            channelIndex = (channelIndex + 1) % CHANNELS.length; offset = 0; load();
        }).bounds(left, 82, panel / 2 - 4, 20).build());
        addRenderableWidget(Button.builder(Component.literal("UNKNOWN review"), button -> {
            channelIndex = 3; sourceIndex = -1; offset = 0; load();
        }).bounds(left + panel / 2 + 4, 82, panel / 2 - 4, 20).build());
        previous = addRenderableWidget(Button.builder(Component.literal("Previous"), button -> { offset--; load(); })
            .bounds(left, height - 51, panel / 2 - 4, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal("Next"), button -> { offset++; load(); })
            .bounds(left + panel / 2 + 4, height - 51, panel / 2 - 4, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(left, height - 26, panel, 20).build());
        load();
    }

    private void load() {
        if (sources.isEmpty()) { notice = "No imported sources"; return; }
        long sequence = ++request;
        pending = true;
        entry = null;
        wrapped = List.of();
        notice = "Loading transcript";
        scroll = 0;
        tick();
        String sourceId = sourceIndex < 0 ? null : sources.get(sourceIndex).id();
        importer.removedRecords(sourceId).whenComplete((count, error) -> minecraft.execute(() -> {
            if (sequence == request && error == null) removed = count;
        }));
        importer.transcript(sourceId, query, CHANNELS[channelIndex], offset, 1)
            .whenComplete((page, error) -> minecraft.execute(() -> {
                if (sequence != request) return;
                pending = false;
                if (error != null) { total = 0; notice = "Transcript unavailable; retry after storage is ready"; }
                else {
                    total = page.total();
                    entry = page.entries().isEmpty() ? null : page.entries().getFirst();
                    wrapped = entry == null ? List.of() : font.split(Component.literal(entry.raw()), Math.min(420, width - 32));
                    notice = entry == null ? "No matching records in this filter" : "";
                }
                tick();
            }));
    }

    @Override public void tick() {
        previous.active = !pending && offset > 0;
        next.active = !pending && offset + 1L < total;
        source.active = !pending && !sources.isEmpty();
        channel.setMessage(Component.literal("Channel: " + (CHANNELS[channelIndex] == null ? "ALL" : CHANNELS[channelIndex])));
        source.setMessage(Component.literal(sourceIndex < 0 ? "All sources (click to filter)" : font.plainSubstrByWidth(
            "Source " + (sourceIndex + 1) + "/" + sources.size() + ": " + sources.get(sourceIndex).label(), source.getWidth() - 12)));
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (entry != null) {
            int lines = wrapped.size();
            int visible = Math.max(1, (height - 221) / 12);
            scroll = Math.max(0, Math.min(Math.max(0, lines - visible), scroll - (int) Math.signum(vertical) * 3));
        }
        return true;
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        int panel = Math.min(420, width - 32), left = (width - panel) / 2;
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFE5E7EB);
        if (entry != null) {
            graphics.drawString(font, "Record " + (offset + 1) + "/" + total + " | Source line " + entry.sourceLine()
                + " | Clock " + (entry.clock() == null ? "unknown" : entry.clock()), left, 109, 0xFF93C5FD);
            graphics.drawString(font, "Channel: " + entry.channel() + " | Speaker: "
                + (entry.observedSpeaker() == null ? "unresolved" : entry.observedSpeaker()), left, 122, 0xFFD1D5DB);
            graphics.drawString(font, "Date/zone unknown | Source " + entry.sourceId().substring(0, 12) + " | Scroll text", left, 135, 0xFF9CA3AF);
            int y = 164;
            for (int index = scroll; index < wrapped.size() && y + 10 < height - 58; index++, y += 12)
                graphics.drawString(font, wrapped.get(index), left, y, 0xFFE5E7EB);
        } else {
            graphics.drawString(font, notice, left, 113, 0xFF9CA3AF);
            graphics.drawString(font, "Active matching records: " + total, left, 126, 0xFFD1D5DB);
        }
        graphics.drawString(font, "Removed for selected source(s): " + removed + " (all text/channels)", left, 148, 0xFF9CA3AF);
    }

    @Override public void onClose() { request++; minecraft.setScreen(parent); }
    @Override public Component getNarrationMessage() {
        if (entry == null) return Component.literal(title.getString() + ". " + notice + ". Active matches " + total
            + ". Removed for selected source(s), all text/channels: " + removed);
        return Component.literal("Record " + (offset + 1) + " of " + total + ". Source "
            + sources.stream().filter(source -> source.id().equals(entry.sourceId())).findFirst().map(ImportSource::label).orElse(entry.sourceId()) + ", line " + entry.sourceLine() + ". Channel "
            + entry.channel() + ". Clock " + (entry.clock() == null ? "unknown" : entry.clock())
            + ". Source fingerprint " + entry.sourceId() + ". Date and timezone unresolved. " + entry.raw());
    }
    @Override public boolean isPauseScreen() { return false; }
}
