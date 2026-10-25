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
            exerciseImport(context, report, database);
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
        String raw = "[23:59:59] [Render thread/INFO]: [System] [CHAT] §aSynthetic Visitor§r waves.";
        String fixture = raw + "\n" + raw + "\n"
            + "[00:00:00] [Render thread/INFO]: [CHAT] [PM] IMPORT_PRIVATE_SENTINEL\n"
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
            context.waitFor(client -> isScreen(client.screen, TRANSCRIPT_SCREEN)
                && client.screen.getNarrationMessage().getString().contains("Record 1 of 2"), WAIT_TICKS);
            context.runOnClient(client -> {
                String narration = client.screen.getNarrationMessage().getString();
                check(narration.contains("line 1.") && narration.contains("Channel UNKNOWN"),
                    "Transcript must expose source coordinate and unresolved channel");
                check(narration.contains("Date and timezone unresolved"), "UI must not invent a date or timezone");
                check(screenButtonActive(client.screen, "Next") && !screenButtonActive(client.screen, "Previous"),
                    "Transcript must start at the first source observation");
            });
            context.clickScreenButton("Next");
            context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Record 2 of 2"), WAIT_TICKS);
            context.runOnClient(client -> check(client.screen.getNarrationMessage().getString().contains("line 2.")
                && !screenButtonActive(client.screen, "Next") && screenButtonActive(client.screen, "Previous"),
                "Repeated text must navigate to a distinct second source coordinate"));
            context.clickScreenButton("Previous");
            context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Record 1 of 2"), WAIT_TICKS);
            context.runOnClient(client -> editBoxes(client.screen).getFirst().setValue("NO_SYNTHETIC_MATCH"));
            context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("No matching records"), WAIT_TICKS);
            context.runOnClient(client -> editBoxes(client.screen).getFirst().setValue("Synthetic Visitor"));
            context.waitFor(client -> client.screen.getNarrationMessage().getString().contains("Record 1 of 2"), WAIT_TICKS);
            report.screenshot(context.takeScreenshot("lotc-import-transcript-" + report.phase()));
            context.clickScreenButton("Done");
            context.waitFor(client -> isScreen(client.screen, IMPORT_SCREEN), WAIT_TICKS);
            context.clickScreenButton("Done");
            waitForJournal(context);
        });
    }

    private static void waitForImportComplete(ClientGameTestContext context) {
        context.waitFor(client -> isScreen(client.screen, IMPORT_SCREEN)
            && screenButtonActive(client.screen, "Import log") && screenButtonActive(client.screen, "View transcripts")
            && client.screen.getNarrationMessage().getString().contains(". COMPLETE."), WAIT_TICKS);
    }

    private static void verifyImported(Path database, String sourceId, String raw) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri() + "?mode=ro")) {
            try (var query = connection.prepareStatement("SELECT * FROM capture_sources WHERE id=?")) {
                query.setString(1, sourceId);
                try (var source = query.executeQuery()) {
                    check(source.next(), "Import source checkpoint must exist");
                    check(source.getLong("total_lines") == 5 && source.getLong("processed_lines") == 5
                        && source.getLong("imported_lines") == 2 && source.getLong("excluded_lines") == 2
                        && source.getLong("ignored_lines") == 1 && source.getInt("complete") == 1,
                        "Import counters and completion checkpoint must survive repeat import/restart");
                    check(!source.next(), "Content identity must identify one source");
                }
            }
            try (var query = connection.prepareStatement("SELECT * FROM transcript_lines WHERE source_id=? ORDER BY source_line")) {
                query.setString(1, sourceId);
                try (var rows = query.executeQuery()) {
                    for (int coordinate = 1; coordinate <= 2; coordinate++) {
                        check(rows.next() && rows.getLong("source_line") == coordinate, "Repeated observations need distinct coordinates");
                        check(raw.equals(rows.getString("raw")), "Transcript must retain original observed bytes as UTF-8 text");
                        check("23:59:59".equals(rows.getString("clock")), "Original clock must remain unchanged");
                        check("UNKNOWN".equals(rows.getString("channel")) && rows.getString("observed_speaker") == null,
                            "Unreviewed names must stay unresolved and ineligible for AI");
                    }
                    check(!rows.next(), "Repeat import must not add duplicates or excluded payloads");
                }
            }
            try (var query = connection.createStatement();
                 var rows = query.executeQuery("SELECT count(*) FROM transcript_lines WHERE raw LIKE '%IMPORT_PRIVATE_SENTINEL%' OR raw LIKE '%IMPORT_OOC_SENTINEL%'")) {
                check(rows.next() && rows.getInt(1) == 0, "PM/OOC payloads must never enter stored transcripts");
            }
        }
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
