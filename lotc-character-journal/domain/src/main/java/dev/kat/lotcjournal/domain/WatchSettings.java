package dev.kat.lotcjournal.domain;

import java.util.Set;

/** Saved opt-in intent; restoring it must never resume an interrupted batch. */
public record WatchSettings(String folder, boolean enabled, Set<String> reviewedNames) {
    public WatchSettings(String folder, boolean enabled) { this(folder, enabled, Set.of()); }
    public WatchSettings {
        if (folder != null) BatchFile.requireText(folder, 4096, "watch folder");
        if (enabled && folder == null) throw new IllegalArgumentException("Enabled watcher needs a folder");
        reviewedNames = BatchPlan.checkedNames(reviewedNames);
    }
    public static WatchSettings defaults() { return new WatchSettings(null, false, Set.of()); }
}
