package dev.kat.lotcjournal.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;

/** Exercises the installed adapter through input and public widgets, never by constructing its services. */
@SuppressWarnings("UnstableApiUsage")
public final class JournalClientGameTest implements FabricClientGameTest {
    private static final String SCREEN_CLASS = "dev.kat.lotcjournal.fabric.JournalScreen";
    private static final String IMPORT_SCREEN = "dev.kat.lotcjournal.fabric.LogImportScreen";
    private static final String TRANSCRIPT_SCREEN = "dev.kat.lotcjournal.fabric.TranscriptScreen";
    private static final String FOLDER_SCREEN = "dev.kat.lotcjournal.fabric.FolderImportScreen";
    private static final String DASHBOARD_SCREEN = "dev.kat.lotcjournal.fabric.ImportDashboardScreen";
    private static final int WAIT_TICKS = 400;

    @Override public void runTest(ClientGameTestContext context) {
        var report = new SmokeReport();
        try {
            Path profile = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
            String expected = System.getProperty("lotc.journal.smoke.profile");
            Path database = FabricLoader.getInstance().getConfigDir().resolve("lotc-journal/journal.sqlite")
                .toAbsolutePath().normalize();
            report.stage("startup", 30_000, () -> {
                check(FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT, "Must run a real client");
                if (expected != null) {
                    check(profile.toRealPath().equals(Path.of(expected).toRealPath()), "Unexpected test profile");
                    check(database.startsWith(profile), "Journal config must stay inside the isolated profile");
                }
                context.waitFor(client -> client.screen instanceof TitleScreen && client.level == null
                    && client.player == null && client.getWindow().getWidth() > 0
                    && client.getWindow().getHeight() > 0, WAIT_TICKS);
                if (!report.phase().equals("preflight")) {
                    var metadata = FabricLoader.getInstance().getModContainer("lotc_journal")
                        .orElseThrow(() -> new AssertionError("Journal mod was not installed"));
                    String expectedVersion = System.getProperty("lotc.journal.smoke.expectedVersion");
                    if (report.phase().equals("standalone") && expectedVersion == null)
                        expectedVersion = metadata.getMetadata().getVersion().getFriendlyString();
                    check(expectedVersion != null && metadata.getMetadata().getVersion().getFriendlyString().equals(expectedVersion),
                        "Unexpected tested journal version");
                    var adapter = Class.forName("dev.kat.lotcjournal.fabric.JournalClient");
                    var sqlite = Class.forName("org.sqlite.JDBC");
                    report.origin("journal", adapter);
                    report.origin("sqlite", sqlite);
                    var ingestion = Class.forName("dev.kat.lotcjournal.ingestion.LogImportService");
                    var parsing = Class.forName("dev.kat.lotcjournal.parsing.LogLineParser");
                    report.origin("ingestion", ingestion);
                    report.origin("parsing", parsing);
                    if (report.mode().equals("packaged")) {
                        check(isJar(adapter) && isJar(sqlite) && isJar(ingestion) && isJar(parsing),
                            "Packaged run must load journal, import/parser modules and SQLite from JARs");
                    }
                } else {
                    check(!FabricLoader.getInstance().isModLoaded("lotc_journal"), "Graphics preflight must exclude the journal mod");
                    report.screenshot(context.takeScreenshot("lotc-graphics-preflight"));
                }
            });
            if (!report.phase().equals("preflight")) exerciseJournal(context, report, database);
            report.bodyComplete();
        } catch (Throwable failure) {
            try {
                report.screenshot(context.takeScreenshot("lotc-journal-failure-" + report.phase()));
            } catch (Throwable screenshotFailure) {
                failure.addSuppressed(screenshotFailure);
            }
            try {
                report.bodyFailure(failure);
            } catch (Throwable reportingFailure) {
                failure.addSuppressed(reportingFailure);
            }
            if (failure instanceof Error error) throw error;
            throw new RuntimeException("Journal client smoke test failed", failure);
        }
    }

