package dev.kat.lotcjournal.fabric;

import dev.kat.lotcjournal.application.JournalService;
import dev.kat.lotcjournal.storage.SqliteJournalStore;
import java.nio.file.Files;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

@SuppressWarnings("UnstableApiUsage")
public final class JournalClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try {
            var directory = Files.createTempDirectory("lotc-journal-client-test");
            var store = new SqliteJournalStore(directory.resolve("test.sqlite"));
            try (var service = new JournalService(store, store)) {
                service.start().get(10, java.util.concurrent.TimeUnit.SECONDS);
                context.runOnClient(client -> client.setScreen(new JournalScreen(service, directory)));
                context.waitForScreen(JournalScreen.class);
                context.takeScreenshot("lotc-journal-foundation");
                context.runOnClient(client -> client.setScreen(null));
            }
        } catch (Exception error) { throw new RuntimeException("Journal client test failed", error); }
    }
}

