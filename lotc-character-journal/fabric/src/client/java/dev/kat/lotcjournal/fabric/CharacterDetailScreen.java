package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.application.IdentityAliases;
import dev.kat.lotcjournal.application.IdentityService;
import dev.kat.lotcjournal.domain.CharacterIdentity;
import dev.kat.lotcjournal.domain.Persona;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A draft or one persisted UUID; no filtered-list index can change the edit target. */
final class CharacterDetailScreen extends Screen {
    private record Draft(String name, String aliases, String account, String notes) {
        static Draft of(CharacterIdentity value) {
            return value == null ? new Draft("", "", "", "")
                : new Draft(value.name(), IdentityAliases.format(value.aliases()), value.accountObservation(), value.notes());
        }
    }
    private final IdentityScreen parent;
    private final IdentityService service;
    private final String identityId;
    private CharacterIdentity persisted;
    private Draft draft, savedDraft;
    private EditBox name, aliases, account, notes;
    private Button save, persona;
    private String status;
    private boolean pending;

    CharacterDetailScreen(IdentityScreen parent, IdentityService service, CharacterIdentity value) {
        super(Component.literal(value == null ? "New character" : "Review character"));
        this.parent = parent; this.service = service;
        identityId = value == null ? UUID.randomUUID().toString() : value.id();
        persisted = value; draft = Draft.of(value); savedDraft = draft;
        status = value == null ? "Enter a name, then Save character before Use as persona" : "Saved character ready. Review or Use as persona";
    }
    @Override protected void init() {
        if (name != null) draft = readDraft();
        int panel = Math.min(520, width - 32), left = (width - panel) / 2;
        name = field(left, 44, panel, "Character name", 96, draft.name());
        aliases = field(left, 74, panel, "Aliases (; separated; use \\; or \\\\ for literals)", IdentityAliases.MAX_TEXT, draft.aliases());
        aliases.setTooltip(Tooltip.create(Component.literal("Separate aliases with ;. Escape a literal semicolon as \\; and a backslash as \\\\. Whitespace inside an alias is preserved.")));
        account = field(left, 104, panel, "Account observation — unverified", 128, draft.account());
        notes = field(left, 134, panel, "Manual shared-archive notes", 16384, draft.notes());
        save = addRenderableWidget(Button.builder(Component.literal("Save character"), button -> save())
            .bounds(left, 160, panel / 2 - 4, 20).build());
        persona = addRenderableWidget(Button.builder(Component.literal("Use as persona"), button -> useAsPersona())
            .bounds(left + panel / 2 + 4, 160, panel / 2 - 4, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(left, height - 26, panel, 20).build());
        tick();
    }
    private EditBox field(int x, int y, int width, String hint, int max, String value) {
        var field = new EditBox(font, x, y, width, 20, Component.literal(hint));
        field.setHint(Component.literal(hint)); field.setMaxLength(max); field.setValue(value);
        return addRenderableWidget(field);
    }
    private Draft readDraft() { return new Draft(name.getValue(), aliases.getValue(), account.getValue(), notes.getValue()); }
    private void save() {
        if (pending) return;
        final CharacterIdentity value;
        try {
            var edited = readDraft();
            value = new CharacterIdentity(identityId, edited.name().strip(), edited.notes(), IdentityAliases.parse(edited.aliases()), edited.account());
        } catch (IllegalArgumentException invalid) { status = "Enter a name and valid aliases (\\; and \\\\ are escapes)"; return; }
        pending = true; status = "Saving character"; tick();
        service.saveIdentity(value).whenComplete((ignored, error) -> minecraft.execute(() -> {
            pending = false;
            if (error != null) status = "Character could not be saved; draft retained. Retry Save character";
            else {
                persisted = value; draft = savedDraft = Draft.of(value); parent.remember(identityId);
                if (minecraft.screen == this) { name.setValue(draft.name()); aliases.setValue(draft.aliases()); account.setValue(draft.account()); notes.setValue(draft.notes()); }
                status = "Character saved. Use as persona is available";
            }
            if (minecraft.screen == this) tick();
        }));
    }
    private void useAsPersona() {
        if (pending || persisted == null || !readDraft().equals(savedDraft)) return;
        CharacterIdentity value = persisted;
        pending = true; status = "Selecting persona"; tick();
        service.personas().thenCompose(values -> {
            var existing = values.stream().filter(persona -> persona.identityId().equals(value.id())).findFirst();
            if (existing.isPresent()) return service.selectPersona(existing.get().id());
            var created = new Persona(UUID.randomUUID().toString(), value.id(), value.name());
            return service.createPersona(created).thenCompose(ignored -> service.selectPersona(created.id()));
        }).whenComplete((ignored, error) -> minecraft.execute(() -> {
            pending = false;
            status = error == null ? "Persona selected; assign messages explicitly to build its memories" : "Persona could not be selected; retry";
            if (minecraft.screen == this) tick();
        }));
    }
    @Override public void tick() {
        if (save == null) return;
        save.active = !pending;
        boolean dirty = !readDraft().equals(savedDraft);
        persona.active = !pending && persisted != null && !dirty;
        persona.setTooltip(Tooltip.create(Component.literal(persisted == null ? "Save this character first" : dirty ? "Save your edits before choosing this persona" : "Choose this saved character as your persona")));
        name.active = aliases.active = account.active = notes.active = !pending;
    }
    @Override public void render(GuiGraphics graphics, int x, int y, float delta) {
        super.render(graphics, x, y, delta);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFE5E7EB);
        int panel = Math.min(520, width - 32), left = (width - panel) / 2;
        graphics.drawString(font, (persisted == null ? "Unsaved draft" : "Saved character") + " #" + identityId.substring(0, 8), left, 24, 0xFF93C5FD);
        graphics.drawString(font, "Character name", left, 34, 0xFF9CA3AF);
        graphics.drawString(font, "Aliases", left, 64, 0xFF9CA3AF);
        graphics.drawString(font, "Account observation (unverified)", left, 94, 0xFF9CA3AF);
        graphics.drawString(font, "Shared archive notes", left, 124, 0xFF9CA3AF);
        String message = persisted != null && !pending && !readDraft().equals(savedDraft) ? "Unsaved edits — Save character to enable Use as persona" : status;
        graphics.drawString(font, font.plainSubstrByWidth(message, panel), left, 190, 0xFF93C5FD);
    }
    @Override public Component getNarrationMessage() { return Component.literal(title.getString() + ". " + status + ". Character " + identityId); }
    @Override public void onClose() { if (persisted != null) parent.remember(identityId); minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