    private void exerciseJournal(ClientGameTestContext context, SmokeReport report, Path database) throws Exception {
        // Fabric resets game options before each test, so options.txt alone cannot
        // enforce the small render footprint of this isolated CI smoke profile.
        context.runOnClient(client -> {
            client.options.renderDistance().set(2);
            client.options.simulationDistance().set(5);
        });
        var world = report.measure("local-world", 120_000, () -> {
            var created = context.worldBuilder().create();
            context.waitFor(client -> client.level != null && client.player != null && client.screen == null, WAIT_TICKS);
            return created;
        });
        try (world) {
            KeyMapping open = report.measure("keybind-open", 30_000, () -> {
                KeyMapping key = context.computeOnClient(client -> Arrays.stream(client.options.keyMappings)
                    .filter(mapping -> mapping.getName().equals("key.lotc_journal.open")).findFirst()
                    .orElseThrow(() -> new AssertionError("Registered journal key mapping is missing")));
                check(context.computeOnClient(client -> KeyBindingHelper.getBoundKeyOf(key).getValue())
                    == GLFW.GLFW_KEY_J, "Fresh test profile must use default J binding");
                context.getInput().pressKey(GLFW.GLFW_KEY_J);
                waitForJournal(context);
                context.runOnClient(client -> check(!client.screen.isPauseScreen(), "Journal must not pause play"));
                return key;
            });
            boolean initial = readLayout(database);
            if (report.phase().equals("write")) {
                report.stage("initial-defaults", 10_000, () -> {
                    check(!initial, "Fresh first process must start with default comfortable layout");
                    check(context.computeOnClient(client -> hasButton(client.screen, "Layout: Comfortable")),
                        "UI must agree with the fresh persisted default");
                });
            } else if (report.phase().equals("read")) {
                report.stage("restart-persistence", 10_000, () -> {
                    check(initial, "Second Minecraft process must retain the first process's compact setting");
                    check(context.computeOnClient(client -> hasButton(client.screen, "Layout: Compact")),
                        "Second process UI must show the previously persisted layout");
                });
            }
            report.stage("search-widget", 20_000, () -> {
                context.runOnClient(client -> {
                    EditBox search = search(client.screen);
                    check(search.getValue().isEmpty(), "Search starts empty");
                    client.screen.setFocused(search);
                    search.setFocused(true);
                });
                context.getInput().typeChars("Synthetic Visitor");
                context.runOnClient(client -> check(search(client.screen).getValue().equals("Synthetic Visitor"),
                    "Search field must accept actual typed input"));
            });
            report.stage("live-startup", 20_000, () -> {
                context.waitFor(client -> client.screen.getNarrationMessage().getString().contains(report.phase().equals("read") ? "Live PAUSED" : "Live CAPTURING"), WAIT_TICKS);
                if (!report.phase().equals("read")) context.clickScreenButton("Pause live capture");
                context.waitFor(client -> screenButtonActive(client.screen, "Resume live capture")
                    && client.screen.getNarrationMessage().getString().contains("Live PAUSED"), WAIT_TICKS);
                check(sqlCount(database,"SELECT count(*) FROM journal_settings WHERE key='live_paused' AND value='true'")==1,
                    "Capture auto-starts and explicit pause must persist across JVM restart");
            });
            exerciseImport(context, report, database);
            exerciseBatchImport(context, report, database);
            exerciseLiveAndIdentity(context, report, database);
            report.stage("layout-save", 30_000, () -> {
                // The read phase proves that changing the setting still works after a process restart.
                if (initial) toggle(context, false);
                toggle(context, true);
                check(readLayout(database), "Layout action must commit to the production database");
            });
            report.stage("backup", 30_000, () -> {
                Path backups = database.getParent().resolve("backups");
                List<Path> before = backupFiles(backups);
                context.clickScreenButton("Create backup");
                context.waitFor(client -> hasButton(client.screen, "Create backup"), WAIT_TICKS);
                List<Path> created = backupFiles(backups).stream().filter(path -> !before.contains(path)).toList();
                check(created.size() == 1, "Backup button must create exactly one fresh snapshot");
                check(readLayout(created.getFirst()), "Backup must contain the selected compact layout");
                toggle(context, false);
                check(!readLayout(database), "Source setting must change after backup");
                check(readLayout(created.getFirst()), "Saved backup must remain independent of later source edits");
                // Phased runs intentionally preserve true for the next JVM; standalone restores its initial value.
                if (!report.phase().equals("standalone") || initial) toggle(context, true);
                report.screenshot(context.takeScreenshot("lotc-journal-" + report.phase()));
            });
            report.stage("journal-close", 20_000, () -> {
                context.clickScreenButton("Done");
                context.waitFor(client -> client.screen == null && client.player != null, WAIT_TICKS);
            });
            report.stage("keybind-rebind", 30_000, () -> {
                var original = context.computeOnClient(client -> KeyBindingHelper.getBoundKeyOf(open));
                try {
                    context.runOnClient(client -> {
                        open.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_K));
                        KeyMapping.resetMapping();
                    });
                    context.getInput().pressKey(GLFW.GLFW_KEY_J);
                    context.waitTicks(2);
                    context.runOnClient(client -> check(client.screen == null, "Old binding must stop opening the journal"));
                    context.getInput().pressKey(GLFW.GLFW_KEY_K);
                    waitForJournal(context);
                    context.clickScreenButton("Done");
                    context.waitFor(client -> client.screen == null && client.player != null, WAIT_TICKS);
                } finally {
                    context.runOnClient(client -> { open.setKey(original); KeyMapping.resetMapping(); });
                }
            });
        }
        report.stage("world-leave", 30_000, () -> context.waitFor(
            client -> client.level == null && client.player == null && client.getSingleplayerServer() == null
                && client.screen instanceof TitleScreen, WAIT_TICKS));
    }

    private void exerciseImport(ClientGameTestContext context, SmokeReport report, Path database) throws Exception {
        String raw = "[23:59:59] [Render thread/INFO]: [System] [CHAT][Quiet] §aSynthetic Visitor§r waves.";
        var content = new StringBuilder(raw + "\n" + raw + "\n");
        for (int index = 3; index <= 62; index++) content.append("[23:59:59] [Render thread/INFO]: [System] [CHAT]" + (index <= 52 ? "[Quiet] " : " "))
            .append(dialogue(index)).append('\n');
        for (String notice : systemNotices()) content.append("[23:59:59] [Render thread/INFO]: [System] [CHAT] ").append(notice).append('\n');
        String fixture = content + "[00:00:00] [Render thread/INFO]: [CHAT] [PM] IMPORT_PRIVATE_SENTINEL\n"
            + "[00:00:01] [Render thread/INFO]: [CHAT] [OOC]IMPORT_OOC_SENTINEL\n"
            + "synthetic non-chat diagnostic\n";
        byte[] bytes = fixture.getBytes(StandardCharsets.UTF_8);
        String sourceId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path path = FabricLoader.getInstance().getGameDir().resolve("synthetic-import.log").toAbsolutePath();
        if (report.phase().equals("read")) {
            report.stage("import-persistence", 20_000, () -> {
                check(Files.isRegularFile(path), "Reader must see the writer's original source fixture");
                check(Arrays.equals(bytes, Files.readAllBytes(path)), "Source fixture bytes must survive process restart");
                verifyImported(database, sourceId, raw);
            });
        } else Files.write(path, bytes);

        report.stage("import-ui", 60_000, () -> {
            context.clickScreenButton("Log import");
            context.waitFor(client -> isScreen(client.screen, IMPORT_SCREEN), WAIT_TICKS);
            context.runOnClient(client -> {
                var fields = editBoxes(client.screen);
                check(fields.size() == 2, "Import UI needs explicit path and optional reviewed-name inputs");
                check(fields.get(1).getValue().isEmpty(), "Historical names must remain unreviewed by default");
                fields.getFirst().setValue("");
                client.screen.setFocused(fields.getFirst());
                fields.getFirst().setFocused(true);
            });
            context.getInput().typeChars(path.toString());
            context.waitFor(client -> screenButtonActive(client.screen, "Import log"), WAIT_TICKS);
            context.clickScreenButton("Import log");
            waitForImportComplete(context);
            verifyImported(database, sourceId, raw);
            // Exercise the repeat-import UI path, including successful completion, not just stable row counts.
            context.clickScreenButton("Import log");
            waitForImportComplete(context);
            verifyImported(database, sourceId, raw);
            context.clickScreenButton("View transcripts");
            context.waitFor(client -> isScreen(client.screen, TRANSCRIPT_SCREEN), WAIT_TICKS);
            context.runOnClient(client -> editBoxes(client.screen).getFirst().setValue("Synthetic Visitor"));
            context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Messages 1 to 50 of 62"), WAIT_TICKS);
            report.stage("dialogue-scroll", 60_000, () -> {
                context.runOnClient(client -> {
                    String narration = client.screen.getNarrationMessage().getString();
                    check(narration.indexOf("Synthetic Visitor waves.") != narration.lastIndexOf("Synthetic Visitor waves."),
                        "Two distinct repeated observations must be visible together in the conversation");
                    check(!narration.contains("Render thread") && !narration.contains("[Quiet]") && !narration.contains("Source fingerprint"),
                        "Default conversation must contain cleaned dialogue without logger or diagnostic prefixes");
                    check(scrollPane(client.screen).maxScrollAmount() > 0, "Dialogue needs a real scrollable viewport");
                    check(screenButtonActive(client.screen, "Next") && !screenButtonActive(client.screen, "Previous"), "First bounded page needs next-page navigation");
                });
                context.getInput().setCursorPos(context.computeOnClient(client -> client.getWindow().getWidth() / 2.0),
                    context.computeOnClient(client -> client.getWindow().getHeight() * 0.65));
                context.getInput().scroll(-4);
                context.waitFor(client -> scrollPane(client.screen).scrollAmount() > 0, WAIT_TICKS);
                context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                context.getInput().pressKey(GLFW.GLFW_KEY_END);
                context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("dialogue 50"), WAIT_TICKS);
                context.getInput().scroll(-1);
                context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Messages 51 to 62 of 62"), WAIT_TICKS);
                context.runOnClient(client -> check(!screenButtonActive(client.screen, "Next") && screenButtonActive(client.screen, "Previous"), "Last page must have correct bounds"));
                context.getInput().setCursorPos(context.computeOnClient(client -> client.getWindow().getWidth() / 2.0),
                    context.computeOnClient(client -> client.getWindow().getHeight() * 0.65));
                context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                context.getInput().pressKey(GLFW.GLFW_KEY_END);
                context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("FINAL_DIALOGUE_MARKER"), WAIT_TICKS);
                report.screenshot(context.takeScreenshot("lotc-dialogue-bottom-" + report.phase()));
                context.getInput().pressKey(GLFW.GLFW_KEY_HOME);
                context.getInput().scroll(1);
                context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Messages 1 to 50 of 62"), WAIT_TICKS);
                context.getInput().resizeWindow(640, 480);
                context.waitFor(client -> client.screen.width == 320 && client.screen.height == 240, WAIT_TICKS);
                context.runOnClient(client -> {
                    check(client.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                        .allMatch(button -> button.getY() >= 0 && button.getBottom() <= client.screen.height), "All controls must remain onscreen after resizing");
                    check(scrollPane(client.screen).getBottom() < client.screen.height - 24, "Scrollable dialogue must not overlap fixed footer");
                });
                report.screenshot(context.takeScreenshot("lotc-dialogue-small-" + report.phase()));
                context.getInput().resizeWindow(854, 480);
                context.waitFor(client -> client.screen.width == 427 && client.screen.height == 240, WAIT_TICKS);
            });
            context.getInput().setCursorPos(context.computeOnClient(client -> client.getWindow().getWidth() / 2.0),
                context.computeOnClient(client -> client.getWindow().getHeight() * 0.65));
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT); context.getInput().pressKey(GLFW.GLFW_KEY_HOME);
            context.clickScreenButton("Details");
            context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("line 1")
                && client.screen.getNarrationMessage().getString().contains("Date and timezone unresolved"), WAIT_TICKS);
            context.clickScreenButton("Hide details");
            context.runOnClient(client -> editBoxes(client.screen).getFirst().setValue("NO_SYNTHETIC_MATCH"));
            context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("No matching records"), WAIT_TICKS);
            context.runOnClient(client -> editBoxes(client.screen).getFirst().setValue("Synthetic Visitor"));
            context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Messages 1 to 50 of 62"), WAIT_TICKS);
            report.stage("roleplay-filter", 30_000, () -> {
                context.clickScreenButton("Roleplay only");
                context.waitFor(client -> screenButtonActive(client.screen, "Channel: ROLEPLAY")
                    && client.screen.getNarrationMessage().getString().contains("Messages 1 to 50 of 52"), WAIT_TICKS);
                check(sqlCount(database, "SELECT count(*) FROM transcript_lines WHERE source_id='" + sourceId + "' AND channel='ROLEPLAY'") == 52,
                    "Trusted RP envelopes must be classified without inferring speaker identities");
                report.screenshot(context.takeScreenshot("lotc-roleplay-only-" + report.phase()));
                context.clickScreenButton("UNKNOWN review");
                context.waitFor(client -> screenButtonActive(client.screen, "Channel: UNKNOWN")
                    && client.screen.getNarrationMessage().getString().contains("Messages 1 to 10 of 10"), WAIT_TICKS);
                check(sqlCount(database, "SELECT count(*) FROM transcript_lines WHERE source_id='" + sourceId + "' AND channel='UNKNOWN'") == 10,
                    "Only genuinely unmarked observations should remain in review");
                context.clickScreenButton("Channel: UNKNOWN");
                context.waitFor(client -> screenButtonActive(client.screen, "Channel: ALL")
                    && client.screen.getNarrationMessage().getString().contains("Messages 1 to 50 of 62"), WAIT_TICKS);
            });
            report.screenshot(context.takeScreenshot("lotc-import-transcript-" + report.phase()));
            context.clickScreenButton("Done");
            context.waitFor(client -> isScreen(client.screen, IMPORT_SCREEN), WAIT_TICKS);
            context.clickScreenButton("Done");
            waitForJournal(context);
        });
        report.stage("message-favorites", 40_000, () -> {
            // Use the main journal entry point, independently of the import dashboard.
            context.clickScreenButton("View transcripts");
            context.waitFor(client -> isScreen(client.screen, TRANSCRIPT_SCREEN), WAIT_TICKS);
            context.runOnClient(client -> editBoxes(client.screen).getFirst().setValue("Synthetic Visitor"));
            context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Messages 1 to 50 of 62"), WAIT_TICKS);
            report.stage("message-row-selection", 30_000, () -> {
                context.runOnClient(client -> check(!screenButtonActive(client.screen, "Favorite selected"),
                    "A whole log or first row must not be selected implicitly"));
                transcriptClick(context, 45, 35); // Focus an actual dialogue row, not a header.
                context.getInput().scroll(-1);
                context.waitFor(client -> scrollPane(client.screen).scrollAmount() > 0, WAIT_TICKS);
                // Third record wraps. Click its second visual line after real scrolling.
                double[] point = context.computeOnClient(client -> new double[]{
                    scrollPane(client.screen).getX() + 45,
                    scrollPane(client.screen).getY() + 4 + 40 - scrollPane(client.screen).scrollAmount() + 18});
                transcriptClickAbsolute(context, point[0], point[1]);
                context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Selected line 3."), WAIT_TICKS);
                context.clickScreenButton("Favorite selected");
                context.waitFor(client -> screenButtonActive(client.screen, "Unfavorite selected"), WAIT_TICKS);
                check(sqlCount(database, "SELECT count(*) FROM message_favorites WHERE source_id='" + sourceId + "' AND source_line=3") == 1,
                    "Real click on a wrapped non-first row after scrolling must bookmark only that exact observation");
                check(sqlCount(database, "SELECT count(*) FROM message_favorites WHERE source_line NOT IN (1,3)") == 0,
                    "Clicking a row must never favorite the entire log or adjacent rows");
                report.screenshot(context.takeScreenshot("lotc-selected-message-" + report.phase()));
                context.clickScreenButton("Unfavorite selected");
                context.waitFor(client -> screenButtonActive(client.screen, "Favorite selected"), WAIT_TICKS);
                transcriptClick(context, 45, 35); context.getInput().pressKey(GLFW.GLFW_KEY_HOME);
                context.waitFor(client -> scrollPane(client.screen).scrollAmount() == 0, WAIT_TICKS);
            });
            transcriptClick(context, 45, 8);
            context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Selected line 1."), WAIT_TICKS);
            String firstFavorite = "SELECT count(*) FROM message_favorites WHERE source_id='" + sourceId + "' AND source_line=1";
            check(sqlCount(database, "SELECT count(*) FROM message_favorites") == (report.phase().equals("read") ? 1 : 0),
                "Favorites must start empty and survive the writer-to-reader process restart exactly");
            if (report.phase().equals("read")) check(sqlCount(database, firstFavorite) == 1,
                "Restart must retain the exact first coordinate before any UI mutation");
            else context.clickScreenButton("Favorite selected");
            context.waitFor(client -> screenButtonActive(client.screen, "Unfavorite selected"), WAIT_TICKS);
            check(sqlCount(database, firstFavorite) == 1, "Favorite must target the selected observation");
            check(sqlCount(database, "SELECT count(*) FROM message_favorites WHERE source_line=2") == 0,
                "Identical-looking repeated dialogue must remain a separate unstarred observation");
            context.clickScreenButton("Favorites: All");
            context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Messages 1 to 1 of 1")
                && client.screen.getNarrationMessage().getString().contains("Selected line 1. Favorite: yes"), WAIT_TICKS);
            report.screenshot(context.takeScreenshot("lotc-message-favorites-" + report.phase()));
            // Toggle the visible star through real mouse input; removing the last favorite must clamp/empty safely.
            context.getInput().setCursorPos(context.computeOnClient(client ->
                (scrollPane(client.screen).getX() + 8.0) * client.getWindow().getWidth() / client.screen.width),
                context.computeOnClient(client -> (scrollPane(client.screen).getY() + 8.0) * client.getWindow().getHeight() / client.screen.height));
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("No matching records"), WAIT_TICKS);
            check(sqlCount(database, "SELECT count(*) FROM message_favorites") == 0, "Unfavorite must be committed, not just hidden");
            context.runOnClient(client -> check(!screenButtonActive(client.screen, "Favorite selected"), "Empty view must disable favorite mutation"));
            context.clickScreenButton("Favorites: Only");
            context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Messages 1 to 50 of 62"), WAIT_TICKS);
            transcriptClick(context, 45, 8);
            context.clickScreenButton("Favorite selected");
            context.waitFor(client -> screenButtonActive(client.screen, "Unfavorite selected"), WAIT_TICKS);
            check(sqlCount(database, firstFavorite) == 1, "Writer and reader must leave the same exact bookmark");
            context.clickScreenButton("Done"); waitForJournal(context);
        });
    }

    private static void transcriptClick(ClientGameTestContext context, double relativeX, double relativeY) {
        double[] point = context.computeOnClient(client -> new double[]{scrollPane(client.screen).getX() + relativeX,
            scrollPane(client.screen).getY() + relativeY});
        transcriptClickAbsolute(context, point[0], point[1]);
    }
    private static void transcriptClickAbsolute(ClientGameTestContext context, double guiX, double guiY) {
        context.getInput().setCursorPos(context.computeOnClient(client -> guiX * client.getWindow().getWidth() / client.screen.width),
            context.computeOnClient(client -> guiY * client.getWindow().getHeight() / client.screen.height));
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    private void exerciseLiveAndIdentity(ClientGameTestContext context, SmokeReport report, Path database) throws Exception {
        report.stage("live-capture", 40_000, () -> {
            long before=sqlCount(database,"SELECT count(*) FROM live_events");
            context.clickScreenButton("Resume live capture");
            context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Live CAPTURING"), WAIT_TICKS);
            context.runOnClient(client -> {
                var game=net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.invoker();
                game.onReceiveGameMessage(net.minecraft.network.chat.Component.literal("[Quiet] Synthetic Live Actor waves."),false);
                game.onReceiveGameMessage(net.minecraft.network.chat.Component.literal("[Quiet] Synthetic Live Actor waves."),false);
                net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.CHAT.invoker().onReceiveChatMessage(
                    net.minecraft.network.chat.Component.literal("[RP] Synthetic Live Actor says: hello."),null,null,null,java.time.Instant.now());
                game.onReceiveGameMessage(net.minecraft.network.chat.Component.literal("[PM] LIVE_PRIVATE_SENTINEL"),false);
                game.onReceiveGameMessage(net.minecraft.network.chat.Component.literal("[OOC] LIVE_OOC_SENTINEL"),false);
                game.onReceiveGameMessage(net.minecraft.network.chat.Component.literal("You are now Roleplaying as Synthetic Persona"),false);
                game.onReceiveGameMessage(net.minecraft.network.chat.Component.literal("[Quiet] LIVE_OVERLAY_SENTINEL"),true);
            });
            context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("; saved 3; pending 0"),WAIT_TICKS);
            context.clickScreenButton("Pause live capture");
            context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Live PAUSED"), WAIT_TICKS);
            check(sqlCount(database,"SELECT count(*) FROM live_events")==before+3,"Actual CHAT and non-overlay GAME listeners must persist only three eligible events");
            check(sqlCount(database,"SELECT count(*) FROM transcript_lines WHERE text LIKE '%LIVE_PRIVATE_SENTINEL%' OR text LIKE '%LIVE_OOC_SENTINEL%' OR text LIKE '%LIVE_OVERLAY_SENTINEL%'")==0,"Privacy and overlays must be excluded before storage");
            Path liveRoot=FabricLoader.getInstance().getGameDir().resolve("lotc-journal/live");
            boolean authority=false,readable=false; Path importable=null;
            try(var paths=Files.walk(liveRoot)) {for(Path path:paths.filter(Files::isRegularFile).toList()) {
                if(path.toString().endsWith(".jsonl")||path.toString().endsWith(".chat.txt")) {
                    String text=Files.readString(path);check(!text.contains("LIVE_PRIVATE_SENTINEL")&&!text.contains("LIVE_OOC_SENTINEL")&&!text.contains("LIVE_OVERLAY_SENTINEL"),"Excluded payload must never enter either live file format");
                    authority|=path.toString().endsWith(".jsonl")&&text.contains("Synthetic Live Actor");
                    if(path.toString().endsWith(".jsonl")&&text.contains("Synthetic Live Actor"))importable=path;
                    readable|=path.toString().endsWith(".chat.txt")&&text.contains("Synthetic Live Actor");
                }
            }}check(authority&&readable,"Forced versioned authority and readable dated chat files must both exist");
            report.screenshot(context.takeScreenshot("lotc-live-capture-"+report.phase()));
            Path selectedJournal=importable;byte[] originalJournal=Files.readAllBytes(selectedJournal);
            context.clickScreenButton("Log import");
            context.waitFor(client -> isScreen(client.screen,IMPORT_SCREEN),WAIT_TICKS);
            context.runOnClient(client -> editBoxes(client.screen).getFirst().setValue(selectedJournal.toString()));
            context.clickScreenButton("Import log");
            context.waitFor(client -> screenButtonActive(client.screen,"Import log")&&client.screen.getNarrationMessage().getString().contains("Journal format import complete"),WAIT_TICKS);
            check(sqlCount(database,"SELECT count(*) FROM live_events")==before+3,"Reimporting the native closed journal must reuse event IDs");
            check(Arrays.equals(originalJournal,Files.readAllBytes(selectedJournal)),"Journal format import must leave selected original untouched");
            context.clickScreenButton("Done");waitForJournal(context);
        });
        report.stage("identity-personas",60_000,()->{
            context.runOnClient(client -> search(client.screen).setValue(""));
            if(report.phase().equals("read")) {
                check(sqlCount(database,"SELECT count(*) FROM character_identities")==2,"Manual identities must survive restart");
                check(sqlCount(database,"SELECT count(*) FROM personas")==2,"Distinct persona choices must survive restart");
                check(sqlCount(database,"SELECT count(*) FROM memory_assignments")==2,"Explicit memory assignments must survive restart");
            } else {
                context.clickScreenButton("Characters");
                context.waitFor(client -> isScreen(client.screen,"dev.kat.lotcjournal.fabric.IdentityScreen"),WAIT_TICKS);
                for(String label:List.of("Synthetic Persona A","Synthetic Persona B")) {
                    context.clickScreenButton("New character");
                    context.runOnClient(client -> {var fields=editBoxes(client.screen);fields.get(1).setValue(label);fields.get(2).setValue("Shared Alias");fields.get(4).setValue("Synthetic manual archive note");});
                    context.clickScreenButton("Save character");
                    context.waitFor(client -> screenButtonActive(client.screen,"Use as persona"),WAIT_TICKS);
                    context.clickScreenButton("Use as persona");
                    context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Persona selected"),WAIT_TICKS);
                }
                check(sqlCount(database,"SELECT count(*) FROM character_identities")==2,"Shared aliases must not silently merge distinct identities");
                context.clickScreenButton("Done");waitForJournal(context);
            }
            context.clickScreenButton("View transcripts");
            context.waitFor(client -> isScreen(client.screen,TRANSCRIPT_SCREEN),WAIT_TICKS);
            context.runOnClient(client -> editBoxes(client.screen).getFirst().setValue("Synthetic Visitor waves."));
            context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Messages 1 to 2 of 2"),WAIT_TICKS);
            for(int row=0;row<2;row++) {
                transcriptClick(context,45,8+20*row);
                context.clickScreenButton("Assign");
                context.waitFor(client -> isScreen(client.screen,"dev.kat.lotcjournal.fabric.MemoryAssignmentScreen")
                    && screenButtonActive(client.screen,"Select owning persona"),WAIT_TICKS);
                context.clickScreenButton("Select owning persona");
                context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Confirm only")||client.screen.getNarrationMessage().getString().contains("Existing assignment"),WAIT_TICKS);
                if(row==1) {
                    context.clickScreenButton("Memory of: Synthetic Persona A #"+sqlText(database,"SELECT substr(id,1,6) FROM personas WHERE label='Synthetic Persona A'"));
                    context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Confirm only")||client.screen.getNarrationMessage().getString().contains("Existing assignment"),WAIT_TICKS);
                }
                if(row==0 && !report.phase().equals("read")) context.clickScreenButton("Identity: unassigned (click to choose)");
                context.clickScreenButton(row==1?"Confirm memory; keep identity unassigned":"Confirm assignment");
                context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Assignment confirmed"),WAIT_TICKS);
                context.clickScreenButton("Skip / Done");
                context.waitFor(client -> isScreen(client.screen,TRANSCRIPT_SCREEN),WAIT_TICKS);
            }
            check(sqlCount(database,"SELECT count(*) FROM memory_assignments")==2,"Each message needs its own confirmed persona assignment");
            context.clickScreenButton("Done");waitForJournal(context);
            context.clickScreenButton("Persona memories");
            context.waitFor(client -> isScreen(client.screen,"dev.kat.lotcjournal.fabric.PersonaScreen")&&screenButtonActive(client.screen,"View this persona's memories"),WAIT_TICKS);
            context.clickScreenButton("View this persona's memories");
            context.waitFor(client -> isScreen(client.screen,TRANSCRIPT_SCREEN)&&client.screen.getNarrationMessage().getString().contains("Messages 1 to 1 of 1"),WAIT_TICKS);
            check(context.computeOnClient(client -> client.screen.getTitle().getString().contains("Persona memories")),"Memory view must be clearly distinct from shared archive");
            report.screenshot(context.takeScreenshot("lotc-persona-memories-"+report.phase()));
            context.clickScreenButton("Done");context.clickScreenButton("Done");waitForJournal(context);
        });
    }
    private static String sqlText(Path database,String sql) throws Exception {
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database.toUri()+"?mode=ro");var q=c.createStatement();var r=q.executeQuery(sql)){check(r.next(),"Expected metadata row");return r.getString(1);}
    }

    private void exerciseBatchImport(ClientGameTestContext context, SmokeReport report, Path database) throws Exception {
        Path profile = FabricLoader.getInstance().getGameDir().toAbsolutePath();
        Path folder = profile.resolve("synthetic-batch"), watched = profile.resolve("synthetic-watch");
        String unique = "[12:34:56] [Render thread/INFO]: [CHAT] Batch Visitor waves.\n";
        if (!report.phase().equals("read")) {
            Files.createDirectories(folder); Files.createDirectories(watched);
            Files.copy(profile.resolve("synthetic-import.log"), folder.resolve("known.log"));
            Files.writeString(folder.resolve("new.log"), unique);
            Files.writeString(folder.resolve("corrupt.log.gz"), "not a gzip archive");
            Files.writeString(folder.resolve("latest.log"), "active unfinished log");
        }
        report.stage("batch-workflow", 90_000, () -> {
            if (report.phase().equals("read")) {
                check(sqlCount(database, "SELECT count(*) FROM import_batches WHERE status='UNDONE'") == 1,
                    "Undone batch must survive process restart");
                check(sqlCount(database, "SELECT count(*) FROM removed_observations") == 1,
                    "Removed observations need durable payload-free accounting");
                check(sqlCount(database, "SELECT count(*) FROM transcript_lines WHERE source_id NOT IN (SELECT source_id FROM live_sessions)") == 62,
                    "Restart must retain older records and not resurrect undone batch rows");
                check(sqlCount(database, "SELECT count(*) FROM watch_settings WHERE enabled=1") == 1,
                    "Explicit watch opt-in must persist across JVMs");
            }
            context.clickScreenButton("Log import");
            context.waitFor(client -> isScreen(client.screen, IMPORT_SCREEN), WAIT_TICKS);
            context.clickScreenButton("Import all / dashboard");
            context.waitFor(client -> isScreen(client.screen, FOLDER_SCREEN), WAIT_TICKS);
            if (!report.phase().equals("read")) {
                context.runOnClient(client -> editBoxes(client.screen).getFirst().setValue(folder.toString()));
                context.clickScreenButton("Preview folder");
                context.waitFor(client -> screenButtonActive(client.screen, "Confirm Import all")
                    && client.screen.getNarrationMessage().getString().contains("Preview files 3"), WAIT_TICKS);
                check(sqlCount(database, "SELECT count(*) FROM import_batches") == 0,
                    "Preview must not create a durable batch");
                check(sqlCount(database, "SELECT count(*) FROM transcript_lines WHERE source_id NOT IN (SELECT source_id FROM live_sessions)") == 62,
                    "Preview must not write observations");
                report.screenshot(context.takeScreenshot("lotc-folder-preview-" + report.phase()));
                context.clickScreenButton("Confirm Import all");
                context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Batch PARTIAL"), WAIT_TICKS);
                check(sqlCount(database, "SELECT count(*) FROM transcript_lines WHERE source_id NOT IN (SELECT source_id FROM live_sessions)") == 63,
                    "One new record plus two older records must survive corrupt-file isolation");
                check(sqlCount(database, "SELECT count(*) FROM batch_files WHERE status='DUPLICATE'") == 1,
                    "Known source must be reported as a duplicate without claiming its records");
                check(sqlCount(database, "SELECT count(*) FROM batch_files WHERE status='FAILED'") == 1,
                    "Corrupt archive must fail individually");
                context.clickScreenButton("Import dashboard");
                context.waitFor(client -> isScreen(client.screen, DASHBOARD_SCREEN)
                    && client.screen.getNarrationMessage().getString().contains("PARTIAL"), WAIT_TICKS);
                context.clickScreenButton("Retry failed");
                context.waitFor(client -> screenButtonActive(client.screen, "Retry failed")
                    && client.screen.getNarrationMessage().getString().contains("PARTIAL"), WAIT_TICKS);
                check(sqlCount(database, "SELECT count(*) FROM transcript_lines WHERE source_id NOT IN (SELECT source_id FROM live_sessions)") == 63,
                    "Retrying the unchanged corrupt file must not duplicate successful records");
                context.clickScreenButton("Undo batch");
                context.waitFor(client -> screenButtonActive(client.screen, "Confirm undo"), WAIT_TICKS);
                check(sqlCount(database, "SELECT count(*) FROM transcript_lines WHERE source_id NOT IN (SELECT source_id FROM live_sessions)") == 63,
                    "Undo must require explicit confirmation");
                report.screenshot(context.takeScreenshot("lotc-batch-undo-confirm-" + report.phase()));
                context.clickScreenButton("Confirm undo");
                context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("UNDONE"), WAIT_TICKS);
                check(sqlCount(database, "SELECT count(*) FROM transcript_lines WHERE source_id NOT IN (SELECT source_id FROM live_sessions)") == 62,
                    "Undo must remove only the new batch record");
                check(sqlCount(database, "SELECT count(*) FROM removed_observations") == 1,
                    "Undo must preserve removed-coordinate accounting");
                check(Files.readString(folder.resolve("new.log")).equals(unique), "Undo must not modify original files");
                context.clickScreenButton("Done");
                context.waitFor(client -> isScreen(client.screen, FOLDER_SCREEN), WAIT_TICKS);
                context.runOnClient(client -> editBoxes(client.screen).getFirst().setValue(watched.toString()));
                context.clickScreenButton("Enable folder watch");
                context.waitFor(client -> screenButtonActive(client.screen, "Confirm folder watch"), WAIT_TICKS);
                check(sqlCount(database, "SELECT count(*) FROM watch_settings WHERE enabled=1") == 0,
                    "Watch requires explicit confirmation before persisted opt-in");
                report.screenshot(context.takeScreenshot("lotc-watch-confirm-" + report.phase()));
                context.clickScreenButton("Confirm folder watch");
                context.waitFor(client -> screenButtonActive(client.screen, "Disable folder watch"), WAIT_TICKS);
                check(sqlCount(database, "SELECT count(*) FROM watch_settings WHERE enabled=1") == 1,
                    "Watch opt-in must be committed");
            } else {
                context.waitFor(client -> screenButtonActive(client.screen, "Disable folder watch"), WAIT_TICKS);
                context.clickScreenButton("Disable folder watch");
                context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Folder watch disabled"), WAIT_TICKS);
                check(sqlCount(database, "SELECT count(*) FROM watch_settings WHERE enabled=1") == 0,
                    "Watch disable must persist");
                context.clickScreenButton("Import dashboard");
                context.waitFor(client -> isScreen(client.screen, DASHBOARD_SCREEN)
                    && client.screen.getNarrationMessage().getString().contains("UNDONE"), WAIT_TICKS);
                context.runOnClient(client -> check(!screenButtonActive(client.screen, "Retry failed")
                    && !screenButtonActive(client.screen, "Undo batch"), "Undone batch must be terminal in UI"));
                report.screenshot(context.takeScreenshot("lotc-batch-restart-" + report.phase()));
                context.clickScreenButton("Done");
                context.waitFor(client -> isScreen(client.screen, FOLDER_SCREEN), WAIT_TICKS);
            }
            context.clickScreenButton("Done");
            context.waitFor(client -> isScreen(client.screen, IMPORT_SCREEN), WAIT_TICKS);
            context.clickScreenButton("Done"); waitForJournal(context);
        });
    }

    private static long sqlCount(Path database, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri() + "?mode=ro");
             var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            check(rows.next(), "Count query must return a row"); return rows.getLong(1);
        }
    }

    private static void waitForImportComplete(ClientGameTestContext context) {
        context.waitFor(client -> isScreen(client.screen, IMPORT_SCREEN)
            && screenButtonActive(client.screen, "Import log") && screenButtonActive(client.screen, "View transcripts")
            && client.screen.getNarrationMessage().getString().contains(". COMPLETE."), WAIT_TICKS);
    }

    private static void verifyImported(Path database, String sourceId, String raw) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri() + "?mode=ro")) {
            try (var statement = connection.createStatement(); var result = statement.executeQuery(
                "SELECT count(*) FROM pragma_table_info('transcript_lines') WHERE name='raw'")) {
                check(result.getLong(1) == 0, "Persistent database must have no raw logger-line column");
            }
            try (var query = connection.prepareStatement("SELECT * FROM capture_sources WHERE id=?")) {
                query.setString(1, sourceId);
                try (var source = query.executeQuery()) {
                    check(source.next(), "Import source checkpoint must exist");
                    check(source.getLong("total_lines") == 78 && source.getLong("processed_lines") == 78
                        && source.getLong("imported_lines") == 62 && source.getLong("excluded_lines") == 2
                        && source.getLong("ignored_lines") == 14 && source.getInt("complete") == 1,
                        "Import counters and completion checkpoint must survive repeat import/restart");
                    check(!source.next(), "Content identity must identify one source");
                }
            }
            try (var query = connection.prepareStatement("SELECT * FROM transcript_lines WHERE source_id=? ORDER BY source_line")) {
                query.setString(1, sourceId);
                try (var rows = query.executeQuery()) {
                    for (int coordinate = 1; coordinate <= 62; coordinate++) {
                        check(rows.next() && rows.getLong("source_line") == coordinate, "Repeated observations need distinct coordinates");
                        check(dialogue(coordinate).equals(rows.getString("text")), "Database must contain cleaned dialogue and preserve genuine repeated coordinates");
                        check("23:59:59".equals(rows.getString("clock")), "Original clock must remain unchanged");
                        check((coordinate <= 52 ? "ROLEPLAY" : "UNKNOWN").equals(rows.getString("channel")) && rows.getString("observed_speaker") == null,
                            "Channel evidence must not become an inferred speaker or character identity");
                    }
                    check(!rows.next(), "Repeat import must not add duplicates or excluded payloads");
                }
            }
            try (var query = connection.createStatement();
                 var rows = query.executeQuery("SELECT count(*) FROM transcript_lines WHERE text LIKE '%IMPORT_PRIVATE_SENTINEL%' OR text LIKE '%IMPORT_OOC_SENTINEL%'")) {
                check(rows.next() && rows.getInt(1) == 0, "PM/OOC payloads must never enter stored transcripts");
            }
        }
    }

    private static List<String> systemNotices() {
        return List.of("You are now Roleplaying as Synthetic Persona",
            "Set size for persona Synthetic Persona to: 1.0 (180cm, 71in, 5'11\")",
            "JourneyMap: Press [J]", "You have friend requests from: SyntheticAccount",
            "====== Latest News ======", "• [Link] Synthetic news update",
            "Your Soulstone will recharge in 5 seconds.", "Your Soulstone begins to glow.",
            "Showing matches to Persona name synthetic", "Synthetic Persona is a Persona of SyntheticAccount",
            "The key did not fit!", "Can you build? Yes", "Applicable regions: synthetic_region, synthetic_biome");
    }

    private static String dialogue(int coordinate) {
        if (coordinate <= 2) return "Synthetic Visitor waves.";
        if (coordinate == 3) return "Synthetic Visitor says: " + "A wrapped message for individual selection. ".repeat(5);
        if (coordinate == 62) return "Synthetic Visitor says: \"" + "A longer synthetic sentence. ".repeat(40)
            + "The [CHAT] mark inside speech stays. FINAL_DIALOGUE_MARKER.\"";
        return "Synthetic Visitor says dialogue " + coordinate + ".";
    }
    private static AbstractScrollArea scrollPane(Screen screen) {
        return screen.children().stream().filter(AbstractScrollArea.class::isInstance).map(AbstractScrollArea.class::cast).findFirst()
            .orElseThrow(() -> new AssertionError("Native dialogue scroll pane is missing"));
    }

    private static boolean isScreen(Screen screen, String name) {
        return screen != null && screen.getClass().getName().equals(name);
    }

    private static List<EditBox> editBoxes(Screen screen) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList();
    }

    private static boolean screenButtonActive(Screen screen, String label) {
        return screen != null && screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
            .anyMatch(button -> button.active && button.getMessage().getString().equals(label));
    }

    private static void waitForJournal(ClientGameTestContext context) {
        context.waitFor(client -> isJournal(client.screen)
            && (hasButton(client.screen, "Layout: Comfortable") || hasButton(client.screen, "Layout: Compact")), WAIT_TICKS);
    }

    private static void toggle(ClientGameTestContext context, boolean compact) {
        context.clickScreenButton(compact ? "Layout: Comfortable" : "Layout: Compact");
        context.waitFor(client -> hasButton(client.screen, compact ? "Layout: Compact" : "Layout: Comfortable"), WAIT_TICKS);
    }

    private static boolean isJournal(Screen screen) {
        return screen != null && screen.getClass().getName().equals(SCREEN_CLASS);
    }

    private static EditBox search(Screen screen) {
        check(isJournal(screen), "Journal screen must be open");
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst()
            .orElseThrow(() -> new AssertionError("Search field is missing"));
    }

    private static boolean hasButton(Screen screen, String text) {
        return isJournal(screen) && screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
            .anyMatch(button -> button.active && button.getMessage().getString().equals(text));
    }

    private static List<Path> backupFiles(Path directory) throws Exception {
        if (!Files.exists(directory)) return List.of();
        try (var paths = Files.list(directory)) { return paths.filter(Files::isRegularFile).toList(); }
    }

    private static boolean readLayout(Path database) throws Exception {
        check(Files.isRegularFile(database), "Journal database must already exist; harness must not initialize it");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri() + "?mode=ro");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT value FROM journal_settings WHERE key='compact_layout'")) {
            check(result.next(), "Saved layout row is missing");
            String value = result.getString(1);
            check(value.equals("true") || value.equals("false"), "Invalid saved layout value");
            return Boolean.parseBoolean(value);
        }
    }

    private static boolean isJar(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation().toString().endsWith(".jar");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
