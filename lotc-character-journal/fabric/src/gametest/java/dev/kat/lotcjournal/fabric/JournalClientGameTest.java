package dev.kat.lotcjournal.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Arrays;
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
                    check(metadata.getMetadata().getVersion().getFriendlyString().equals("0.1.0-alpha.1"),
                        "Unexpected tested journal version");
                    var adapter = Class.forName("dev.kat.lotcjournal.fabric.JournalClient");
                    var sqlite = Class.forName("org.sqlite.JDBC");
                    report.origin("journal", adapter);
                    report.origin("sqlite", sqlite);
                    if (report.mode().equals("packaged")) {
                        check(isJar(adapter) && isJar(sqlite), "Packaged run must load journal and SQLite from JARs");
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
