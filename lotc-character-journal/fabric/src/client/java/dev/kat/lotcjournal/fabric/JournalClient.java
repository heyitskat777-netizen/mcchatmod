package dev.kat.lotcjournal.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kat.lotcjournal.application.JournalService;
import dev.kat.lotcjournal.storage.SqliteJournalStore;
import dev.kat.lotcjournal.ingestion.LogImportService;
import dev.kat.lotcjournal.ingestion.BatchImportService;
import dev.kat.lotcjournal.ingestion.FolderWatchService;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Composition root: the only place that wires concrete storage to the journal service. */
public final class JournalClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("lotc_journal");
    private JournalService service;
    private LogImportService importer;
    private BatchImportService batches;
    private FolderWatchService watcher;

    @Override public void onInitializeClient() {
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("lotc-journal");
        SqliteJournalStore store = new SqliteJournalStore(directory.resolve("journal.sqlite"));
        service = new JournalService(store, store);
        importer = new LogImportService(store);
        batches = new BatchImportService(importer, store);
        watcher = new FolderWatchService(batches, store);
        service.start().thenCompose(ignored -> watcher.load()).exceptionally(error -> {
            LOGGER.error("Could not initialize local journal", error);
            return null;
        });
        var category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("lotc_journal", "journal"));
        var open = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.lotc_journal.open", InputConstants.Type.KEYSYM, InputConstants.KEY_J, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (open.consumeClick()) {
                if (client.screen == null && client.player != null)
                    client.setScreen(new JournalScreen(service, importer, batches, watcher, directory));
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            watcher.close();
            batches.close();
            importer.close();
            service.close();
        });
    }
}

