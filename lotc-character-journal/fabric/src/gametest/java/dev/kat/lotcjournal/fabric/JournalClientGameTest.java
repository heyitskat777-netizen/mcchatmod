package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.application.JournalService;
import dev.kat.lotcjournal.application.JournalSnapshot;
import dev.kat.lotcjournal.storage.SqliteJournalStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;

@SuppressWarnings("UnstableApiUsage")
public final class JournalClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try {
            var directory = Files.createTempDirectory("lotc-journal-client-test");
            var store = new SqliteJournalStore(directory.resolve("test.sqlite"));
            try (var service = new JournalService(store, store)) {
                service.start().get(10, TimeUnit.SECONDS);
                context.runOnClient(client -> client.setScreen(new JournalScreen(service, directory)));
                context.waitForScreen(JournalScreen.class);
                context.waitFor(client -> hasButton((JournalScreen) client.screen, "Layout: Comfortable"));
                context.runOnClient(client -> {
                    var screen = (JournalScreen) client.screen;
                    check(!screen.isPauseScreen(), "Journal must not pause play");
                    check(screen.children().stream().anyMatch(EditBox.class::isInstance),
                        "Journal search field must be present");
                });
                context.clickScreenButton("Layout: Comfortable");
                context.waitFor(client -> service.snapshot().settings().compactLayout());
                context.waitFor(client -> hasButton((JournalScreen) client.screen, "Layout: Compact"));
                context.clickScreenButton("Create backup");
                context.waitFor(client -> service.snapshot().message().equals("Backup created"));
                Path saved;
                try (var backups = Files.list(directory.resolve("backups"))) {
                    var files = backups.toList();
                    check(files.size() == 1, "Backup action must produce exactly one snapshot");
                    saved = files.getFirst();
                }
                var restored = new SqliteJournalStore(saved);
                restored.initialize();
                check(restored.load().compactLayout(), "Backup must contain the selected layout");
                context.clickScreenButton("Layout: Compact");
                context.waitFor(client -> !service.snapshot().settings().compactLayout());
                check(restored.load().compactLayout(), "Backup must remain an independent snapshot");
                context.takeScreenshot("lotc-journal-foundation");
                context.clickScreenButton("Done");
                context.waitFor(client -> !(client.screen instanceof JournalScreen));
            }
            // Reconstruct both adapters: this is persistence, not merely a cached snapshot.
            var reopened = new SqliteJournalStore(directory.resolve("test.sqlite"));
            try (var service = new JournalService(reopened, reopened)) {
                service.start().get(10, TimeUnit.SECONDS);
                check(service.snapshot().status() == JournalSnapshot.Status.READY,
                    "Journal must reopen successfully");
                check(!service.snapshot().settings().compactLayout(),
                    "Reopened journal must retain the most recently saved layout");
                context.runOnClient(client -> client.setScreen(new JournalScreen(service, directory)));
                context.waitForScreen(JournalScreen.class);
                context.waitFor(client -> hasButton((JournalScreen) client.screen, "Layout: Comfortable"));
                context.clickScreenButton("Done");
                context.waitFor(client -> !(client.screen instanceof JournalScreen));
            }
        } catch (Exception error) { throw new RuntimeException("Journal client test failed", error); }
    }

    private static boolean hasButton(JournalScreen screen, String text) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
            .anyMatch(button -> button.active && button.getMessage().getString().equals(text));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

